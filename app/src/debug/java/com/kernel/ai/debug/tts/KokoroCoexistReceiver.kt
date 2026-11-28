package com.kernel.ai.debug.tts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** #1557: starts [KokoroCoexistenceService]. Explicit component only; DUMP restricts senders to adb shell. */
class KokoroCoexistReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, KokoroCoexistenceService::class.java).putExtras(intent))
        resultCode = Activity.RESULT_OK
    }
}
