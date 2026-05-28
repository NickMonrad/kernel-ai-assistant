package com.kernel.ai.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kernel.ai.feature.widget.VoiceCommandActivity

private const val TAG = "KernelAI"

/**
 * Trampoline activity that satisfies Samsung One UI's requirement for an
 * android.intent.action.ASSIST intent filter before an app appears in
 * Settings → Default Apps → Digital assistant.
 *
 * AOSP only requires [JandalVoiceInteractionService], but Samsung's settings
 * UI additionally enumerates apps that declare an ASSIST-capable activity.
 * This activity exists solely to pass that eligibility check.
 *
 * On launch it delegates immediately to [VoiceCommandActivity] (the existing
 * voice overlay used by the homescreen widget), which already handles the full
 * voice capture → QuickIntentRouter → skill execution → TTS pipeline.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guard against explicit-intent abuse from third-party apps. The OS always sends
        // ACTION_ASSIST when invoking the digital assistant; any other action means this
        // activity was started directly (not via the assistant gesture) and we reject it.
        if (intent?.action != Intent.ACTION_ASSIST) {
            Log.w(TAG, "AssistActivity: unexpected action '${intent?.action}' — ignoring")
            finish()
            return
        }
        Log.d(TAG, "AssistActivity: ASSIST received — delegating to VoiceCommandActivity")
        // VoiceCommandActivity has taskAffinity="" so it will land in its own task via
        // standard affinity matching. No FLAG_ACTIVITY_NEW_TASK needed — AssistActivity
        // is itself an Activity (not a Service/BroadcastReceiver context), so the flag
        // would only force an unnecessary extra task hop.
        startActivity(Intent(this, VoiceCommandActivity::class.java))
        finish()
    }
}
