package dev.lumenberg.widgets

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PanelTest {
    @Test
    fun `old layouts retain their full width and saved height`() {
        val panel = Panel.fromJson(JSONObject("{\"id\":7,\"h\":248}"))
        assertEquals(4, panel.span)
        assertEquals(248, panel.height)
        assertFalse(panel.square)
        assertEquals(360f, panel.widthDp(360f, 12f), 0.01f)
    }

    @Test
    fun `two half width widgets fit exactly with their gutter`() {
        val width = Panel(7, span = 2).widthDp(360f, 12f)
        assertEquals(360f, width * 2 + 12f, 0.01f)
    }

    @Test
    fun `mixed quarter spans account for all gutters`() {
        val a = Panel(7, span = 1).widthDp(411f, 12f)
        val b = Panel(8, span = 3).widthDp(411f, 12f)
        assertEquals(411f, a + b + 12f, 0.01f)
    }

    @Test
    fun `square shape survives a narrower window and persistence`() {
        val panel = Panel(7, span = 2, square = true)
        assertEquals(panel, Panel.fromJson(panel.toJson()))
        for (available in listOf(280f, 360f, 720f)) {
            val width = panel.widthDp(available, 12f)
            assertEquals(width, panel.heightDp(width), 0.01f)
        }
    }

    @Test
    fun `corrupt size values are bounded when loading`() {
        val panel = Panel.fromJson(JSONObject("{\"id\":7,\"h\":-100,\"span\":99}"))
        assertEquals(80, panel.height)
        assertEquals(4, panel.span)
    }
}
