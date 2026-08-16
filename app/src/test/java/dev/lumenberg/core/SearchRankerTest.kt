package dev.lumenberg.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {
    @Test
    fun prefixMatchBeatsSubstringEvenWhenSubstringHasMoreUsage() {
        val prefix = SearchRanker.score("cal", SearchRanker.Candidate("Calendar", 1))
        val substring = SearchRanker.score("cal", SearchRanker.Candidate("My Calendar Tool", 50_000))
        assertTrue(prefix > substring)
    }
}
