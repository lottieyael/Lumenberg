package dev.lumenberg.memory

import org.json.JSONObject
import java.io.File

enum class Verbosity {
    SHORT,
    BALANCED,
    DETAILED,
}

enum class Warmth {
    COOL,
    NEUTRAL,
    WARM,
}

data class AgentProfile(
    val name: String = "Lumenberg",
    val verbosity: Verbosity = Verbosity.BALANCED,
    val warmth: Warmth = Warmth.NEUTRAL,
    val hasAvatar: Boolean = false,
) {
    fun prompt(): String = buildString {
        append("Your name is $name. ")
        append(
            when (verbosity) {
                Verbosity.SHORT -> "Keep replies concise unless the user asks for detail. "
                Verbosity.BALANCED -> "Use enough detail to be useful without overexplaining. "
                Verbosity.DETAILED -> "Prefer thorough replies when the task benefits from detail. "
            },
        )
        append(
            when (warmth) {
                Warmth.COOL -> "Use a restrained, matter-of-fact tone."
                Warmth.NEUTRAL -> "Use a natural, neutral tone."
                Warmth.WARM -> "Use a friendly, warm tone without being gushy."
            },
        )
    }
}

class AgentProfileStore(private val root: File) {
    private val file = File(root, "profile.json")
    val avatarFile = File(root, "avatar")

    init {
        root.mkdirs()
    }

    fun load(): AgentProfile = runCatching {
        val json = JSONObject(file.readText())
        AgentProfile(
            name = json.optString("name").ifBlank { "Lumenberg" },
            verbosity = enumValueOrDefault(json.optString("verbosity"), Verbosity.BALANCED),
            warmth = enumValueOrDefault(json.optString("warmth"), Warmth.NEUTRAL),
            hasAvatar = avatarFile.exists(),
        )
    }.getOrDefault(AgentProfile(hasAvatar = avatarFile.exists()))

    fun save(profile: AgentProfile) {
        root.mkdirs()
        file.writeText(
            JSONObject()
                .put("name", profile.name.trim().ifBlank { "Lumenberg" })
                .put("verbosity", profile.verbosity.name)
                .put("warmth", profile.warmth.name)
                .toString(),
        )
    }

    fun removeAvatar() {
        avatarFile.delete()
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback
