package dev.lumenberg.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

    fun load(): Account {
        val stored = prefs.getString("credential", null)
        val credential = stored?.let(::open)
        unreadable = stored != null && credential == null
        return Account(
            provider = Provider.of(prefs.getString("provider", null)),
            credential = credential.orEmpty(),
            model = prefs.getString("model", "").orEmpty(),
            host = prefs.getString("host", "").orEmpty(),
        )
    }

    /**
     * Throws rather than storing nothing: a Keystore hiccup during an unrelated save
     * (changing model, say) must never quietly erase the user's credential.
     */
    fun save(account: Account) {
        val plain = account.credential.trim()
        val sealed = if (plain.isEmpty()) null else seal(plain)
            ?: throw AiError("This device would not store that key securely. Try again.")
        val edit = prefs.edit()
            .putString("provider", account.provider.id)
            .putString("model", account.model.trim())
            .putString("host", normalizeHost(account.host))
        if (sealed != null) edit.putString("credential", sealed) else edit.remove("credential")
        edit.apply()
        unreadable = false
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
