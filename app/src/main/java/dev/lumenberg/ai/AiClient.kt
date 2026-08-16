package dev.lumenberg.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class AiClient {
    data class Message(val role: String, val content: String)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(
        settings: AiSettings,
        messages: List<Message>,
        onResult: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching { request(settings, messages) }
            main.post { onResult(result) }
        }
    }

    private fun request(settings: AiSettings, messages: List<Message>): String {
        require(settings.configured) { "AI provider is not configured." }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("stream", false)
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
        }

        val connection = (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
        }

        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (status !in 200..299) {
            error("Provider returned HTTP $status: ${text.take(500)}")
        }

        val json = JSONObject(text)
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: error("Provider response did not contain choices[0].message.content")
    }
}
