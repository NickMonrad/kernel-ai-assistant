package com.kernel.ai.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernel.ai.core.voice.SpeechStopCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Handles the speaking notification's Stop action (#1873). Not exported. */
@AndroidEntryPoint
class SpeechStopReceiver : BroadcastReceiver() {

    @Inject lateinit var speechStopCoordinator: SpeechStopCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_SPEECH) return
        speechStopCoordinator.requestStop()
    }
}
