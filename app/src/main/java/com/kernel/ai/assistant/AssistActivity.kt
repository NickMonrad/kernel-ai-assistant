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
        Log.d(TAG, "AssistActivity: ASSIST received — delegating to VoiceCommandActivity")
        startActivity(
            Intent(this, VoiceCommandActivity::class.java).apply {
                // Don't stack on top of whatever was on screen; VoiceCommandActivity
                // is already declared with taskAffinity="" so it manages its own task.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }
}
