package dev.lumenberg.device

import android.content.Context
import dev.lumenberg.agent.Tool
import dev.lumenberg.agent.ToolRisk
import dev.lumenberg.agent.ToolSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DeviceSnapshot(
    val notifications: List<NotificationItem>? = null,
    val calendar: List<CalendarItem>? = null,
    val usage: List<UsageItem>? = null,
    val contactsAvailable: Boolean = false,
    val mediaAvailable: Boolean = false,
    val media: MediaItem? = null,
    val locationAvailable: Boolean = false,
    val location: LocationItem? = null,
    val battery: BatteryItem? = null,
)

class DeviceRuntime(context: Context) {
    private val access = DeviceAccess(context.applicationContext)
    private val notifications = NotificationReader(context, access)
    private val calendar = CalendarReader(context, access)
    private val contacts = ContactReader(context, access)
    private val usage = UsageReader(context, access)
    private val media = MediaReader(context, access)
    private val battery = BatteryReader(context.applicationContext)
    private val location = LocationReader(context, access)

    fun accessState(): DeviceAccessState = access.snapshot()

    fun tools(): List<Tool> = listOf(
        ReadNotificationsTool(notifications),
        UpcomingCalendarTool(calendar),
        FindContactTool(contacts),
        UsageTool(usage),
        MediaStateTool(media),
        MediaControlTool(media),
        LocationTool(location),
        BatteryTool(battery),
    )

    suspend fun promptContext(): String = coroutineScope {
        val grants = access.snapshot()
        val notificationJob = if (grants.notifications) async { runCatching { notifications.active(6) }.getOrNull() } else null
        val calendarJob = if (grants.calendar) async { runCatching { calendar.upcoming(48, 5) }.getOrNull() } else null
        val usageJob = if (grants.usage) async { runCatching { usage.today(5) }.getOrNull() } else null
        val mediaJob = if (grants.notifications) async { runCatching { media.current() }.getOrNull() } else null
        val locationJob = if (grants.location) async { runCatching { location.lastKnown() }.getOrNull() } else null

        renderDeviceContext(
            DeviceSnapshot(
                notifications = notificationJob?.await(),
                calendar = calendarJob?.await(),
                usage = usageJob?.await(),
                contactsAvailable = grants.contacts,
                mediaAvailable = grants.notifications,
                media = mediaJob?.await(),
                locationAvailable = grants.location,
                location = locationJob?.await(),
                battery = battery.current(),
            ),
        )
    }
}

internal fun renderDeviceContext(snapshot: DeviceSnapshot): String = buildString {
    append("Current device context:")
    snapshot.battery?.let { item ->
        append("\nBattery: ${item.percent}%")
        if (item.charging) append(", charging via ${item.plugged}")
    }
    snapshot.media?.let { item ->
        append("\nMedia: ${item.state} in ${item.app}")
        if (item.title.isNotBlank()) append(", ${item.title.clean()}")
        if (item.artist.isNotBlank()) append(" by ${item.artist.clean()}")
    }
    snapshot.notifications?.let { items ->
        if (items.isEmpty()) {
            append("\nActive notifications: none")
        } else {
            append("\nActive notifications:")
            items.forEach { item ->
                append("\n- ${item.app}: ")
                append(listOf(item.title, item.text).filter(String::isNotBlank).joinToString(" | ").clean())
            }
        }
    }
    snapshot.calendar?.let { items ->
        if (items.isEmpty()) {
            append("\nUpcoming calendar: none in the next 48 hours")
        } else {
            append("\nUpcoming calendar:")
            items.forEach { item ->
                append("\n- ${formatTime(item.begin)} ${item.title.clean()}")
                if (item.location.isNotBlank()) append(" at ${item.location.clean()}")
            }
        }
    }
    snapshot.usage?.let { items ->
        if (items.isNotEmpty()) {
            append("\nTop app usage today: ")
            append(items.joinToString(", ") { "${it.app} ${minutes(it.foregroundMs)}m" })
        }
    }
    if (snapshot.contactsAvailable) append("\nContacts are available through find_contact when needed.")
    if (snapshot.locationAvailable) {
        snapshot.location?.let { item ->
            append("\nLast known location: ${"%.5f".format(item.latitude)}, ${"%.5f".format(item.longitude)}")
            append(" (${item.accuracyMeters.toInt()}m accuracy, observed ${formatTime(item.observedAt)})")
        }
    }
}

private class ReadNotificationsTool(private val reader: NotificationReader) : Tool {
    override val spec = ToolSpec(
        name = "read_notifications",
        description = "Read active Android notifications. Optionally filter by app name or package.",
        parameters = schema(
            "package" to JSONObject().put("type", "string"),
            "limit" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50),
        ),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val items = reader.active(
            limit = arguments.optInt("limit", 20),
            packageFilter = arguments.optString("package").takeIf(String::isNotBlank),
        )
        return JSONArray().apply { items.forEach { put(it.json()) } }.toString()
    }
}

private class UpcomingCalendarTool(private val reader: CalendarReader) : Tool {
    override val spec = ToolSpec(
        name = "upcoming_calendar",
        description = "Read upcoming calendar events from calendars stored on this phone.",
        parameters = schema(
            "hours" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 720),
            "limit" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50),
        ),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val items = reader.upcoming(arguments.optInt("hours", 48), arguments.optInt("limit", 20))
        return JSONArray().apply { items.forEach { put(it.json()) } }.toString()
    }
}

private class FindContactTool(private val reader: ContactReader) : Tool {
    override val spec = ToolSpec(
        name = "find_contact",
        description = "Find a saved contact and phone number by name or number.",
        parameters = schema(
            "query" to JSONObject().put("type", "string"),
            "limit" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30),
            required = listOf("query"),
        ),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val items = reader.search(arguments.optString("query"), arguments.optInt("limit", 10))
        return JSONArray().apply { items.forEach { put(it.json()) } }.toString()
    }
}

private class UsageTool(private val reader: UsageReader) : Tool {
    override val spec = ToolSpec(
        name = "app_usage",
        description = "Read today's foreground app usage, ordered by time used.",
        parameters = schema(
            "limit" to JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30),
        ),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val items = reader.today(arguments.optInt("limit", 10))
        return JSONArray().apply { items.forEach { put(it.json()) } }.toString()
    }
}

private class MediaStateTool(private val reader: MediaReader) : Tool {
    override val spec = ToolSpec(
        name = "media_state",
        description = "Read the active media session and what is currently playing or paused.",
        parameters = schema(),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String =
        reader.current()?.json()?.toString() ?: JSONObject().put("active", false).toString()
}

private class MediaControlTool(private val reader: MediaReader) : Tool {
    override val spec = ToolSpec(
        name = "media_control",
        description = "Control the current media session without opening its app.",
        parameters = schema(
            "action" to JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("play", "pause", "next", "previous"))),
            required = listOf("action"),
        ),
        risk = ToolRisk.LOCAL,
    )

    override suspend fun execute(arguments: JSONObject): String =
        reader.control(arguments.optString("action")).json().toString()
}

private class LocationTool(private val reader: LocationReader) : Tool {
    override val spec = ToolSpec(
        name = "device_location",
        description = "Read the phone's most recent known foreground location and its age and accuracy.",
        parameters = schema(),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String =
        reader.lastKnown()?.json()?.toString() ?: JSONObject().put("available", false).toString()
}

private class BatteryTool(private val reader: BatteryReader) : Tool {
    override val spec = ToolSpec(
        name = "battery_state",
        description = "Read the phone battery level and charging state.",
        parameters = schema(),
        risk = ToolRisk.READ,
    )

    override suspend fun execute(arguments: JSONObject): String =
        reader.current()?.json()?.toString() ?: JSONObject().put("available", false).toString()
}

private fun schema(
    vararg properties: Pair<String, JSONObject>,
    required: List<String> = emptyList(),
): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply { properties.forEach { (name, value) -> put(name, value) } })
    if (required.isNotEmpty()) put("required", JSONArray(required))
    put("additionalProperties", false)
}

private fun NotificationItem.json() = JSONObject()
    .put("package", packageName)
    .put("app", app)
    .put("title", title)
    .put("text", text)
    .put("postedAt", postedAt)
    .put("clearable", clearable)

private fun CalendarItem.json() = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("location", location)
    .put("begin", begin)
    .put("end", end)
    .put("allDay", allDay)
    .put("calendar", calendar)

private fun ContactItem.json() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("number", number)
    .put("type", type)

private fun UsageItem.json() = JSONObject()
    .put("package", packageName)
    .put("app", app)
    .put("foregroundMs", foregroundMs)

private fun MediaItem.json() = JSONObject()
    .put("package", packageName)
    .put("app", app)
    .put("title", title)
    .put("artist", artist)
    .put("album", album)
    .put("state", state)

private fun LocationItem.json() = JSONObject()
    .put("latitude", latitude)
    .put("longitude", longitude)
    .put("accuracyMeters", accuracyMeters.toDouble())
    .put("observedAt", observedAt)
    .put("provider", provider)

private fun BatteryItem.json() = JSONObject()
    .put("percent", percent)
    .put("charging", charging)
    .put("plugged", plugged)

private fun formatTime(epochMs: Long): String = FORMATTER.format(Instant.ofEpochMilli(epochMs))

private fun minutes(ms: Long): Long = (ms / 60_000L).coerceAtLeast(1L)

private fun String.clean(): String = replace('\n', ' ').replace('\r', ' ').trim().take(180)

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
    .withZone(ZoneId.systemDefault())
