package com.kernel.ai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.kernel.ai.MainActivity
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.feature.widget.VoiceCommandService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"
private const val CHANNEL_ID = "kernel_wake_word"
private const val NOTIFICATION_ID = 9_500

/**
 * Foreground service that keeps [WakeWordDetector] running continuously.
 *
 * Started when "Listen for Hey Jandal" is enabled in Settings → Voice.
 * Stopped when disabled or when [JandalVoiceInteractionService] (from #983) takes over
 * the privileged microphone path via [android.service.voice.VoiceInteractionService].
 *
 * On wake word detection:
 * 1. Plays the start-listening cue (already open on the [VoiceInputController] path)
 * 2. Starts STT via [VoiceInputController] on [VoiceCaptureMode.AlertCommand]
 * 3. Routes the transcript to [VoiceCommandService] — same execution seam as widget + assistant
 *
 * If [WakeWordDetector.isAvailable] is false (model not yet trained, see #984),
 * the service posts a notification explaining this and stops itself.
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var wakeWordDetector: WakeWordDetector
    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var cuePlayer: StartListeningCuePlayer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!wakeWordDetector.isAvailable) {
            Log.i(TAG, "WakeWordService: model not yet available (#984) — stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Log.i(TAG, "WakeWordService: starting wake word detection")
        wakeWordDetector.start(onDetected = { handleDetection() })

        return START_STICKY
    }

    override fun onDestroy() {
        wakeWordDetector.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Detection handoff ──────────────────────────────────────────────────────

    private fun handleDetection() {
        serviceScope.launch {
            val startResult = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)
            if (startResult !is VoiceInputStartResult.Started) {
                Log.w(TAG, "WakeWordService: STT unavailable after detection — $startResult; re-arming")
                wakeWordDetector.start(onDetected = { handleDetection() })
                return@launch
            }

            val transcript = try {
                voiceInputController.events
                    .onEach { event ->
                        if (event is VoiceInputEvent.ListeningStarted) cuePlayer.playCue()
                    }
                    .filterIsInstance<VoiceInputEvent.Transcript>()
                    .first()
                    .text
            } catch (e: Exception) {
                Log.w(TAG, "WakeWordService: transcript collection failed — re-arming", e)
                wakeWordDetector.start(onDetected = { handleDetection() })
                return@launch
            }

            Log.d(TAG, "WakeWordService: routing transcript=\"$transcript\"")
            routeTranscript(transcript)

            // Re-arm for the next detection.
            wakeWordDetector.start(onDetected = { handleDetection() })
        }
    }

    private fun routeTranscript(transcript: String) {
        val intent = Intent(this, VoiceCommandService::class.java).apply {
            action = VoiceCommandService.ACTION_EXECUTE_COMMAND
            putExtra(VoiceCommandService.EXTRA_TRANSCRIPT, transcript)
            putExtra(VoiceCommandService.EXTRA_INPUT_MODE, "voice")
        }
        startService(intent)
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hey Jandal",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Always-on wake word detection"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Jandal")
            .setContentText("Listening for wake word…")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
