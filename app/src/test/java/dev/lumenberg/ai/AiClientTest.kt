package dev.lumenberg.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiClientTest {
    private val client = AiClient()

    // --- the launch marker ------------------------------------------------

    @Test
    fun `a reply that is only a marker launches an app`() {
        val reply = client.finish("[[open:Camera]]")
        assertEquals("Camera", reply.open)
        assertEquals("", reply.text)
    }

    @Test
    fun `a marker inside a sentence is left as text`() {
        val reply = client.finish("Sure, opening [[open:Camera]] now")
        assertNull(reply.open)
    }

    @Test
    fun `quoting the marker back at the user does not launch anything`() {
        val reply = client.finish("The string [[open:Settings]] is what tells me to open an app.")
        assertNull(reply.open)
    }

    @Test
    fun `surrounding whitespace does not stop a launch`() {
        assertEquals("Clock", client.finish("  [[open: Clock ]]\n").open)
    }

    @Test
    fun `ordinary prose is returned untouched`() {
        assertEquals("It is 12 degrees.", client.finish("  It is 12 degrees.  ").text)
    }

    // --- history repair ---------------------------------------------------

    @Test
    fun `a gap left by an app launch does not break alternation`() {
        val repaired = client.alternating(
            listOf(Turn("user", "open camera"), Turn("user", "what is the weather")),
        )
        assertEquals(listOf("user"), repaired.map { it.role })
        assertEquals("open camera\n\nwhat is the weather", repaired.first().text)
    }

    @Test
    fun `a history starting on the assistant is trimmed to start on the user`() {
        val repaired = client.alternating(
            listOf(Turn("assistant", "hi"), Turn("user", "hello"), Turn("assistant", "yes")),
        )
        assertEquals(listOf("user", "assistant"), repaired.map { it.role })
    }

    @Test
    fun `a well-formed history is left alone`() {
        val turns = listOf(Turn("user", "a"), Turn("assistant", "b"), Turn("user", "c"))
        assertEquals(turns, client.alternating(turns))
    }

    // --- model choice -----------------------------------------------------

    @Test
    fun `the newest matching model wins, not the alphabetically first`() {
        val models = listOf("gpt-3.5-turbo", "gpt-4o", "gpt-5")
        assertEquals("gpt-5", client.preferred(Provider.OPENAI, models))
    }

    @Test
    fun `models that cannot hold a conversation are skipped`() {
        val models = listOf("text-embedding-3-large", "whisper-1", "gpt-4o")
        assertEquals("gpt-4o", client.preferred(Provider.OPENAI, models))
    }

    @Test
    fun `a provider hint is honoured over catalogue order`() {
        val models = listOf("anthropic/claude-sonnet-4", "meta/llama-3", "openai/gpt-5")
        assertEquals("openai/gpt-5", client.preferred(Provider.OPENROUTER, models))
    }

    @Test
    fun `an unfamiliar catalogue still yields a model`() {
        assertEquals("some-model", client.preferred(Provider.OLLAMA, listOf("some-model")))
    }

    @Test
    fun `an empty catalogue yields nothing`() {
        assertNull(client.preferred(Provider.OPENAI, emptyList()))
    }
}
