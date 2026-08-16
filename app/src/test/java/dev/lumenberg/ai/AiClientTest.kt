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

    @Test
    fun `DeepSeek picks its chat model over its other endpoints`() {
        val models = listOf("deepseek-chat", "deepseek-reasoner")
        assertEquals("deepseek-reasoner", client.preferred(Provider.DEEPSEEK, models))
    }

    @Test
    fun `Copilot picks a current model from the seat catalogue`() {
        val models = listOf("gpt-4o", "gpt-5", "claude-sonnet-4", "text-embedding-3-small")
        assertEquals("gpt-5", client.preferred(Provider.COPILOT, models))
    }

    // --- stream token parsing ---------------------------------------------

    private fun token(json: String) = client.tokenOf(org.json.JSONObject(json))

    @Test
    fun `a reasoning model's empty content chunks produce nothing, not the word null`() {
        // DeepSeek's reasoner sends content: null while it is still thinking.
        val chunk = """{"choices":[{"delta":{"content":null,"reasoning_content":"hmm"}}]}"""
        assertNull(token(chunk))
    }

    @Test
    fun `an ordinary OpenAI-shaped chunk yields its text`() {
        assertEquals("Hey", token("""{"choices":[{"delta":{"content":"Hey"}}]}"""))
    }

    @Test
    fun `an Anthropic chunk yields its text`() {
        assertEquals("Hey", token("""{"delta":{"text":"Hey"}}"""))
    }

    @Test
    fun `a missing content field yields nothing`() {
        assertNull(token("""{"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun `a keep-alive shaped event yields nothing`() {
        assertNull(token("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun `a non-streaming message body still yields its text`() {
        assertEquals("Hi", token("""{"choices":[{"message":{"content":"Hi"}}]}"""))
    }

    // --- provider wiring --------------------------------------------------

    @Test
    fun `anywhere a key can be pasted, users are told where to get one`() {
        Provider.entries.forEach { provider ->
            when (provider.signIn) {
                // A browser sign-in can still fall back to a pasted key, so it needs the link too.
                SignIn.KEY, SignIn.OAUTH -> assertEquals(
                    "${provider.label} must tell users where to get a key",
                    true,
                    provider.keyUrl != null,
                )
                // Nothing to paste: a device flow shows a code, and a local machine has no key.
                SignIn.DEVICE, SignIn.HOST -> assertNull(provider.keyUrl)
            }
        }
    }

    @Test
    fun `only Anthropic uses its own wire format`() {
        Provider.entries.forEach { provider ->
            val expected = if (provider == Provider.ANTHROPIC) Wire.ANTHROPIC else Wire.OPENAI
            assertEquals(expected, provider.wire)
        }
    }

    @Test
    fun `a saved provider id survives a round trip`() {
        Provider.entries.forEach { assertEquals(it, Provider.of(it.id)) }
        assertEquals(Provider.OPENROUTER, Provider.of("something-we-removed"))
    }
}
