package dev.lumenberg.device

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NotificationStoreTest {
    @Test
    fun `active notifications are saved newest first`() {
        val root = createTempDir(prefix = "lumenberg-notifications-")
        try {
            val store = ActiveNotificationStore(root)
            store.write(
                listOf(
                    StoredNotification("old", "mail", "Old", "One", 10L, true),
                    StoredNotification("new", "chat", "New", "Two", 20L, false),
                ),
            )

            assertEquals(listOf("new", "old"), store.read().map { it.key })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt notification snapshot does not poison reads`() {
        val root = createTempDir(prefix = "lumenberg-notifications-")
        try {
            root.mkdirs()
            File(root, "notifications.json").writeText("not json")
            assertEquals(emptyList<StoredNotification>(), ActiveNotificationStore(root).read())
        } finally {
            root.deleteRecursively()
        }
    }
}
