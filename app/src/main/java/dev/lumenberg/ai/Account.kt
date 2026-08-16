package dev.lumenberg.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The one AI account Lumenberg talks to. */
data class Account(
    val provider: Provider = Provider.OPENROUTER,
    val credential: String = "",
    val model: String = "",
    /** Only for [SignIn.HOST]: the machine the user pointed us at. */
    val host: String = "",
) {
    val base: String
        get() = if (provider.signIn == SignIn.HOST) host.trimEnd('/') else provider.base

    val ready: Boolean
        get() = model.isNotBlank() && when (provider.signIn) {
            SignIn.HOST -> host.isNotBlank()
            else -> credential.isNotBlank()
        }
}

/**
 * Stores the account. The credential is sealed with a hardware-backed AES key so it never
 * sits in plaintext, even if the preferences file is pulled off a rooted device.
 */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    /** True when a credential is on disk but could not be decrypted. */
    var unreadable: Boolean = false
        private set

    /**
     * Every account the user has connected, and which one is answering. More than one is
     * normal: a cheap model for quick questions and a strong one for real ones.
     */
    fun load(): List<Account> {
        migrate()
        val array = runCatching { JSONArray(prefs.getString("accounts", "[]")) }
            .getOrDefault(JSONArray())
        var lost = false
        val accounts = (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val sealed = row.optString("credential").takeIf { it.isNotBlank() }
            val credential = sealed?.let(::open)
            if (sealed != null && credential == null) lost = true
            Account(
                provider = Provider.of(row.optString("provider")),
                credential = credential.orEmpty(),
                model = row.optString("model"),
                host = row.optString("host"),
            )
        }
        unreadable = lost
        return accounts
    }

    fun active(): Int = prefs.getInt("active", 0)

    /**
     * Throws rather than storing nothing: a Keystore hiccup while switching models must
     * never quietly erase a credential.
     */
    fun save(accounts: List<Account>, active: Int) {
        val array = JSONArray()
        accounts.forEach { account ->
            val plain = account.credential.trim()
            val sealed = if (plain.isEmpty()) "" else seal(plain)
                ?: throw AiError("This device would not store that key securely. Try again.")
            array.put(
                JSONObject()
                    .put("provider", account.provider.id)
                    .put("credential", sealed)
                    .put("model", account.model.trim())
                    .put("host", normalizeHost(account.host)),
            )
        }
        prefs.edit()
            .putString("accounts", array.toString())
            .putInt("active", active.coerceIn(0, maxOf(accounts.lastIndex, 0)))
            .apply()
        unreadable = false
    }

    /** Carries a 0.2 single-account install into the list format, once. */
    private fun migrate() {
        if (prefs.contains("accounts") || !prefs.contains("provider")) return
        val row = JSONObject()
            .put("provider", prefs.getString("provider", "").orEmpty())
            .put("credential", prefs.getString("credential", "").orEmpty())
            .put("model", prefs.getString("model", "").orEmpty())
            .put("host", prefs.getString("host", "").orEmpty())
        prefs.edit()
            .putString("accounts", JSONArray().put(row).toString())
            .putInt("active", 0)
            .remove("provider").remove("credential").remove("model").remove("host")
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plain.toByteArray())
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "lumenberg.account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * "192.168.1.4" and "192.168.1.4:11434" both become a usable OpenAI-compatible base.
 * Only addresses that are plainly on the local network get plaintext by default; anything
 * that looks like it is out on the internet is not silently downgraded to HTTP.
 */
fun normalizeHost(input: String): String {
    val text = input.trim().trimEnd('/')
    if (text.isEmpty()) return ""
    val withScheme = when {
        text.startsWith("http://") || text.startsWith("https://") -> text
        isLocal(text.substringBefore(':')) -> "http://$text"
        else -> "https://$text"
    }
    val withPort = if (Regex(":\\d+").containsMatchIn(withScheme.substringAfter("://"))) {
        withScheme
    } else {
        "$withScheme:11434"
    }
    return if (withPort.endsWith("/v1")) withPort else "$withPort/v1"
}

private fun isLocal(hostname: String): Boolean = hostname == "localhost" ||
    hostname.endsWith(".local") ||
    !hostname.contains('.') ||
    hostname.startsWith("10.") ||
    hostname.startsWith("192.168.") ||
    Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(hostname)
