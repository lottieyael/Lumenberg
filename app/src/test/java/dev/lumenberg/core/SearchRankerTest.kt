package dev.lumenberg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    private fun best(query: String, vararg labels: String): String =
        labels.map { it to SearchRanker.score(query, it, 0) }
            .filter { it.second != SearchRanker.NO_MATCH }
            .maxByOrNull { it.second }!!
            .first

    @Test
    fun `a better kind of match beats a heavily used app`() {
        val prefix = SearchRanker.score("cal", "Calendar", launches = 0)
        val substring = SearchRanker.score("cal", "My Calendar Tool", launches = 999_999)
        assertTrue(prefix > substring)
    }

    @Test
    fun `initials find an app`() {
        assertEquals("Google Maps", best("gm", "Google Maps", "Files", "Settings"))
    }

    @Test
    fun `a real prefix beats initials`() {
        assertEquals("Gmail", best("gm", "Gmail", "Google Maps"))
    }

    @Test
    fun `dropped letters still find an app`() {
        assertEquals("Spotify", best("spty", "Spotify", "Settings"))
    }

    @Test
    fun `the shorter of two equal matches wins`() {
        assertEquals("Maps", best("map", "Maps", "Maps Go Companion"))
    }

    @Test
    fun `an exact name outranks everything`() {
        assertEquals("Photos", best("photos", "Photos", "Photos Editor Pro"))
    }

    @Test
    fun `nonsense matches nothing`() {
        assertEquals(SearchRanker.NO_MATCH, SearchRanker.score("zzqx", "Calendar", 0))
    }

    @Test
    fun `an empty query ranks by use`() {
        assertTrue(SearchRanker.score("", "Rare", 1) < SearchRanker.score("", "Common", 90))
    }

    @Test
    fun `usage never lets one tier overtake another`() {
        val wordPrefix = SearchRanker.score("go", "Really Long Application Name Google", 0)
        val prefix = SearchRanker.score("go", "Go", Long.MAX_VALUE)
        assertTrue(prefix > wordPrefix)
    }

    @Test
    fun `queries are trimmed and case-insensitive`() {
        assertEquals(
            SearchRanker.score("cal", "Calendar", 0),
            SearchRanker.score("  CAL ", "Calendar", 0),
        )
    }
}
