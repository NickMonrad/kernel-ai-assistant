package com.kernel.ai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.kernel.ai.feature.widget.EXTRA_WAKE_OVERLAY
import com.kernel.ai.feature.widget.EXTRA_WAKE_TOKEN
import com.kernel.ai.feature.widget.VoiceCommandActivity

private const val TAG = "KernelAI"

/**
 * Handles a single active assistant turn initiated by the Side key, the Hey Jandal wake word (#2081),
 * or an equivalent gesture.
 *
 * The session immediately delegates to [VoiceCommandActivity], which owns the full voice
 * interaction UI (bloop cue, pulsing mic overlay, partial transcript, ActionsScreen routing).
 * Keeping the logic there avoids duplication and ensures widget and assistant triggers behave
 * identically.
 *
 * Created per-activation by [JandalVoiceInteractionSessionService.onNewSession].
 */
class JandalVoiceInteractionSession(
    private val ctx: Context,
) : VoiceInteractionSession(ctx) {

    /** True for a wake turn: the overlay shows a capture [WakeWordService] owns, so the capture is not paused. */
    private var wakeTurn = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(EXTRA_WAKE_OVERLAY, false) == true) {
            wakeTurn = true
            val token = args.getLong(EXTRA_WAKE_TOKEN)
            Log.d(TAG, "AssistantSession: onShow wake token=$token — overlay only, capture kept running")
            startAssistantActivity(
                Intent(ctx, VoiceCommandActivity::class.java).apply {
                    putExtra(EXTRA_WAKE_OVERLAY, true)
                    putExtra(EXTRA_WAKE_TOKEN, token)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            hide()
            return
        }
        Log.d(TAG, "AssistantSession: onShow flags=$showFlags — pausing wake word, delegating to VoiceCommandActivity")

        // Release the WakeWordService AudioRecord before Vosk tries to open the mic.
        // OnnxWakeWordDetector exits its 80ms audio loop within one frame; 300ms is ample.
        WakeWordService.pause(ctx)
        Handler(Looper.getMainLooper()).postDelayed({
            ctx.startActivity(
                Intent(ctx, VoiceCommandActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            hide()
        }, 300)
    }

    override fun onHide() {
        super.onHide()
        // A wake turn never paused WakeWordService, so there is nothing to resume.
        if (wakeTurn) {
            wakeTurn = false
            return
        }
        // Unconditional resume: covers the case where VoiceCommandActivity finishes
        // without starting a voice session (e.g. RECORD_AUDIO denied, activity swiped
        // away) so WakeWordService is never left permanently paused.
        // When a real voice session ran, WakeWordService.resume() is idempotent.
        WakeWordService.resume(ctx)
    }
}
