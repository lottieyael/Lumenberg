package dev.lumenberg.core

/**
 * Ranks app labels against what the user has typed so far.
 *
 * Tiers are wide apart so a better kind of match always beats a more-used app,
 * and usage only ever breaks ties inside one tier.
 */
object SearchRanker {
    const val NO_MATCH = Long.MIN_VALUE

    private const val EXACT = 6_000_000L
    private const val PREFIX = 5_000_000L
    private const val WORD_PREFIX = 4_000_000L
    private const val ACRONYM = 3_000_000L
    private const val SUBSTRING = 2_000_000L
    private const val SUBSEQUENCE = 1_000_000L
    private const val USAGE_CEILING = 999_999L

    fun score(query: String, label: String, launches: Long): Long {
        val q = query.trim().lowercase()
        val text = label.lowercase()
        if (q.isEmpty()) return launches

        val words = text.split(' ', '-', '_', '.').filter(String::isNotEmpty)
        val tier = when {
            text == q -> EXACT
            text.startsWith(q) -> PREFIX
            words.any { it.startsWith(q) } -> WORD_PREFIX
            q.length >= 2 && acronym(words).startsWith(q) -> ACRONYM
            text.contains(q) -> SUBSTRING
            subsequence(text, q) -> SUBSEQUENCE
            else -> return NO_MATCH
        }
        // Shorter labels win inside a tier: "Maps" over "Maps Go" for "map".
        val brevity = (200 - text.length).coerceIn(0, 200) * 1_000L
        return tier + brevity + launches.coerceIn(0, USAGE_CEILING)
    }

    private fun acronym(words: List<String>): String =
        words.map { it.first() }.joinToString("")

    /** "spty" matches "spotify"; every query letter appears in order. */
    private fun subsequence(text: String, query: String): Boolean {
        var i = 0
        for (c in text) {
            if (c == query[i] && ++i == query.length) return true
        }
        return false
    }
}
