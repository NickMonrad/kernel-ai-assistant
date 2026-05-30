package com.kernel.ai.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "KernelAI"

/**
 * Creates [JandalVoiceInteractionSession] instances on demand.
 *
 * Android's voice interaction architecture requires two separate services:
 * - [JandalVoiceInteractionService] — the main assistant service, handles hotword and session
 *   orchestration. Declared in the manifest with BIND_VOICE_INTERACTION.
 * - [JandalVoiceInteractionSessionService] (this class) — creates the per-activation session.
 *   Declared in interaction_service_config.xml as android:sessionService.
 */
@AndroidEntryPoint
class JandalVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "JandalVoiceInteractionSessionService: creating new session")
        return JandalVoiceInteractionSession(this)
    }
}
