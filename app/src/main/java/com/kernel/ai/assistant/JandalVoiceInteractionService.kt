package com.kernel.ai.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

private const val TAG = "KernelAI"

/**
 * Registers Jandal as a system-level Default Digital Assistant.
 *
 * Declaring this service in the manifest with the BIND_VOICE_INTERACTION permission makes Jandal
 * appear in Android Settings → Default Apps → Digital Assistant, allowing the user to select it.
 * Once selected:
 * - Long-press Home (or Samsung Side-key equivalent) activates [JandalVoiceInteractionSession]
 *   via [JandalVoiceInteractionSessionService]
 * - The OS grants a privileged background microphone path that persists through battery savers
 * - [createAlwaysOnHotwordDetector] becomes available for the wake word service (#985)
 *
 * Session creation is delegated to [JandalVoiceInteractionSessionService], which is registered
 * via the android:sessionService attribute in res/xml/interaction_service_config.xml.
 */
class JandalVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "JandalVoiceInteractionService: ready")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "JandalVoiceInteractionService: shutdown")
    }
}
