package com.kernel.ai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Starts the turn once the wake mic is released (#2109). Not cancelled on destroy: the turn must still start. */
    private val releaseScope = CoroutineScope(Dispatchers.Main.immediate)

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

        // The turn starts only after the wake detector has released the mic (#2109). Its stop joins the detector
        // thread, so a fixed delay is not enough. A release that times out still starts the turn.
        val release = WakeWordService.pause(ctx)
        releaseScope.launch {
            if (!awaitMicRelease(release)) {
                Log.w(TAG, "AssistantSession: wake mic not released within ${MIC_RELEASE_TIMEOUT_MS} ms — starting anyway")
            }
            ctx.startActivity(
                Intent(ctx, VoiceCommandActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            hide()
        }
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

/** Longest wait for the wake detector to release the mic before a voice turn starts (#2109). */
internal const val MIC_RELEASE_TIMEOUT_MS = 2_500L

/**
 * Waits until [release] completes, for at most [timeoutMs]. Returns false on timeout. A null [release] means the
 * service was not running, so there is nothing to wait for (#2109).
 */
internal suspend fun awaitMicRelease(release: Job?, timeoutMs: Long = MIC_RELEASE_TIMEOUT_MS): Boolean {
    if (release == null) return true
    return withTimeoutOrNull(timeoutMs) { release.join() } != null
}
