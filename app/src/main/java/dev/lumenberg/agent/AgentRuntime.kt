package dev.lumenberg.agent

import dev.lumenberg.ai.Account
import dev.lumenberg.ai.AiError
import dev.lumenberg.ai.ModelGateway
import dev.lumenberg.ai.ModelMessage
import dev.lumenberg.ai.Turn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

class AgentRuntime(
    private val client: ModelGateway,
    private val policy: PolicyEngine = DefaultPolicy,
) {
    suspend fun run(
        account: Account,
        history: List<Turn>,
        registry: ToolRegistry,
        context: String = "",
        onDelta: suspend (String) -> Unit,
    ): String {
        val messages = mutableListOf<ModelMessage>()
        if (context.isNotBlank()) messages += ModelMessage.Context(context.trim())
        messages += repairHistory(history).map { ModelMessage.Text(it.role, it.text) }
        val specs = if (account.provider.supportsTools) registry.specs else emptyList()

        repeat(MAX_ROUNDS) {
            val reply = client.send(account, messages, specs, onDelta)
            if (reply.calls.isEmpty()) return reply.text
            if (specs.isEmpty()) {
                return reply.text.ifBlank { "This model cannot use phone actions." }
            }

            messages += ModelMessage.AssistantAction(reply.text, reply.calls)
            val results = coroutineScope {
                reply.calls.map { call -> async { execute(call, registry) } }.map { it.await() }
            }
            messages += ModelMessage.ToolOutputs(results)
        }

        throw AiError("The assistant kept trying actions without finishing.")
    }

    private suspend fun execute(call: ToolCall, registry: ToolRegistry): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult(call.id, call.name, "Unknown tool: ${call.name}", false)
        if (policy.decide(tool.spec) != PolicyDecision.ALLOW) {
            return ToolResult(call.id, call.name, "That action is not allowed without approval.", false)
        }
        val arguments = runCatching { JSONObject(call.arguments) }.getOrNull()
            ?: return ToolResult(call.id, call.name, "The tool arguments were not valid JSON.", false)
        return runCatching { tool.execute(arguments) }
            .fold(
                onSuccess = { ToolResult(call.id, call.name, it, true) },
                onFailure = {
                    ToolResult(call.id, call.name, it.message ?: "The action failed.", false)
                },
            )
    }

    companion object {
        const val MAX_ROUNDS = 8
    }
}

class OpenAppTool(
    apps: List<String>,
    private val open: (String) -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = "open_app",
        description = buildString {
            append("Open one installed Android app by name. Installed apps: ")
            append(apps.joinToString(", "))
        },
        parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
            put("required", JSONArray().put("name"))
            put("additionalProperties", false)
        },
        risk = ToolRisk.LOCAL,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val name = arguments.optString("name").trim()
        require(name.isNotEmpty()) { "An app name is required." }
        check(open(name)) { "There is no installed app matching \"$name\"." }
        return "Opened $name."
    }
}

internal fun repairHistory(history: List<Turn>): List<Turn> {
    val out = mutableListOf<Turn>()
    history.forEach { turn ->
        when {
            out.isEmpty() && turn.role != "user" -> Unit
            out.isNotEmpty() && out.last().role == turn.role ->
                out[out.lastIndex] = out.last().copy(text = out.last().text + "\n\n" + turn.text)
            else -> out += turn
        }
    }
    return out
}
