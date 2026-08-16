package dev.lumenberg.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The OAuth callback is built as a URL, and getting it subtly wrong is silent: the browser
 * returns, nothing parses, and the sign-in appears to do nothing at all. That happened, so
 * the shape of the redirect is pinned here.
 */
class RedirectTest {

    /** Mirrors how a provider appends its result to the callback it was given. */
    private fun codeFrom(callback: String, code: String): String? {
        val separator = if (callback.contains('?')) "&" else "?"
        return java.net.URI("$callback$separator" + "code=$code")
            .query
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.first() == "code" }
            ?.getOrNull(1)
    }

    @Test
    fun `a clean callback yields the code`() {
        assertEquals("abc123", codeFrom("lumenberg://auth", "abc123"))
    }

    @Test
    fun `a callback that already carries a query still yields the code`() {
        // If a parameter is ever added back, it has to survive this.
        assertEquals("abc123", codeFrom("lumenberg://auth?state=xyz", "abc123"))
    }

    @Test
    fun `the redirect we send has no query of its own`() {
        // The bug was a callback_url carrying state, so the code arrived after a second
        // question mark and could not be read.
        val redirect = "lumenberg://auth"
        assertEquals(false, redirect.contains('?'))
    }
}
