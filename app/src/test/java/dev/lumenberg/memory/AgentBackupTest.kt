package dev.lumenberg.memory

import dev.lumenberg.ai.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class AgentBackupTest {
    @Test
    fun `profile survives a round trip`() {
        val root = Files.createTempDirectory("lumenberg-profile").toFile()
        try {
            val store = AgentProfileStore(root)
            store.save(AgentProfile("Nova", Verbosity.DETAILED, Warmth.WARM))

            assertEquals("Nova", store.load().name)
            assertEquals(Verbosity.DETAILED, store.load().verbosity)
            assertEquals(Warmth.WARM, store.load().warmth)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `portable backup restores agent files`() {
        val source = Files.createTempDirectory("lumenberg-export").toFile()
        val target = Files.createTempDirectory("lumenberg-import").toFile()
        try {
            AgentProfileStore(source).apply {
                save(AgentProfile("Nova", Verbosity.SHORT, Warmth.COOL))
                avatarFile.writeBytes(byteArrayOf(1, 2, 3))
            }
            AgentStore(source).apply {
                remember("Prefers trains")
                appendTurn(Turn("user", "hello"))
            }

            val bytes = ByteArrayOutputStream().also { AgentBackup.export(source, it) }.toByteArray()
            AgentBackup.import(target, ByteArrayInputStream(bytes))

            assertEquals("Nova", AgentProfileStore(target).load().name)
            assertEquals("Prefers trains", AgentStore(target).loadMemories().single().text)
            assertEquals(listOf(Turn("user", "hello")), AgentStore(target).loadTurns())
            assertTrue(AgentProfileStore(target).avatarFile.exists())
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }
}
