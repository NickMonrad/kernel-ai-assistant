package com.kernel.ai.debug.tts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * #2088 debug TTS baseline entry point. Validates the request, refuses while a run is active, and starts
 * [DebugTtsBaselineService], which does the run in a foreground service. Result data is the output JSON path,
 * written when the run finishes; the service logs `tts baseline done` or `tts baseline failed` under tag KernelAI.
 */
class DebugTtsBaselineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val request = DebugTtsBaselineRequest.from(intent)
            check(!DebugTtsBaselineService.isRunning()) { "a baseline run is already active" }
            context.startForegroundService(Intent(context, DebugTtsBaselineService::class.java).putExtras(intent))
            resultCode = Activity.RESULT_OK
            resultData = debugTtsBaselineOutput(context, request.lane, request.label).absolutePath
        } catch (error: Exception) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "baseline_failed:${error.message}"
        }
    }
}
