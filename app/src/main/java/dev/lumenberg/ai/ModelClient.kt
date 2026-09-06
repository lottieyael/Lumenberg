package dev.lumenberg.ai

import dev.lumenberg.agent.ToolCall
import dev.lumenberg.agent.ToolResult
import dev.lumenberg.agent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class Turn(val role: String, val text: String)

sealed interface ModelMessage {
    data class Context(val text: String) : ModelMessage
    data class Text(val role: String, val text: String) : ModelMessage
    data class AssistantAction(val text: String, val calls: List<ToolCall>) : ModelMessage
    data class ToolOutputs(val results: List<ToolResult>) : ModelMessage
}

data class ModelReply(
    val text: String,
    val calls: List<ToolCall> = emptyList(),
)

interface ModelGateway {
    suspend fun send(
        account: Account,
        messages: List<ModelMessage>,
        tools: List<ToolSpec>,
        onDelta: suspend (String) -> Unit,
    ): ModelReply
}

class ModelClient : ModelGateway {

    suspend fun models(account: Account): List<String> = withContext(Dispatchers.IO) {
        val data = JSONObject(get(account, "/models")).optJSONArray("data") ?: JSONArray()
        (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            .map { it.removePrefix("models/") }
            .distinct()
            .sorted()
    }

    fun preferred(provider: Provider, models: List<String>): String? {
        val usable = models.filterNot { id -> NOT_CHAT.any { id.contains(it, ignoreCase = true) } }
            .ifEmpty { models }
        provider.prefer.forEach { hint ->
            usable.filter { it.contains(hint, ignoreCase = true) }
                .maxWithOrNull(String.CASE_INSENSITIVE_ORDER)
                ?.let { return it }
        }
        return usable.firstOrNull()
    }

    override suspend fun send(
        account: Account,
        messages: List<ModelMessage>,
        tools: List<ToolSpec>,
        onDelta: suspend (String) -> Unit,
    ): ModelReply = withContext(Dispatchers.IO) {
        require(account.ready) { "No AI account is set up yet." }
        val body = when (account.provider.wire) {
            Wire.OPENAI -> openAiBody(account, messages, tools)
            Wire.ANTHROPIC -> anthropicBody(account, messages, tools)
        }
        val path = if (account.provider.wire == Wire.ANTHROPIC) "/messages" else "/chat/completions"
        val connection = open(account, path, "POST")
        val state = StreamState(account.provider.wire)

        val closer = coroutineContext[Job]?.invokeOnCompletion {
            if (it != null) runCatching { connection.disconnect() }
        }

        try {
            connection.doOutput = true
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AiError(humanize(status, detail, account))
            }

            connection.inputStream.bufferedReader().use { reader ->
                val event = StringBuilder()
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine()
                    if (line == null) {
                        consume(event, state, account)?.let { onDelta(it) }
                        break
                    }
                    when {
                        line.isEmpty() -> consume(event, state, account)?.let { onDelta(it) }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") ->
                            event.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                        else -> Unit
                    }
                }
            }
        } finally {
            closer?.dispose()
            connection.disconnect()
        }
        state.finish()
    }

    private fun consume(event: StringBuilder, state: StreamState, account: Account): String? {
        val payload = event.toString().trimEnd('\n')
        event.setLength(0)
        if (payload.isEmpty() || payload == "[DONE]") return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        json.opt("error")?.let { throw AiError(humanize(200, payload, account)) }
        return state.accept(json)
    }

    private fun system(hasTools: Boolean, context: String): String = buildString {
        append("You are the assistant built into the user's phone home screen. ")
        append("Answer in at most three short sentences unless asked for more. No preamble, no markdown headings. ")
        if (hasTools) {
            append("Use a tool when the user asks you to perform an available action. ")
            append("Do not say an action succeeded until you have received its tool result. ")
        }
        if (context.isNotBlank()) {
            append("\n\nPersistent context:\n")
            append(context.trim())
        }
    }

    private fun contextOf(messages: List<ModelMessage>): String = messages
        .filterIsInstance<ModelMessage.Context>()
        .joinToString("\n\n") { it.text }

    private fun openAiBody(
        account: Account,
        messages: List<ModelMessage>,
        tools: List<ToolSpec>,
    ): JSONObject {
        val system = system(tools.isNotEmpty(), contextOf(messages))
        return JSONObject().apply {
            put("model", account.model)
            put("stream", true)
            put("max_tokens", 800)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { message ->
                    when (message) {
                        is ModelMessage.Context -> Unit
                        is ModelMessage.Text -> put(
                            JSONObject().put("role", message.role).put("content", message.text),
                        )
                        is ModelMessage.AssistantAction -> put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", if (message.text.isBlank()) JSONObject.NULL else message.text)
                            put("tool_calls", JSONArray().apply {
                                message.calls.forEach { call ->
                                    put(JSONObject().apply {
                                        put("id", call.id)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        })
                                    })
                                }
                            })
                        })
                        is ModelMessage.ToolOutputs -> message.results.forEach { result ->
                            put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", result.callId)
                                put("content", result.content)
                            })
                        }
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            })
                        })
                    }
                })
                put("tool_choice", "auto")
            }
        }
    }

    private fun anthropicBody(
        account: Account,
        messages: List<ModelMessage>,
        tools: List<ToolSpec>,
    ): JSONObject {
        val system = system(tools.isNotEmpty(), contextOf(messages))
        return JSONObject().apply {
            put("model", account.model)
            put("stream", true)
            put("max_tokens", 800)
            put("system", system)
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    when (message) {
                        is ModelMessage.Context -> Unit
                        is ModelMessage.Text -> put(
                            JSONObject().put("role", message.role).put("content", message.text),
                        )
                        is ModelMessage.AssistantAction -> put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", JSONArray().apply {
                                if (message.text.isNotBlank()) {
                                    put(JSONObject().put("type", "text").put("text", message.text))
                                }
                                message.calls.forEach { call ->
                                    put(JSONObject().apply {
                                        put("type", "tool_use")
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", parseObject(call.arguments) ?: JSONObject())
                                    })
                                }
                            })
                        })
                        is ModelMessage.ToolOutputs -> put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                message.results.forEach { result ->
                                    put(JSONObject().apply {
                                        put("type", "tool_result")
                                        put("tool_use_id", result.callId)
                                        put("content", result.content)
                                        if (!result.success) put("is_error", true)
                                    })
                                }
                            })
                        })
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    tools.forEach { tool ->
                        put(JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                })
            }
        }
    }

    private fun get(account: Account, path: String): String {
        val connection = open(account, path, "GET")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AiError(humanize(status, text, account))
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun open(account: Account, path: String, method: String): HttpURLConnection {
        val seat = if (account.provider == Provider.COPILOT) copilotSeat(account) else null
        val url = URL((seat?.api ?: account.base) + path)
        if (url.protocol != "https" && account.provider.signIn != SignIn.HOST) {
            throw AiError("${account.provider.label} must be reached over HTTPS.")
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            when {
                seat != null -> {
                    setRequestProperty("Authorization", "Bearer ${seat.token}")
                    setRequestProperty("Editor-Version", "Lumenberg/1.0")
                    setRequestProperty("Copilot-Integration-Id", "vscode-chat")
                }
                account.credential.isBlank() -> Unit
                account.provider.wire == Wire.ANTHROPIC -> {
                    setRequestProperty("x-api-key", account.credential)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                else -> setRequestProperty("Authorization", "Bearer ${account.credential}")
            }
            if (account.provider == Provider.OPENROUTER) {
                setRequestProperty("HTTP-Referer", "https://github.com/lumenberg")
                setRequestProperty("X-Title", "Lumenberg")
            }
        }
    }

    private data class Seat(val token: String, val expiresAt: Long, val api: String)

    @Volatile private var seat: Seat? = null

    private fun copilotSeat(account: Account): Seat {
        val now = System.currentTimeMillis() / 1000
        seat?.takeIf { it.expiresAt - 60 > now }?.let { return it }

        val connection = (URL(SEAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${account.credential}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Editor-Version", "Lumenberg/1.0")
        }
        val text = try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status == 401 || status == 403 ->
                    throw AiError("Your GitHub sign-in no longer has a Copilot seat. Sign in again.")
                status !in 200..299 -> throw AiError(humanize(status, body, account))
            }
            body
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(text)
        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: throw AiError("GitHub did not return a Copilot token for this account.")
        return Seat(
            token = token,
            expiresAt = json.optLong("expires_at", now + 300),
            api = json.optJSONObject("endpoints")?.optString("api")?.takeIf { it.isNotBlank() }
                ?: Provider.COPILOT.base,
        ).also { seat = it }
    }

    private fun humanize(status: Int, detail: String, account: Account): String {
        val provider = account.provider.label
        val hint = runCatching {
            val error = JSONObject(detail).opt("error")
            when (error) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> null
            }
        }.getOrNull().orEmpty()
        return when (status) {
            401, 403 -> "$provider did not accept your sign-in. Open Settings and connect again."
            404 -> "$provider has no model called \"${account.model}\". Pick another one in Settings."
            402 -> "Your $provider account is out of credit."
            429 -> "$provider is asking you to slow down. Try again in a moment."
            in 500..599 -> "$provider is having trouble right now. Try again shortly."
            else -> hint.ifBlank { "$provider returned an unexpected error ($status)." }
        }
    }

    private companion object {
        const val SEAT_URL = "https://api.github.com/copilot_internal/v2/token"
        val NOT_CHAT = listOf("embed", "whisper", "tts", "dall-e", "moderation", "rerank", "audio", "image")
    }
}

internal class StreamState(private val wire: Wire) {
    private data class DraftCall(
        var id: String = "",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
        var initialArguments: String? = null,
    )

    private val text = StringBuilder()
    private val calls = linkedMapOf<Int, DraftCall>()

    fun accept(event: JSONObject): String? = when (wire) {
        Wire.OPENAI -> acceptOpenAi(event)
        Wire.ANTHROPIC -> acceptAnthropic(event)
    }

    fun finish(): ModelReply = ModelReply(
        text = text.toString().trim(),
        calls = calls.entries.sortedBy { it.key }.map { (index, draft) ->
            ToolCall(
                id = draft.id.ifBlank { "call-$index" },
                name = draft.name.toString().trim(),
                arguments = draft.arguments.toString().ifBlank { draft.initialArguments ?: "{}" },
            )
        }.filter { it.name.isNotBlank() },
    )

    private fun acceptOpenAi(event: JSONObject): String? {
        val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val part = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        collectOpenAiCalls(part.optJSONArray("tool_calls"))
        val token = part.optString("content").takeIf(String::isNotEmpty)
        token?.let(text::append)
        return token
    }

    private fun collectOpenAiCalls(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val piece = array.optJSONObject(i) ?: continue
            val index = piece.optInt("index", i)
            val draft = calls.getOrPut(index) { DraftCall() }
            piece.optString("id").takeIf(String::isNotBlank)?.let { draft.id = it }
            val function = piece.optJSONObject("function") ?: continue
            function.optString("name").takeIf(String::isNotEmpty)?.let(draft.name::append)
            function.optString("arguments").takeIf(String::isNotEmpty)?.let(draft.arguments::append)
        }
    }

    private fun acceptAnthropic(event: JSONObject): String? {
        return when (event.optString("type")) {
            "content_block_start" -> {
                val index = event.optInt("index", calls.size)
                val block = event.optJSONObject("content_block") ?: return null
                if (block.optString("type") == "tool_use") {
                    val draft = calls.getOrPut(index) { DraftCall() }
                    draft.id = block.optString("id")
                    block.optString("name").takeIf(String::isNotEmpty)?.let(draft.name::append)
                    block.optJSONObject("input")?.takeIf { it.length() > 0 }
                        ?.let { draft.initialArguments = it.toString() }
                }
                null
            }
            "content_block_delta" -> {
                val index = event.optInt("index", 0)
                val delta = event.optJSONObject("delta") ?: return null
                when (delta.optString("type")) {
                    "text_delta" -> delta.optString("text").takeIf(String::isNotEmpty)?.also(text::append)
                    "input_json_delta" -> {
                        val draft = calls.getOrPut(index) { DraftCall() }
                        delta.optString("partial_json").takeIf(String::isNotEmpty)?.let(draft.arguments::append)
                        null
                    }
                    else -> null
                }
            }
            else -> {
                val token = event.optJSONObject("delta")?.optString("text")?.takeIf(String::isNotEmpty)
                token?.let(text::append)
                token
            }
        }
    }
}

private fun parseObject(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

class AiError(message: String) : IOException(message)
