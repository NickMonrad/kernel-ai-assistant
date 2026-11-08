package com.kernel.ai.feature.widget

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"

@AndroidEntryPoint
class VoiceCommandService : Service() {

    @Inject lateinit var voiceCommandHandler: VoiceCommandHandler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transcript = intent?.getStringExtra(EXTRA_TRANSCRIPT)
        if (transcript.isNullOrBlank()) {
            Log.w(TAG, "VoiceCommandService: no transcript — stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val inputMode = intent.getStringExtra(EXTRA_INPUT_MODE) ?: "text"
        Log.d(TAG, "VoiceCommandService: handling transcript=\"$transcript\" mode=$inputMode")
        serviceScope.launch {
            try {
                voiceCommandHandler.handle(transcript, applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "VoiceCommandService: error handling transcript", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_EXECUTE_COMMAND = "com.kernel.ai.widget.EXECUTE_COMMAND"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_INPUT_MODE = "input_mode"
    }
}
