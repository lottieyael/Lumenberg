package dev.lumenberg.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.lumenberg.MainActivity

const val EXTRA_START_VOICE = "dev.lumenberg.START_VOICE"

class LumenbergVoiceService : VoiceInteractionService()

class LumenbergVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = LumenbergVoiceSession(this)
}

class LumenbergVoiceSession(
    private val service: VoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(service, MainActivity::class.java)
            .putExtra(EXTRA_START_VOICE, true)
        runCatching { startVoiceActivity(intent) }
        finish()
    }
}
