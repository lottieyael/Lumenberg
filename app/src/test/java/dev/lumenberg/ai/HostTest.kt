package dev.lumenberg.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Nobody typing an address into a phone should have to know what a base path is. */
class HostTest {

    @Test
    fun `a bare address becomes a usable base`() {
        assertEquals("http://192.168.1.20:11434/v1", normalizeHost("192.168.1.20"))
    }

    @Test
    fun `an explicit port is kept`() {
        assertEquals("http://192.168.1.20:1234/v1", normalizeHost("192.168.1.20:1234"))
    }

    @Test
    fun `https is not downgraded`() {
        assertEquals("https://ai.example.com:11434/v1", normalizeHost("https://ai.example.com"))
    }

    @Test
    fun `an already complete base is left alone`() {
        assertEquals("http://box:11434/v1", normalizeHost("http://box:11434/v1/"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", normalizeHost("   "))
    }
}
