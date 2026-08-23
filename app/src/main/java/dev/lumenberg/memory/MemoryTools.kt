package dev.lumenberg.memory

import dev.lumenberg.agent.Tool
import dev.lumenberg.agent.ToolRisk
import dev.lumenberg.agent.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

class RememberTool(private val store: AgentStore) : Tool {
    override val spec = ToolSpec(
        name = "remember",
        description = "Store a durable fact or preference about the user when they ask you to remember it.",
        parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
            put("required", JSONArray().put("text"))
            put("additionalProperties", false)
        },
        risk = ToolRisk.LOCAL,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val text = arguments.optString("text").trim()
        require(text.isNotEmpty()) { "Memory text is required." }
        store.remember(text)
        return "Remembered."
    }
}

class ForgetTool(private val store: AgentStore) : Tool {
    override val spec = ToolSpec(
        name = "forget",
        description = "Remove durable memories matching what the user explicitly asks you to forget.",
        parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
            put("required", JSONArray().put("query"))
            put("additionalProperties", false)
        },
        risk = ToolRisk.LOCAL,
    )

    override suspend fun execute(arguments: JSONObject): String {
        val query = arguments.optString("query").trim()
        require(query.isNotEmpty()) { "A memory query is required." }
        val count = store.forget(query)
        return if (count == 0) "No matching memory found." else "Forgot $count matching memory item${if (count == 1) "." else "s."}"
    }
}
