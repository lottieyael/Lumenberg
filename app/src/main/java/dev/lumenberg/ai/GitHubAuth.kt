package dev.lumenberg.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What the user has to do, and where, to finish signing in. */
data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val interval: Long,
)

/**
 * GitHub's OAuth device flow. It is the right shape for a launcher: it needs no client
 * secret, which an app on a phone could not keep anyway, and the user can finish on
 * whichever device is comfortable.
 *
 * GitHub documents third-party applications making Copilot requests on behalf of a user
 * who authorised them, which is why this exists and the equivalent for other vendors
 * does not.
 */
class GitHubAuth {

    val configured: Boolean get() = CLIENT_ID.isNotBlank()

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        val json = form(
            "https://github.com/login/device/code",
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPE),
        )
        DeviceCode(
            userCode = json.optString("user_code"),
            verificationUri = json.optString("verification_uri")
                .ifBlank { "https://github.com/login/device" },
            deviceCode = json.optString("device_code"),
            interval = json.optLong("interval", 5L).coerceAtLeast(1L),
        ).also {
            if (it.deviceCode.isBlank()) throw AiError("GitHub would not start the sign-in.")
        }
    }

    /**
     * Waits for the user to approve, honouring GitHub's backoff. Cancelling the caller
     * stops the polling.
     */
    suspend fun awaitToken(code: DeviceCode): String = withContext(Dispatchers.IO) {
        var wait = code.interval
        while (true) {
            delay(wait * 1000)
            val json = form(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
            )
            json.optString("access_token").takeIf { it.isNotBlank() }?.let { return@withContext it }
            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> wait += 5
                "expired_token" -> throw AiError("That code expired. Start the sign-in again.")
                "access_denied" -> throw AiError("The sign-in was declined on GitHub.")
                else -> throw AiError("GitHub could not complete the sign-in.")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun form(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AiError("GitHub returned an error ($status).")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * The client id of a GitHub OAuth App registered for this build. A device-flow
         * client id carries no secret, so shipping it is safe; borrowing another product's
         * is not, which is why there is no default here.
         *
         * Register one at https://github.com/settings/developers with device flow enabled.
         */
        const val CLIENT_ID = ""
        private const val SCOPE = "read:user"
    }
}
