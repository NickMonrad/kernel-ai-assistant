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
import com.kernel.ai.feature.widget.EXTRA_PREFILLED_TRANSCRIPT
import com.kernel.ai.feature.widget.VoiceCommandActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import javax.inject.Inject

private const val TAG = "KernelAI"
private const val CHANNEL_ID = "kernel_wake_word"
private const val NOTIFICATION_ID = 9_500

/**
 * Foreground service that keeps [WakeWordDetector] running continuously.
 *
 * Started when "Listen for Hey Jandal" is enabled in Settings → Voice.
 *
 * **Mic arbitration:** [WakeWordDetector] holds a raw [android.media.AudioRecord] continuously.
 * To avoid blocking [android.speech.SpeechRecognizer] when the widget, Side key, or any other
 * caller opens a voice session, this service observes [VoiceInputController.events]:
 * - [VoiceInputEvent.ListeningStarted] → stop the detector (release AudioRecord)
 * - [VoiceInputEvent.ListeningStopped] → re-arm the detector
 *
 * This handles every caller (widget, assistant session, chat) automatically with no
 * explicit coordination required from those callers.
 *
 * On wake word detection:
 * 1. Plays the start-listening cue
 * 2. Starts STT via [VoiceInputController] on [VoiceCaptureMode.AlertCommand]
 * 3. Launches [VoiceCommandActivity] with the transcript pre-filled via
 *    [EXTRA_PREFILLED_TRANSCRIPT] — shows the same bottom-sheet overlay as the long-press
 *    flow, then routes to ActionsScreen for the voice reply.
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
        instance = WeakReference(this)
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

        // Automatically yield the AudioRecord whenever another voice session is active.
        serviceScope.launch {
            voiceInputController.events.collect { event ->
                when (event) {
                    is VoiceInputEvent.ListeningStarted -> {
                        Log.i(TAG, "WakeWordService: yielding mic to voice session (${event.mode})")
                        wakeWordDetector.stop()
                    }
                    is VoiceInputEvent.ListeningStopped -> {
                        if (wakeWordDetector.isAvailable) {
                            Log.i(TAG, "WakeWordService: re-arming after voice session (${event.mode})")
                            wakeWordDetector.start(onDetected = { handleDetection() })
                        }
                    }
                    else -> Unit
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
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

            // Collect until the session definitively ends (Transcript, Error, or
            // ListeningStopped). Using only filterIsInstance<Transcript>.first() would
            // block forever on a SharedFlow if the session ends with Error/timeout.
            val terminalEvent = try {
                voiceInputController.events
                    .onEach { event ->
                        if (event is VoiceInputEvent.ListeningStarted) cuePlayer.playCue()
                    }
                    .first { it is VoiceInputEvent.Transcript
                          || it is VoiceInputEvent.Error
                          || it is VoiceInputEvent.ListeningStopped }
            } catch (e: Exception) {
                Log.w(TAG, "WakeWordService: transcript collection failed — re-arming", e)
                wakeWordDetector.start(onDetected = { handleDetection() })
                return@launch
            }

            val transcript = (terminalEvent as? VoiceInputEvent.Transcript)?.text
            if (transcript.isNullOrBlank()) {
                Log.w(TAG, "WakeWordService: session ended without transcript ($terminalEvent) — re-arming")
                wakeWordDetector.start(onDetected = { handleDetection() })
                return@launch
            }

            Log.d(TAG, "WakeWordService: routing transcript=\"$transcript\"")
            routeTranscript(transcript)

            // Re-arm is handled by the ListeningStopped observer above.
        }
    }

    private fun routeTranscript(transcript: String) {
        // Launch VoiceCommandActivity with the transcript pre-filled.
        // This shows the same bottom-sheet overlay the user sees from the long-press or widget
        // flow, skipping the STT step since we already have the recognised text. The activity
        // then calls navigateToActions → ActionsScreen executes and speaks the result.
        startActivity(
            Intent(this, VoiceCommandActivity::class.java).apply {
                putExtra(EXTRA_PREFILLED_TRANSCRIPT, transcript)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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
        const val ACTION_PAUSE  = "com.kernel.ai.assistant.WAKE_PAUSE"
        const val ACTION_RESUME = "com.kernel.ai.assistant.WAKE_RESUME"

        /**
         * Weak reference to the running service instance.
         * Set in [onCreate], cleared in [onDestroy].
         * All callers are in the same process — no IPC needed.
         */
        private var instance: WeakReference<WakeWordService>? = null

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                // Android restricts startForegroundService() when the app is not in the
                // foreground (ForegroundServiceStartNotAllowedException on API 31+).
                // This can happen when the DataStore preference flow re-emits on restore.
                // Log and ignore — the service will be started next time the app resumes.
                Log.w(TAG, "WakeWordService: cannot start from background: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /**
         * Release AudioRecord so another caller can open the mic.
         * No-op (safe) if the service is not running.
         */
        fun pause(context: Context) {
            instance?.get()?.let { svc ->
                Log.i("KernelAI", "WakeWordService: pausing (mic requested by external caller)")
                svc.wakeWordDetector.stop()
            }
        }

        /**
         * Re-arm wake word detection after the STT session ends.
         * No-op (safe) if the service is not running.
         */
        fun resume(context: Context) {
            instance?.get()?.let { svc ->
                if (svc.wakeWordDetector.isAvailable) {
                    Log.i("KernelAI", "WakeWordService: resuming wake word detection")
                    svc.wakeWordDetector.start(onDetected = { svc.handleDetection() })
                }
            }
        }
    }
}
