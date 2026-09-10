package dev.lumenberg.core

import org.junit.Assert.*
import org.junit.Test

class LocalCommandTest {
    @Test fun `launches exact names without a model`() {
        assertEquals(LocalCommand.Open("Maps"), localCommand("  OPEN   maps ", listOf("Maps")))
        assertEquals(LocalCommand.Open("Music"), localCommand("Music", listOf("Music")))
    }
    @Test fun `ambiguous names never launch an arbitrary app`() {
        assertEquals(LocalCommand.Ambiguous("Maps"), localCommand("open Maps", listOf("Maps", "Maps")))
    }
    @Test fun `media commands are anchored`() {
        assertEquals(LocalCommand.Media("pause"), localCommand("pause music", emptyList()))
        assertEquals(LocalCommand.Media("next"), localCommand("next track", emptyList()))
        assertNull(localCommand("explain how to pause music", emptyList()))
        assertNull(localCommand("play a song about rain", emptyList()))
        assertNull(localCommand("open my notes from yesterday", listOf("Notes")))
    }
}
