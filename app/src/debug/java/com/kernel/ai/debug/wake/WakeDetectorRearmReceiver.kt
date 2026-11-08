package com.kernel.ai.debug.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kernel.ai.assistant.WakeWordService

/**
 * Debug-only control endpoint (#1410 evidence retention).
 *
 * Forces the running [WakeWordService] through its own approved pause/resume
 * re-arm lifecycle so the wake detector restarts under the current
 * `log.tag.WakeWordDiag` property value.  The detector evaluates whether the
 * WakeWordDiag diagnostics are enabled only when a generation starts, so a
 * later/resumed physical run that enables DEBUG after the current generation
 * began must re-arm before its first trial — otherwise every trial of that
 * generation would silently lack the gateExitSummary energy evidence.
 *
 * This receiver opens no microphone: it only stops and restarts the existing
 * detector's `AudioRecord` through the same in-process calls the service uses
 * for mic arbitration.  The runner verifies the new detector generation
 * through the target journal; the broadcast result is a fast-fail only.
 *
 * Invocation (explicit component only, never implicit):
 *
 * ```
 * adb shell am broadcast -n com.kernel.ai.debug/com.kernel.ai.debug.wake.WakeDetectorRearmReceiver \
 *   -a com.kernel.ai.debug.action.REARM_WAKE_DETECTOR
 * ```
 *
 * Returns [RESULT_OK] when the re-arm was dispatched, or [RESULT_ERROR] for
 * an invalid invocation or a re-arm failure.  A no-op (service not running)
 * still returns [RESULT_OK]; the runner's journal verification is
 * authoritative and fails closed when no new generation appears.
 */
class WakeDetectorRearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (!isExplicitReceiverInvocation(appContext, intent)) {
                        finishWith(pendingResult, WakeDetectorRearmContract.RESULT_ERROR, "explicit_component_required")
                        return@post
                    }
                    // pause() releases the detector's AudioRecord and joins the
                    // detection loop; resume() re-arms it through the standard
                    // callbacks, allocating a fresh detector generation.
                    WakeWordService.pause(appContext)
                    WakeWordService.resume(appContext)
                    Log.i(LOG_TAG, "wake detector re-arm dispatched")
                    finishWith(pendingResult, WakeDetectorRearmContract.RESULT_OK, "rearmed")
                } catch (error: Exception) {
                    Log.e(LOG_TAG, "rearm_failed", error)
                    finishWith(pendingResult, WakeDetectorRearmContract.RESULT_ERROR, "rearm_failed")
                }
            }
        } catch (error: Exception) {
            Log.e(LOG_TAG, "receiver_dispatch_failed", error)
            finishWith(pendingResult, WakeDetectorRearmContract.RESULT_ERROR, "receiver_dispatch_failed")
        }
    }

    private fun isExplicitReceiverInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == WakeDetectorRearmReceiver::class.java.name

    private fun finishWith(pendingResult: PendingResult, code: Int, data: String) {
        pendingResult.setResultCode(code)
        pendingResult.setResultData(data)
        pendingResult.finish()
    }

    private companion object {
        const val LOG_TAG = "WakeRearm"
    }
}

/** Explicit broadcast contract for the debug wake-detector re-arm endpoint. */
object WakeDetectorRearmContract {
    const val ACTION = "com.kernel.ai.debug.action.REARM_WAKE_DETECTOR"

    /** Re-arm dispatched; the runner verifies the new generation via journal. */
    const val RESULT_OK = 0

    /** Invalid invocation or re-arm failure. */
    const val RESULT_ERROR = 2
}
