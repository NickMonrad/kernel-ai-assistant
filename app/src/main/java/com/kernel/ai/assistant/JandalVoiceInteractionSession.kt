package com.kernel.ai.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.feature.widget.VoiceCommandService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val TAG = "KernelAI"

/**
 * Handles a single active assistant turn initiated by the user (hold-Home, Side-key, etc.).
 *
 * The session is intentionally headless — no system assistant chrome is shown. The
 * interaction is fully voice-based:
 * 1. STT capture starts immediately via [VoiceInputController] on [VoiceCaptureMode.AlertCommand]
 * 2. The start-listening audio cue plays when the recogniser fires [VoiceInputEvent.ListeningStarted]
 * 3. The first full transcript is routed to [VoiceCommandService] — the same execution seam
 *    used by the homescreen widget and the future wake word service (#985)
 * 4. [VoiceCommandService] handles QIR routing → skill execution → TTS confirmation
 *
 * Created per-activation by [JandalVoiceInteractionSessionService.onNewSession].
 *
 * Note: [VoiceInputController] is a singleton. If Quick Actions or Chat voice is active
 * simultaneously, [startListening] surfaces the conflict via [VoiceInputStartResult.Unavailable].
 * This is acceptable for the initial implementation.
 */
class JandalVoiceInteractionSession(
    private val ctx: Context,
    private val voiceInputController: VoiceInputController,
    private val cuePlayer: StartListeningCuePlayer,
) : VoiceInteractionSession(ctx) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * True only after [VoiceInputController.startListening] succeeds. Guards the
     * [VoiceInputController.stopListening] call in [onHide] so we never interrupt an
     * unrelated consumer when our own session never acquired the mic.
     */
    private var listeningStarted = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "AssistantSession: onShow flags=$showFlags")

        sessionScope.launch {
            val startResult = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)
            if (startResult !is VoiceInputStartResult.Started) {
                Log.w(TAG, "AssistantSession: STT unavailable — $startResult")
                hide()
                return@launch
            }
            listeningStarted = true

            val transcript = try {
                voiceInputController.events
                    .onEach { event ->
                        // Mirror ActionsViewModel: play cue when mic is truly open, not before.
                        if (event is VoiceInputEvent.ListeningStarted) cuePlayer.playCue()
                    }
                    .filterIsInstance<VoiceInputEvent.Transcript>()
                    .first()
                    .text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AssistantSession: transcript collection failed", e)
                hide()
                return@launch
            }

            Log.d(TAG, "AssistantSession: routing transcript=\"$transcript\"")
            routeTranscript(transcript)
            hide()
        }
    }

    override fun onHide() {
        super.onHide()
        if (listeningStarted) voiceInputController.stopListening()
        sessionScope.cancel()
    }

    private fun routeTranscript(transcript: String) {
        val intent = Intent(ctx, VoiceCommandService::class.java).apply {
            action = VoiceCommandService.ACTION_EXECUTE_COMMAND
            putExtra(VoiceCommandService.EXTRA_TRANSCRIPT, transcript)
            putExtra(VoiceCommandService.EXTRA_INPUT_MODE, "voice")
        }
        ctx.startService(intent)
    }
}
