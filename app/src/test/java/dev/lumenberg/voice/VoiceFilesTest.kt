package dev.lumenberg.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class VoiceFilesTest {
    @Test
    fun `wav header describes 16 khz mono pcm`() {
        val file = Files.createTempFile("lumenberg", ".wav").toFile()
        try {
            RandomAccessFile(file, "rw").use { wav -> writeWavHeader(wav, 32_000L) }
            val bytes = file.readBytes()

            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals("fmt ", bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII))
            assertEquals(16_000, intLe(bytes, 24))
            assertEquals(32_000, intLe(bytes, 28))
            assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
            assertEquals(32_000, intLe(bytes, 40))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `digest hex keeps unsigned byte values`() {
        assertEquals("00ff1080", byteArrayOf(0, -1, 16, -128).hex())
    }

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
