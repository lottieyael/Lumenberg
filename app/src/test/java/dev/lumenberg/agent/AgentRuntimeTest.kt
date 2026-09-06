package dev.lumenberg.agent

import dev.lumenberg.ai.Account
import dev.lumenberg.ai.ModelGateway
import dev.lumenberg.ai.ModelMessage
import dev.lumenberg.ai.ModelReply
import dev.lumenberg.ai.Provider
import dev.lumenberg.ai.Turn
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    private val account = Account(provider = Provider.OPENAI, credential = "test", model = "gpt-test")

    @Test
    fun `a tool call executes once and its result goes back to the model`() = runBlocking {
        val gateway = FakeGateway(
            ModelReply("", listOf(ToolCall("call-1", "test_tool", "{}"))),
            ModelReply("Done."),
        )
        val tool = CountingTool()
        val runtime = AgentRuntime(gateway)

        val reply = runtime.run(
            account,
            listOf(Turn("user", "do it")),
            ToolRegistry(listOf(tool)),
        ) {}

        assertEquals("Done.", reply)
        assertEquals(1, tool.calls)
        val outputs = gateway.requests[1].filterIsInstance<ModelMessage.ToolOutputs>().single()
        assertTrue(outputs.results.single().success)
    }

    @Test
    fun `malformed arguments never execute the tool`() = runBlocking {
        val gateway = FakeGateway(
            ModelReply("", listOf(ToolCall("call-1", "test_tool", "{"))),
            ModelReply("Could not do that."),
        )
        val tool = CountingTool()

        AgentRuntime(gateway).run(
            account,
            listOf(Turn("user", "do it")),
            ToolRegistry(listOf(tool)),
        ) {}

        assertEquals(0, tool.calls)
        val outputs = gateway.requests[1].filterIsInstance<ModelMessage.ToolOutputs>().single()
        assertFalse(outputs.results.single().success)
    }

    @Test
    fun `an unknown tool is reported instead of executed`() = runBlocking {
        val gateway = FakeGateway(
            ModelReply("", listOf(ToolCall("call-1", "made_up", "{}"))),
            ModelReply("I cannot do that."),
        )
        val known = CountingTool()

        AgentRuntime(gateway).run(
            account,
            listOf(Turn("user", "do it")),
            ToolRegistry(listOf(known)),
        ) {}

        assertEquals(0, known.calls)
        val outputs = gateway.requests[1].filterIsInstance<ModelMessage.ToolOutputs>().single()
        assertFalse(outputs.results.single().success)
        assertTrue(outputs.results.single().content.contains("Unknown tool"))
    }

    @Test
    fun `policy denial prevents execution`() = runBlocking {
        val gateway = FakeGateway(
            ModelReply("", listOf(ToolCall("call-1", "test_tool", "{}"))),
            ModelReply("I need approval."),
        )
        val tool = CountingTool(risk = ToolRisk.EXTERNAL)

        AgentRuntime(gateway).run(
            account,
            listOf(Turn("user", "do it")),
            ToolRegistry(listOf(tool)),
        ) {}

        assertEquals(0, tool.calls)
        val outputs = gateway.requests[1].filterIsInstance<ModelMessage.ToolOutputs>().single()
        assertFalse(outputs.results.single().success)
    }

    @Test
    fun `multiple calls each execute exactly once`() = runBlocking {
        val gateway = FakeGateway(
            ModelReply(
                "",
                listOf(
                    ToolCall("call-1", "first", "{}"),
                    ToolCall("call-2", "second", "{}"),
                ),
            ),
            ModelReply("Both done."),
        )
        val first = CountingTool("first")
        val second = CountingTool("second")

        val reply = AgentRuntime(gateway).run(
            account,
            listOf(Turn("user", "do both")),
            ToolRegistry(listOf(first, second)),
        ) {}

        assertEquals("Both done.", reply)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun `providers without verified tool support stay text only`() = runBlocking {
        val gateway = FakeGateway(ModelReply("Plain answer."))
        val local = Account(provider = Provider.OLLAMA, model = "local", host = "http://localhost:11434/v1")

        val reply = AgentRuntime(gateway).run(
            local,
            listOf(Turn("user", "hello")),
            ToolRegistry(listOf(CountingTool())),
        ) {}

        assertEquals("Plain answer.", reply)
        assertTrue(gateway.toolRequests.single().isEmpty())
    }

    @Test
    fun `history repair starts with user and merges duplicate roles`() {
        val repaired = repairHistory(
            listOf(
                Turn("assistant", "old"),
                Turn("user", "one"),
                Turn("user", "two"),
                Turn("assistant", "three"),
            ),
        )

        assertEquals(listOf("user", "assistant"), repaired.map { it.role })
        assertEquals("one\n\ntwo", repaired.first().text)
    }

    private class CountingTool(
        name: String = "test_tool",
        risk: ToolRisk = ToolRisk.LOCAL,
    ) : Tool {
        var calls = 0

        override val spec = ToolSpec(
            name = name,
            description = "Test tool",
            parameters = JSONObject().put("type", "object"),
            risk = risk,
        )

        override suspend fun execute(arguments: JSONObject): String {
            calls++
            return "ok"
        }
    }

    private class FakeGateway(vararg replies: ModelReply) : ModelGateway {
        private val replies = replies.toMutableList()
        val requests = mutableListOf<List<ModelMessage>>()
        val toolRequests = mutableListOf<List<ToolSpec>>()

        override suspend fun send(
            account: Account,
            messages: List<ModelMessage>,
            tools: List<ToolSpec>,
            onDelta: suspend (String) -> Unit,
        ): ModelReply {
            requests += messages.toList()
            toolRequests += tools.toList()
            return replies.removeAt(0)
        }
    }
}
