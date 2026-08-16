package dev.lumenberg.core

object SearchRanker {
    data class Candidate(
        val label: String,
        val launchScore: Long,
    )

    fun score(query: String, candidate: Candidate): Long {
        val q = query.trim().lowercase()
        val label = candidate.label.lowercase()
        if (q.isEmpty()) return candidate.launchScore

        val textScore = when {
            label == q -> 1_000_000L
            label.startsWith(q) -> 500_000L
            label.split(Regex("\\s+")).any { it.startsWith(q) } -> 250_000L
            label.contains(q) -> 100_000L
            else -> Long.MIN_VALUE
        }
        if (textScore == Long.MIN_VALUE) return textScore
        return textScore + candidate.launchScore.coerceAtMost(99_999L)
    }
}
