package dev.lumenberg.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelClientTest {
    private val client = ModelClient()

    @Test
    fun `openai stream assembles a tool call`() {
        val state = StreamState(Wire.OPENAI)
        state.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"open_","arguments":"{\"na"}}]}}}]}"""))
        state.accept(JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"app","arguments":"me\":\"Camera\"}"}}]}}}]}"""))

        val reply = state.finish()
        assertEquals(1, reply.calls.size)
        assertEquals("call-1", reply.calls.single().id)
        assertEquals("open_app", reply.calls.single().name)
        assertEquals("Camera", JSONObject(reply.calls.single().arguments).getString("name"))
    }

    @Test
    fun `anthropic stream assembles a tool call`() {
        val state = StreamState(Wire.ANTHROPIC)
        state.accept(JSONObject("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"open_app","input":{}}}"""))
        state.accept(JSONObject("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"name\":\"Camera\"}"}}"""))

        val reply = state.finish()
        assertEquals(1, reply.calls.size)
        assertEquals("tool-1", reply.calls.single().id)
        assertEquals("open_app", reply.calls.single().name)
        assertEquals("Camera", JSONObject(reply.calls.single().arguments).getString("name"))
    }

    @Test
    fun `streamed prose stays prose`() {
        val state = StreamState(Wire.OPENAI)
        assertEquals("Hello", state.accept(JSONObject("""{"choices":[{"delta":{"content":"Hello"}}]}""")))
        assertEquals(" world", state.accept(JSONObject("""{"choices":[{"delta":{"content":" world"}}]}""")))
        assertEquals("Hello world", state.finish().text)
    }

    @Test
    fun `the newest matching model wins`() {
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
    fun `only verified provider paths expose tools`() {
        assertTrue(Provider.OPENAI.supportsTools)
        assertTrue(Provider.ANTHROPIC.supportsTools)
        assertTrue(Provider.OPENROUTER.supportsTools)
        assertFalse(Provider.COPILOT.supportsTools)
        assertFalse(Provider.OLLAMA.supportsTools)
    }

    @Test
    fun `every key provider explains where its key comes from`() {
        Provider.entries.forEach { provider ->
            when (provider.signIn) {
                SignIn.KEY -> assertTrue(provider.keyUrl != null)
                else -> assertNull(provider.keyUrl)
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
