package com.kernel.ai.debug.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.feature.chat.speakInSentenceChunks
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only (#1942): speaks the text in a file through the production chunked speech path
 * (speakInSentenceChunks -> VoiceOutputController), so chunk timing can be measured without calendar data.
 * Extra "path" = absolute file path readable by the app. Explicit component only.
 */
class SpeechProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: run {
            Log.w(TAG, "speech probe: missing path extra")
            return
        }
        val appContext = context.applicationContext
        val controller = EntryPointAccessors.fromApplication(
            appContext,
            SpeechProbeEntryPoint::class.java,
        ).voiceOutputController()
        CoroutineScope(Dispatchers.IO).launch {
            val text = File(path).readText()
            Log.i(TAG, "speech probe start chars=${text.length} path=$path")
            val result = speakInSentenceChunks(controller, text)
            Log.i(TAG, "speech probe result=$result")
        }
    }

    private companion object {
        const val TAG = "KernelAI"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpeechProbeEntryPoint {
    fun voiceOutputController(): VoiceOutputController
}
