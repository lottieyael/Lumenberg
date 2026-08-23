package dev.lumenberg.device

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class NotificationItem(
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val clearable: Boolean,
)

internal data class StoredNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val clearable: Boolean,
)

internal class ActiveNotificationStore(private val root: File) {
    private val file = File(root, "notifications.json")

    @Synchronized
    fun read(): List<StoredNotification> = runCatching {
        if (!file.exists()) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    StoredNotification(
                        key = item.getString("key"),
                        packageName = item.getString("package"),
                        title = item.optString("title"),
                        text = item.optString("text"),
                        postedAt = item.optLong("postedAt"),
                        clearable = item.optBoolean("clearable"),
                    )
                }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun write(items: List<StoredNotification>) {
        root.mkdirs()
        val temp = File(root, "notifications.json.tmp")
        val array = JSONArray()
        items.sortedByDescending { it.postedAt }.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("package", item.packageName)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("postedAt", item.postedAt)
                    .put("clearable", item.clearable),
            )
        }
        temp.writeText(array.toString())
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAX_ITEMS = 100
    }
}

class LumenbergNotificationListener : NotificationListenerService() {
    private val store by lazy { ActiveNotificationStore(File(filesDir, "device")) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        persist()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        persist()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        persist()
    }

    private fun persist() {
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        val items = active.asSequence()
            .filter { it.packageName != packageName }
            .map(::stored)
            .toList()
        runCatching { store.write(items) }
    }

    private fun stored(sbn: StatusBarNotification): StoredNotification {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        )
            .filterNotNull()
            .map { it.toString().trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return StoredNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            postedAt = sbn.postTime,
            clearable = sbn.isClearable,
        )
    }
}

class NotificationReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val context = context.applicationContext
    private val store = ActiveNotificationStore(File(this.context.filesDir, "device"))

    suspend fun active(limit: Int = 20, packageFilter: String? = null): List<NotificationItem> =
        withContext(Dispatchers.IO) {
            check(access.snapshot().notifications) { "Notification access is not enabled." }
            val filter = packageFilter?.trim()?.takeIf { it.isNotEmpty() }
            store.read().asSequence()
                .filter { item ->
                    filter == null || item.packageName.contains(filter, ignoreCase = true) ||
                        appLabel(item.packageName).contains(filter, ignoreCase = true)
                }
                .take(limit.coerceIn(1, 50))
                .map { item ->
                    NotificationItem(
                        packageName = item.packageName,
                        app = appLabel(item.packageName),
                        title = item.title,
                        text = item.text,
                        postedAt = item.postedAt,
                        clearable = item.clearable,
                    )
                }
                .toList()
        }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
