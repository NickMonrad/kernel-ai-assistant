package com.kernel.ai.debug.inflect

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.debug.tts.DebugTtsBaselineRequest
import com.kernel.ai.debug.tts.DebugTtsBaselineService
import com.kernel.ai.debug.tts.debugTtsBaselineOutput

/**
 * #2088: validates a baseline request and starts [DebugTtsBaselineService], which does the run in a foreground
 * service. Result data is the output JSON path, written when the run finishes; the service logs
 * `inflect baseline done` or `inflect baseline failed` under tag KernelAI.
 */
class InflectBaselineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val request = DebugTtsBaselineRequest.from(intent)
            val service = Intent(context, DebugTtsBaselineService::class.java).putExtras(intent)
            context.startForegroundService(service)
            resultCode = Activity.RESULT_OK
            resultData = debugTtsBaselineOutput(context, request.label).absolutePath
        } catch (error: Exception) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "baseline_failed:${error.message}"
        }
    }
}
