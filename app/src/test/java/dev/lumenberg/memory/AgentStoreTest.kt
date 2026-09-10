package dev.lumenberg.memory

import dev.lumenberg.ai.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AgentStoreTest {
    @Test
    fun `editing and deleting use identity and preserve provenance`() {
        val root = Files.createTempDirectory("memory-edit").toFile()
        try {
            val store = AgentStore(root)
            val first = store.remember("Likes tea", sourcePrompt = "Remember that I like tea")
            val second = store.remember("Likes tea in the morning")
            store.edit(first.id, "Prefers coffee")
            val edited = AgentStore(root).loadMemories().first { it.id == first.id }
            assertEquals("Remember that I like tea", edited.sourcePrompt)
            assertEquals("Prefers coffee", edited.text)
            assertTrue(edited.editedByUser)
            store.delete(first.id)
            assertEquals(second.id, store.loadMemories().single().id)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `conversation survives a new store instance`() {
        val root = Files.createTempDirectory("lumenberg-agent").toFile()
        try {
            AgentStore(root).apply {
                appendTurn(Turn("user", "one"))
                appendTurn(Turn("assistant", "two"))
            }

            assertEquals(
                listOf(Turn("user", "one"), Turn("assistant", "two")),
                AgentStore(root).loadTurns(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `remember deduplicates and forget removes matches`() {
        val root = Files.createTempDirectory("lumenberg-memory").toFile()
        try {
            val store = AgentStore(root)
            val first = store.remember("Prefers concise answers")
            val second = store.remember("prefers concise answers")

            assertEquals(first.id, second.id)
            assertEquals(1, store.loadMemories().size)
            assertEquals(1, store.searchMemories("concise").size)
            assertEquals(1, store.forget("concise"))
            assertTrue(store.loadMemories().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt lines do not erase valid history`() {
        val root = Files.createTempDirectory("lumenberg-corrupt").toFile()
        try {
            val store = AgentStore(root)
            store.appendTurn(Turn("user", "keep me"))
            root.resolve("conversation.jsonl").appendText("not json\n")

            assertEquals(listOf(Turn("user", "keep me")), store.loadTurns())
        } finally {
            root.deleteRecursively()
        }
    }
}
