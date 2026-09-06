package dev.lumenberg.agent

import org.json.JSONObject

enum class ToolRisk {
    READ,
    LOCAL,
    EXTERNAL,
    WEALTH,
    DESTRUCTIVE,
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val risk: ToolRisk,
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val success: Boolean,
)

interface Tool {
    val spec: ToolSpec

    suspend fun execute(arguments: JSONObject): String
}

class ToolRegistry(tools: List<Tool>) {
    private val tools = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> get() = tools.values.map { it.spec }

    fun get(name: String): Tool? = tools[name]
}
