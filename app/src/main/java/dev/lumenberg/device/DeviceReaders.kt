package dev.lumenberg.device

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

data class CalendarItem(
    val id: Long,
    val title: String,
    val location: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendar: String,
)

data class ContactItem(
    val id: Long,
    val name: String,
    val number: String,
    val type: Int,
)

data class UsageItem(
    val packageName: String,
    val app: String,
    val foregroundMs: Long,
)

data class MediaItem(
    val packageName: String,
    val app: String,
    val title: String,
    val artist: String,
    val album: String,
    val state: String,
)

data class BatteryItem(
    val percent: Int,
    val charging: Boolean,
    val plugged: String,
)

data class LocationItem(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val observedAt: Long,
    val provider: String,
)

class CalendarReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun upcoming(hours: Int = 48, limit: Int = 20): List<CalendarItem> = withContext(Dispatchers.IO) {
        check(access.snapshot().calendar) { "Calendar access is not enabled." }
        val begin = System.currentTimeMillis()
        val end = begin + hours.coerceIn(1, 24 * 30) * 60L * 60L * 1000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<CalendarItem>()
        resolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val title = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val location = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val start = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val calendar = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext() && out.size < limit.coerceIn(1, 50)) {
                out += CalendarItem(
                    id = cursor.getLong(id),
                    title = cursor.getString(title).orEmpty(),
                    location = cursor.getString(location).orEmpty(),
                    begin = cursor.getLong(start),
                    end = cursor.getLong(finish),
                    allDay = cursor.getInt(allDay) != 0,
                    calendar = cursor.getString(calendar).orEmpty(),
                )
            }
        }
        out
    }
}

class ContactReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun search(query: String, limit: Int = 10): List<ContactItem> = withContext(Dispatchers.IO) {
        check(access.snapshot().contacts) { "Contacts access is not enabled." }
        val clean = query.trim()
        require(clean.isNotEmpty()) { "A contact search is required." }
        val phone = ContactsContract.CommonDataKinds.Phone
        val uri = Uri.withAppendedPath(phone.CONTENT_FILTER_URI, Uri.encode(clean)).buildUpon()
            .appendQueryParameter(phone.SEARCH_DISPLAY_NAME_KEY, "true")
            .appendQueryParameter(phone.SEARCH_PHONE_NUMBER_KEY, "true")
            .build()
        val projection = arrayOf(
            phone.CONTACT_ID,
            phone.DISPLAY_NAME_PRIMARY,
            phone.NUMBER,
            phone.TYPE,
        )
        val out = mutableListOf<ContactItem>()
        resolver.query(
            uri,
            projection,
            null,
            null,
            "${phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(phone.CONTACT_ID)
            val name = cursor.getColumnIndexOrThrow(phone.DISPLAY_NAME_PRIMARY)
            val number = cursor.getColumnIndexOrThrow(phone.NUMBER)
            val type = cursor.getColumnIndexOrThrow(phone.TYPE)
            while (cursor.moveToNext() && out.size < limit.coerceIn(1, 30)) {
                out += ContactItem(
                    id = cursor.getLong(id),
                    name = cursor.getString(name).orEmpty(),
                    number = cursor.getString(number).orEmpty(),
                    type = cursor.getInt(type),
                )
            }
        }
        out.distinctBy { it.id to it.number }
    }
}

class UsageReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val context = context.applicationContext
    private val manager = requireNotNull(this.context.getSystemService(UsageStatsManager::class.java))

    suspend fun today(limit: Int = 10): List<UsageItem> = withContext(Dispatchers.IO) {
        check(access.snapshot().usage) { "Usage access is not enabled." }
        val begin = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, System.currentTimeMillis())
            .orEmpty()
            .asSequence()
            .filter { it.totalTimeInForeground > 0L && it.packageName != context.packageName }
            .groupBy { it.packageName }
            .map { (packageName, rows) ->
                UsageItem(
                    packageName = packageName,
                    app = appLabel(packageName),
                    foregroundMs = rows.sumOf { it.totalTimeInForeground },
                )
            }
            .sortedByDescending { it.foregroundMs }
            .take(limit.coerceIn(1, 30))
            .toList()
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

class MediaReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val context = context.applicationContext
    private val manager = requireNotNull(this.context.getSystemService(MediaSessionManager::class.java))
    private val listener = ComponentName(this.context, LumenbergNotificationListener::class.java)

    suspend fun current(): MediaItem? = withContext(Dispatchers.IO) {
        check(access.snapshot().notifications) { "Notification access is needed to inspect media sessions." }
        primary()?.let(::item)
    }

    suspend fun control(action: String): MediaItem = withContext(Dispatchers.IO) {
        check(access.snapshot().notifications) { "Notification access is needed to control media." }
        val controller = primary() ?: error("There is no active media session.")
        when (action.lowercase()) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
            else -> error("Media action must be play, pause, next, or previous.")
        }
        item(controller)
    }

    private fun primary(): MediaController? = runCatching { manager.getActiveSessions(listener) }
        .getOrElse { throw IllegalStateException("Android did not grant access to active media sessions.") }
        .maxByOrNull { priority(it.playbackState?.state) }

    private fun item(controller: MediaController): MediaItem {
        val metadata = controller.metadata
        return MediaItem(
            packageName = controller.packageName,
            app = appLabel(controller.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            state = stateName(controller.playbackState?.state),
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun priority(state: Int?): Int = when (state) {
        PlaybackState.STATE_PLAYING -> 4
        PlaybackState.STATE_BUFFERING -> 3
        PlaybackState.STATE_PAUSED -> 2
        else -> 1
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        else -> "unknown"
    }
}

class BatteryReader(private val context: Context) {
    fun current(): BatteryItem? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return BatteryItem(
            percent = (level * 100f / scale).toInt().coerceIn(0, 100),
            charging = plugged != 0,
            plugged = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                else -> "battery"
            },
        )
    }
}

class LocationReader(
    context: Context,
    private val access: DeviceAccess,
) {
    private val manager = requireNotNull(context.applicationContext.getSystemService(LocationManager::class.java))

    suspend fun lastKnown(): LocationItem? = withContext(Dispatchers.IO) {
        check(access.snapshot().location) { "Location access is not enabled." }
        manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { location ->
                LocationItem(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    observedAt = location.time,
                    provider = location.provider.orEmpty(),
                )
            }
    }
}
