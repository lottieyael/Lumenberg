package dev.lumenberg.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single activity the system exposes as launchable. */
data class LauncherApp(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val launches: Long = 0L,
) {
    val key: String get() = component.flattenToString()
}

/**
 * The installed-app list, kept off the main thread and refreshed by the system rather
 * than by polling. One instance per process.
 */
class AppRepository(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val launcherApps = requireNotNull(this.context.getSystemService(LauncherApps::class.java))
    private val usage = this.context.getSharedPreferences("app_usage", Context.MODE_PRIVATE)
    // 64 icons at 128px is about 4 MB, which is what a launcher should cost at rest.
    private val icons = LruCache<String, ImageBitmap>(64)

    private val _apps = MutableStateFlow(emptyList<LauncherApp>())
    val apps: StateFlow<List<LauncherApp>> = _apps.asStateFlow()

    private val watcher = object : LauncherApps.Callback() {
        override fun onPackageAdded(name: String?, user: UserHandle?) = refresh()
        override fun onPackageRemoved(name: String?, user: UserHandle?) = refresh()
        override fun onPackageChanged(name: String?, user: UserHandle?) = refresh()
        override fun onPackagesAvailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(names: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
    }

    private var pending: Job? = null

    fun start() {
        launcherApps.registerCallback(watcher)
        // Returning to the home screen must not re-read every installed activity.
        if (_apps.value.isEmpty()) refresh()
    }

    fun stop() = launcherApps.unregisterCallback(watcher)

    /**
     * One install fires several callbacks, so reads are coalesced. A later call always
     * wins, which is what makes the list correct rather than merely recent.
     */
    fun refresh() {
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(250)
            runCatching { read() }.onSuccess { _apps.value = it }
        }
    }

    private fun read(): List<LauncherApp> {
        val me = Process.myUserHandle()
        return launcherApps.getActivityList(null, me)
            .asSequence()
            .filter { it.componentName.packageName != context.packageName }
            .map {
                LauncherApp(
                    label = it.label?.toString().orEmpty().ifBlank { it.componentName.packageName },
                    component = it.componentName,
                    user = me,
                    launches = usage.getLong(it.componentName.flattenToString(), 0L),
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun ranked(query: String, apps: List<LauncherApp>, limit: Int): List<LauncherApp> {
        if (apps.isEmpty()) return emptyList()
        return apps.asSequence()
            .map { it to SearchRanker.score(query, it.label, it.launches) }
            .filter { it.second != SearchRanker.NO_MATCH }
            .sortedWith(compareByDescending<Pair<LauncherApp, Long>> { it.second }.thenBy { it.first.label })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Returns true when the app actually started. */
    fun launch(app: LauncherApp): Boolean = runCatching {
        launcherApps.startMainActivity(app.component, app.user, null, null)
        usage.edit().putLong(app.key, app.launches + 1).apply()
        // Keep the in-memory list in step so ranking reflects the launch immediately.
        _apps.value = _apps.value.map { if (it.key == app.key) it.copy(launches = it.launches + 1) else it }
        true
    }.getOrDefault(false)

    fun openInfo(app: LauncherApp) = runCatching {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    /** Non-blocking peek, so an already-decoded icon never flashes a placeholder first. */
    fun cachedIcon(app: LauncherApp): ImageBitmap? = icons.get(app.key)

    suspend fun icon(app: LauncherApp): ImageBitmap? {
        icons.get(app.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = launcherApps.getActivityList(app.component.packageName, app.user)
                    .firstOrNull { it.componentName == app.component }
                    ?.getIcon(0)
                    ?: context.packageManager.getActivityIcon(app.component)
                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                drawable.bounds = Rect(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap().also { icons.put(app.key, it) }
            }.getOrNull()
        }
    }
}
