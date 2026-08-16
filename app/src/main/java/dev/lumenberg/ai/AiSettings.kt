package dev.lumenberg.ai

import android.content.Context

data class AiSettings(
    val endpoint: String,
    val model: String,
    val apiKey: String,
) {
    val configured: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank()
}

class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        endpoint = prefs.getString("endpoint", "") ?: "",
        model = prefs.getString("model", "") ?: "",
        apiKey = prefs.getString("api_key", "") ?: "",
    )

    fun save(settings: AiSettings) {
        prefs.edit()
            .putString("endpoint", settings.endpoint.trim())
            .putString("model", settings.model.trim())
            .putString("api_key", settings.apiKey.trim())
            .apply()
    }
}
