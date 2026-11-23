package com.kernel.ai.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.kernel.ai.feature.widget.EXTRA_WAKE_OVERLAY
import com.kernel.ai.feature.widget.EXTRA_WAKE_TOKEN

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
        active = this
        Log.d(TAG, "JandalVoiceInteractionService: ready")
    }

    override fun onShutdown() {
        super.onShutdown()
        if (active === this) active = null
        Log.d(TAG, "JandalVoiceInteractionService: shutdown")
    }

    companion object {
        /**
         * The live instance while Jandal holds the assistant role. The system readies only the active
         * voice interaction service, so this is null whenever another assistant is selected (#2081).
         */
        @Volatile
        private var active: JandalVoiceInteractionService? = null

        /** True while Jandal is the active assistant (#2081). */
        val isActive: Boolean get() = active != null

        /**
         * Opens the Hey Jandal overlay for live wake capture [token] through an assistant session, so it can
         * start with the screen off or locked (#2081). Returns false when Jandal is not the assistant, or when
         * the system refuses the session; the caller then falls back to the tap notification.
         */
        fun showWakeSession(token: Long): Boolean {
            val service = active ?: return false
            return try {
                service.showSession(
                    Bundle().apply {
                        putBoolean(EXTRA_WAKE_OVERLAY, true)
                        putLong(EXTRA_WAKE_TOKEN, token)
                    },
                    0,
                )
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "JandalVoiceInteractionService: showSession refused for wake token=$token", e)
                false
            }
        }
    }
}
