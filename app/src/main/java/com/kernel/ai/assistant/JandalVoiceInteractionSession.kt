package com.kernel.ai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.kernel.ai.feature.widget.VoiceCommandActivity

private const val TAG = "KernelAI"

/**
 * Handles a single active assistant turn initiated by the Side key or equivalent gesture.
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

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
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
        // Unconditional resume: covers the case where VoiceCommandActivity finishes
        // without starting a voice session (e.g. RECORD_AUDIO denied, activity swiped
        // away) so WakeWordService is never left permanently paused.
        // When a real voice session ran, WakeWordService.resume() is idempotent.
        WakeWordService.resume(ctx)
    }
}
