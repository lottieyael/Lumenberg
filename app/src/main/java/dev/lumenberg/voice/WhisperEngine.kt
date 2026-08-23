package dev.lumenberg.voice

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperEngine(
    context: Context,
    private val models: WhisperModelStore = WhisperModelStore(context),
) {
    private val context = context.applicationContext

    suspend fun transcribe(audio: File): String = withContext(Dispatchers.IO) {
        check(models.ready) { "The speech model is not installed yet." }
        val model = Whisper.loadModel(context, models.model.absolutePath)
        try {
            Whisper.transcribe(model, audio.absolutePath, WhisperConfig()).text.trim()
        } finally {
            Whisper.releaseModel(model)
        }
    }
}
