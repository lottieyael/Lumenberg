package dev.lumenberg.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextTest {
    @Test
    fun `unavailable sources are omitted instead of described as empty`() {
        val text = renderDeviceContext(
            DeviceSnapshot(
                battery = BatteryItem(72, false, "battery"),
            ),
        )

        assertTrue(text.contains("Battery: 72%"))
        assertFalse(text.contains("Active notifications"))
        assertFalse(text.contains("Upcoming calendar"))
        assertFalse(text.contains("Last known location"))
    }

    @Test
    fun `available empty sources are explicitly empty`() {
        val text = renderDeviceContext(
            DeviceSnapshot(
                notifications = emptyList(),
                calendar = emptyList(),
            ),
        )

        assertTrue(text.contains("Active notifications: none"))
        assertTrue(text.contains("Upcoming calendar: none"))
    }

    @Test
    fun `live context stays compact and strips line breaks`() {
        val text = renderDeviceContext(
            DeviceSnapshot(
                notifications = listOf(
                    NotificationItem("mail", "Mail", "New\nmessage", "Hello\rthere", 1L, true),
                ),
                usage = listOf(UsageItem("music", "Music", 125_000L)),
                mediaAvailable = true,
                media = MediaItem("music", "Music", "Track", "Artist", "Album", "playing"),
                contactsAvailable = true,
            ),
        )

        assertTrue(text.contains("Mail: New message | Hello there"))
        assertTrue(text.contains("Top app usage today: Music 2m"))
        assertTrue(text.contains("Media: playing in Music, Track by Artist"))
        assertTrue(text.contains("Contacts are available"))
    }
}
