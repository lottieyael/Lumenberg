package dev.lumenberg.core

import java.util.Locale

sealed interface LocalCommand {
    data class Open(val app: String) : LocalCommand
    data class Media(val action: String) : LocalCommand
    data class Ambiguous(val app: String) : LocalCommand
}

/** Only exact, unambiguous commands run locally; natural-language requests fall through. */
fun localCommand(text: String, apps: List<String>): LocalCommand? {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    val wanted = Regex("^(?:open|launch) (.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)?.groupValues?.get(1) ?: clean
    val matches = apps.filter { it.equals(wanted, ignoreCase = true) }
    if (matches.size == 1) return LocalCommand.Open(matches.single())
    if (matches.size > 1) return LocalCommand.Ambiguous(wanted)
    val action = when (clean.lowercase(Locale.ROOT)) {
        "play", "play music", "resume", "resume music", "resume playback" -> "play"
        "pause", "pause music", "pause playback" -> "pause"
        "next track", "next song", "skip track", "skip song" -> "next"
        "previous track", "previous song" -> "previous"
        else -> return null
    }
    return LocalCommand.Media(action)
}
