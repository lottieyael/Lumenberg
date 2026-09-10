package dev.lumenberg.cards

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HomeCardStoreTest {
    @Test fun `pinned answers survive restart and refresh preserves identity`() {
        val root = Files.createTempDirectory("cards").toFile()
        try {
            val store = HomeCardStore(root)
            val card = store.pin("Today's plan", "Old answer")
            val restarted = HomeCardStore(root)
            assertEquals(card, restarted.load().single())
            restarted.update(card.id, "New answer")
            assertEquals("New answer", store.load().single().text)
            assertEquals(card.prompt, store.load().single().prompt)
            assertEquals(card.id, store.load().single().id)
            store.remove(card.id)
            restarted.update(card.id, "Late refresh must not recreate the card")
            assertTrue(store.load().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun `empty refresh does not destroy previous content`() {
        val root = Files.createTempDirectory("cards").toFile()
        try {
            val store = HomeCardStore(root)
            val card = store.pin("Plan", "Keep me")
            assertTrue(runCatching { store.update(card.id, " ") }.isFailure)
            assertEquals(card, store.load().single())
        } finally { root.deleteRecursively() }
    }
}
