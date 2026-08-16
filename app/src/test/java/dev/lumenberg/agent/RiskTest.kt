package dev.lumenberg.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides which presses the user is asked about first. It is a word list,
 * so these tests are about which way it fails, not about it being complete.
 */
class RiskTest {

    @Test
    fun `the obvious ones are caught`() {
        listOf("Send", "Delete", "Buy now", "Place order", "Pay", "Post").forEach {
            assertTrue(it, Risk.consequential(it))
        }
    }

    @Test
    fun `a bare Confirm is caught`() {
        // Missed once: the list held "confirm order" and nothing matched plain "Confirm".
        assertTrue(Risk.consequential("Confirm"))
    }

    @Test
    fun `the quiet agreements are caught too`() {
        listOf("OK", "Yes", "Continue", "Allow", "Accept").forEach {
            assertTrue(it, Risk.consequential(it))
        }
    }

    @Test
    fun `an unlabelled button is treated as worth asking about`() {
        // An icon with no description is the case the list can say least about, so it asks
        // rather than pressing quietly.
        assertTrue(Risk.consequential(""))
        assertTrue(Risk.consequential("   "))
    }

    @Test
    fun `ordinary navigation is not flagged`() {
        listOf("Back", "Search", "Settings", "Directions").forEach {
            assertFalse(it, Risk.consequential(it))
        }
    }

    @Test
    fun `a word inside another word does not count`() {
        assertFalse(Risk.consequential("Resend history"))
        assertFalse(Risk.consequential("Okra"))
    }

    @Test
    fun `an unlabelled control is described without an empty quote`() {
        assertTrue(Risk.describe("").contains("this button"))
        assertTrue(Risk.describe("Send").contains("\"Send\""))
    }
}
