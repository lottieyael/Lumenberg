package dev.lumenberg.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth with PKCE against OpenRouter: the user approves in their browser and comes back
 * signed in. No key is ever typed, shown, or pasted.
 *
 * The verifier outlives the browser trip because Android may kill us while it is open.
 */
class OpenRouterAuth(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("oauth", Context.MODE_PRIVATE)

    fun authorizeIntent(): Intent {
        val verifier = random()
        val state = random()
        prefs.edit().putString("verifier", verifier).putString("state", state).apply()
        val url = Uri.parse("https://openrouter.ai/auth").buildUpon()
            .appendQueryParameter("callback_url", "$REDIRECT?state=$state")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The redirect is a BROWSABLE deep link, so any web page can fire it. Only a callback
     * carrying the state we minted for this attempt is treated as ours.
     */
    fun codeIn(uri: Uri?): String? {
        if (uri == null || uri.scheme != "lumenberg" || uri.host != "auth") return null
        val expected = prefs.getString("state", null) ?: return null
        if (uri.getQueryParameter("state") != expected) return null
        return uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
    }

    /** Trades the one-time code for a durable key. */
    suspend fun exchange(code: String): String = withContext(Dispatchers.IO) {
        val verifier = prefs.getString("verifier", null)
            ?: throw AiError("That sign-in link expired. Try connecting again.")
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", verifier)
            .put("code_challenge_method", "S256")

        val connection = (URL("https://openrouter.ai/api/v1/auth/keys").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val text = try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw AiError("OpenRouter could not complete the sign-in. Try again.")
            payload
        } finally {
            connection.disconnect()
            // The code is single-use either way; a stale verifier would only break the retry.
            prefs.edit().remove("verifier").remove("state").apply()
        }

        JSONObject(text).optString("key").takeIf { it.isNotBlank() }
            ?: throw AiError("OpenRouter did not return a key.")
    }

    private fun random(): String {
        val bytes = ByteArray(48).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, URL_SAFE)
    }

    private fun challenge(verifier: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            URL_SAFE,
        )

    private companion object {
        const val REDIRECT = "lumenberg://auth"
        const val URL_SAFE = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    }
}
