package dev.lumenberg.ai

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

/** What the assistant decided to do, once the stream is finished. */
data class Reply(val text: String, val open: String? = null)

/**
 * Talks to whichever provider the user signed into. Two wire formats, one parser:
 * every provider streams JSON over SSE, and the token is at either
 * `choices[0].delta.content` (OpenAI-shaped) or `delta.text` (Anthropic).
 */
class AiClient {

    suspend fun models(account: Account): List<String> = withContext(Dispatchers.IO) {
        val data = JSONObject(get(account, "/models")).optJSONArray("data") ?: JSONArray()
        (0 until data.length())
            .mapNotNull { data.optJSONObject(it)?.takeIf { o -> !o.isNull("id") }?.optString("id")?.takeIf(String::isNotBlank) }
            // Google lists ids as "models/gemini-…" but its chat route wants the bare name.
            .map { it.removePrefix("models/") }
            .distinct()
            .sorted()
    }

    /**
     * Picks a model a non-technical user would have picked. Among the models matching a
     * provider's hint, the highest-sorting id wins, because vendors put the generation in
     * the name and a plain "first match" hands everyone the oldest model in the catalogue.
     */
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

    /**
     * Streams a reply, calling [onDelta] on the IO thread for every token.
     * Cancelling the calling coroutine aborts the request.
     */
    suspend fun send(
        account: Account,
        history: List<Turn>,
        apps: List<String>,
        onDelta: suspend (String) -> Unit,
    ): Reply = withContext(Dispatchers.IO) {
        require(account.ready) { "No AI account is set up yet." }
        val turns = alternating(history)
        val body = when (account.provider.wire) {
            Wire.OPENAI -> openAiBody(account, turns, apps)
            Wire.ANTHROPIC -> anthropicBody(account, turns, apps)
        }
        val path = if (account.provider.wire == Wire.ANTHROPIC) "/messages" else "/chat/completions"
        val connection = open(account, path, "POST")

        // A blocking socket read does not notice coroutine cancellation, so cancellation
        // has to reach in and close the connection underneath it.
        val closer = coroutineContext[Job]?.invokeOnCompletion {
            if (it != null) runCatching { connection.disconnect() }
        }

        val text = StringBuilder()
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
                        consume(event, text, account)?.let { onDelta(it) }
                        break
                    }
                    when {
                        // A blank line ends an SSE event; its data lines are one document.
                        line.isEmpty() -> consume(event, text, account)?.let { onDelta(it) }
                        line.startsWith(":") -> Unit // keep-alive comment
                        line.startsWith("data:") ->
                            event.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                        else -> Unit // event:, id:, retry:
                    }
                }
            }
        } finally {
            closer?.dispose()
            connection.disconnect()
        }
        finish(text.toString())
    }

    /**
     * One request, one answer, no streaming. Used by the agent loop, where the reply is a
     * single small instruction rather than prose to watch arrive.
     *
     * The prefix is append-only across a run, which is what a provider's cache needs.
     * Anthropic's breakpoint goes on the last message rather than the system block: the
     * system prompt alone is a few hundred tokens, under the minimum that can be cached at
     * all, whereas the accumulated screens are what actually grow large enough to matter.
     * The OpenAI-shaped providers match prefixes themselves and need no marker.
     */
    suspend fun converse(account: Account, system: String, history: List<Turn>): String =
        withContext(Dispatchers.IO) {
            require(account.ready) { "No AI account is set up yet." }
            val turns = alternating(history)
            val anthropic = account.provider.wire == Wire.ANTHROPIC
            val body = JSONObject().apply {
                put("model", account.model)
                put("stream", false)
                // Enough that a real answer plus its JSON wrapper is never cut in half.
                put("max_tokens", 1200)
                if (anthropic) {
                    put("system", system)
                    put("messages", JSONArray().apply {
                        turns.forEachIndexed { index, turn ->
                            val content = if (index == turns.lastIndex) {
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", turn.text)
                                        .put("cache_control", JSONObject().put("type", "ephemeral")),
                                )
                            } else {
                                turn.text
                            }
                            put(JSONObject().put("role", turn.role).put("content", content))
                        }
                    })
                } else {
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        turns.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
                    })
                }
            }

            val path = if (anthropic) "/messages" else "/chat/completions"
            val connection = open(account, path, "POST")
            val text = try {
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw AiError(humanize(status, payload, account))
                payload
            } finally {
                connection.disconnect()
            }

            val json = JSONObject(text)
            val reply = if (anthropic) {
                json.optJSONArray("content")?.optJSONObject(0)?.let { if (it.isNull("text")) null else it.optString("text") }
            } else {
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.let { if (it.isNull("content")) null else it.optString("content") }
            }
            reply?.takeIf { it.isNotBlank() } ?: throw AiError("The assistant returned nothing.")
        }

    /** Drains one complete SSE event into [text], returning the token it carried. */
    private fun consume(event: StringBuilder, text: StringBuilder, account: Account): String? {
        val payload = event.toString().trimEnd('\n')
        event.setLength(0)
        if (payload.isEmpty() || payload == "[DONE]") return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        // Some providers report failures inside a 200 stream. Those must not look like silence.
        json.opt("error")?.let { throw AiError(humanize(200, payload, account)) }
        val token = tokenOf(json) ?: return null
        text.append(token)
        return token
    }

    internal fun tokenOf(event: JSONObject): String? {
        event.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            // Deliberately not reasoning_content: a reasoning model's scratchpad is not
            // an answer, and a home screen is the wrong place to read one.
            delta?.text("content")?.let { return it }
        }
        return event.optJSONObject("delta")?.text("text")
    }

    /**
     * `optString` answers with the four characters "null" when a field is JSON null,
     * which is how a reasoning model's empty content chunks turn into nullnullnull
     * on screen. This returns nothing for nothing.
     */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    /**
     * Anthropic rejects a history that does not strictly alternate starting from the user,
     * and a reply that was only an app launch or a cancelled stream leaves a gap.
     * Repairing here fixes it for every caller at once.
     */
    internal fun alternating(history: List<Turn>): List<Turn> {
        val out = mutableListOf<Turn>()
        history.forEach { turn ->
            when {
                out.isEmpty() && turn.role != "user" -> Unit
                out.isNotEmpty() && out.last().role == turn.role ->
                    out[out.lastIndex] = out.last().let { it.copy(text = it.text + "\n\n" + turn.text) }
                else -> out += turn
            }
        }
        return out
    }

    // ponytail: a launch marker in the text beats tool-call plumbing in two wire formats.
    // Swap for native tool calls only if a model starts ignoring the instruction.
    //
    // The marker only counts when it is the entire reply. Matching it anywhere would let
    // a model launch an app mid-sentence, and would let anyone get an app launched by
    // asking the assistant to quote the marker back.
    internal fun finish(raw: String): Reply {
        val match = LAUNCH.matchEntire(raw.trim())
            ?: return Reply(raw.trim())
        return Reply(text = "", open = match.groupValues[1].trim().takeIf(String::isNotEmpty))
    }

    private fun system(apps: List<String>): String = buildString {
        append("You are the assistant built into the user's phone home screen. ")
        append("Answer in at most three short sentences unless asked for more. No preamble, no markdown headings. ")
        if (apps.isNotEmpty()) {
            append("Installed apps: ")
            append(apps.joinToString(", "))
            append(". If the user is asking to open one of them, reply with exactly ")
            append("[[open:Exact App Name]] and nothing else.")
        }
    }

    private fun openAiBody(account: Account, history: List<Turn>, apps: List<String>) = JSONObject().apply {
        put("model", account.model)
        put("stream", true)
        put("max_tokens", 800)
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system(apps)))
            history.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
        })
    }

    private fun anthropicBody(account: Account, history: List<Turn>, apps: List<String>) = JSONObject().apply {
        put("model", account.model)
        put("stream", true)
        put("max_tokens", 800)
        put("system", system(apps))
        put("messages", JSONArray().apply {
            history.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
        })
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
                    // Copilot refuses requests that do not say what is asking.
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

    /**
     * A GitHub OAuth token is durable but not what Copilot accepts; it is traded for a
     * short-lived seat token that expires in minutes, so this caches one and renews early.
     */
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

    /** Users do not know what a 429 is. */
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

        /** Ids that answer a /models call but cannot hold a conversation. */
        val NOT_CHAT = listOf("embed", "whisper", "tts", "dall-e", "moderation", "rerank", "audio", "image")
    }
}

class AiError(message: String) : IOException(message)

private val LAUNCH = Regex("""\[\[\s*open\s*:\s*([^\]]+?)\s*]]""", RegexOption.IGNORE_CASE)
