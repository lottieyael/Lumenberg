package dev.lumenberg.voice

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Downloading(val progress: Float) : VoiceState
    data object Recording : VoiceState
    data object Transcribing : VoiceState
    data class Failed(val message: String) : VoiceState
}

class VoiceController(context: Context) {
    private val models = WhisperModelStore(context)
    private val recorder = VoiceRecorder(context)
    private val whisper = WhisperEngine(context, models)

    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    val modelReady: Boolean get() = models.ready
    val recording: Boolean get() = state == VoiceState.Recording
    val working: Boolean get() = state is VoiceState.Downloading || state == VoiceState.Transcribing

    fun prepare(
        scope: CoroutineScope,
        onReady: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) {
        if (models.ready) {
            onReady()
            return
        }
        if (state is VoiceState.Downloading) return
        state = VoiceState.Downloading(0f)
        scope.launch {
            runCatching {
                models.download { progress -> state = VoiceState.Downloading(progress) }
            }.fold(
                onSuccess = {
                    state = VoiceState.Idle
                    onReady()
                },
                onFailure = {
                    val message = it.message ?: "Could not download the speech model."
                    state = VoiceState.Failed(message)
                    onFailure(message)
                },
            )
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggle(
        scope: CoroutineScope,
        onTranscript: (String) -> Unit,
        onFailure: (String) -> Unit = {},
    ) {
        when (state) {
            VoiceState.Recording -> finish(scope, onTranscript, onFailure)
            VoiceState.Idle, is VoiceState.Failed -> {
                if (!models.ready) {
                    prepare(scope, onFailure = onFailure)
                    return
                }
                runCatching { recorder.start() }.fold(
                    onSuccess = { state = VoiceState.Recording },
                    onFailure = {
                        val message = it.message ?: "Could not start voice input."
                        state = VoiceState.Failed(message)
                        onFailure(message)
                    },
                )
            }
            is VoiceState.Downloading, VoiceState.Transcribing -> Unit
        }
    }

    fun dismissError() {
        if (state is VoiceState.Failed) state = VoiceState.Idle
    }

    fun cancel() {
        recorder.cancel()
        if (state == VoiceState.Recording) state = VoiceState.Idle
    }

    private fun finish(
        scope: CoroutineScope,
        onTranscript: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val audio = runCatching { recorder.stop() }.getOrElse {
            val message = it.message ?: "Could not finish voice input."
            state = VoiceState.Failed(message)
            onFailure(message)
            return
        }
        state = VoiceState.Transcribing
        scope.launch {
            runCatching { whisper.transcribe(audio) }.fold(
                onSuccess = { text ->
                    if (text.isBlank()) {
                        val message = "I could not hear any speech in that recording."
                        state = VoiceState.Failed(message)
                        onFailure(message)
                    } else {
                        state = VoiceState.Idle
                        onTranscript(text)
                    }
                },
                onFailure = {
                    val message = it.message ?: "Whisper could not transcribe that recording."
                    state = VoiceState.Failed(message)
                    onFailure(message)
                },
            )
        }
    }
}
