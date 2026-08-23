package dev.lumenberg.voice

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.max

class VoiceRecorder(context: Context) {
    private val output = File(context.cacheDir, "lumenberg-voice.wav")
    private var audio: AudioRecord? = null
    private var writer: RandomAccessFile? = null
    private var worker: Thread? = null
    @Volatile private var recording = false

    val active: Boolean get() = recording

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        check(!recording) { "Voice recording is already running." }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "This phone could not create a microphone buffer." }
        val bufferSize = max(minimum, SAMPLE_RATE * 2)
        val next = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(next.state == AudioRecord.STATE_INITIALIZED) {
            next.release()
            "This phone could not open the microphone."
        }

        output.parentFile?.mkdirs()
        val file = RandomAccessFile(output, "rw").apply {
            setLength(0)
            write(ByteArray(44))
        }
        audio = next
        writer = file
        recording = true
        next.startRecording()
        worker = thread(name = "lumenberg-voice", isDaemon = true) {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val count = next.read(buffer, 0, buffer.size)
                if (count > 0) {
                    synchronized(file) { file.write(buffer, 0, count) }
                }
            }
        }
    }

    fun stop(): File {
        check(recording) { "Voice recording is not running." }
        recording = false
        val current = audio
        runCatching { current?.stop() }
        worker?.join(2_000)
        worker = null
        current?.release()
        audio = null

        val file = writer ?: error("Voice recording has no output file.")
        synchronized(file) {
            val dataBytes = (file.length() - 44L).coerceAtLeast(0L)
            file.seek(0)
            writeWavHeader(file, dataBytes)
            file.fd.sync()
            file.close()
        }
        writer = null
        check(output.length() > 44L) { "No microphone audio was captured." }
        return output
    }

    fun cancel() {
        if (recording) {
            recording = false
            runCatching { audio?.stop() }
            worker?.join(500)
        }
        worker = null
        audio?.release()
        audio = null
        runCatching { writer?.close() }
        writer = null
        output.delete()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }
}

internal fun writeWavHeader(file: RandomAccessFile, dataBytes: Long) {
    val byteRate = VoiceRecorder.SAMPLE_RATE * VoiceRecorder.CHANNELS * VoiceRecorder.BITS_PER_SAMPLE / 8
    val blockAlign = VoiceRecorder.CHANNELS * VoiceRecorder.BITS_PER_SAMPLE / 8
    file.writeBytes("RIFF")
    file.writeIntLe((36L + dataBytes).toInt())
    file.writeBytes("WAVE")
    file.writeBytes("fmt ")
    file.writeIntLe(16)
    file.writeShortLe(1)
    file.writeShortLe(VoiceRecorder.CHANNELS)
    file.writeIntLe(VoiceRecorder.SAMPLE_RATE)
    file.writeIntLe(byteRate)
    file.writeShortLe(blockAlign)
    file.writeShortLe(VoiceRecorder.BITS_PER_SAMPLE)
    file.writeBytes("data")
    file.writeIntLe(dataBytes.toInt())
}

private fun RandomAccessFile.writeIntLe(value: Int) {
    write(value and 0xff)
    write(value shr 8 and 0xff)
    write(value shr 16 and 0xff)
    write(value shr 24 and 0xff)
}

private fun RandomAccessFile.writeShortLe(value: Int) {
    write(value and 0xff)
    write(value shr 8 and 0xff)
}
