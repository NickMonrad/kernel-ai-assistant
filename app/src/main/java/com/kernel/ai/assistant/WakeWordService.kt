package com.kernel.ai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import androidx.core.content.ContextCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.AcousticJournalBridge
import com.kernel.ai.core.voice.containsWakePhrase
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.core.voice.WakeWordHandoff
import com.kernel.ai.feature.widget.EXTRA_PREFILLED_TRANSCRIPT
import com.kernel.ai.feature.widget.VoiceCommandActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

internal fun transcriptEvidenceSha256(text: String): String {
    val normalized = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Build consistent cue-journal metadata from a playback result. */
internal fun cueMetadata(
    cueResult: StartListeningCueResult,
    isError: Boolean = false,
): Map<String, String> {
    val m = mutableMapOf(
        "context" to "wake_word",
        "policy_version" to cueResult.policyVersion,
        "stream" to (cueResult.selectedStream?.toString() ?: "unknown"),
        "current_volume" to (cueResult.currentVolume?.toString() ?: "unknown"),
        "max_volume" to (cueResult.maxVolume?.toString() ?: "unknown"),
        "route" to (cueResult.routeClassification ?: "unknown"),
    )
    if (isError) {
        m["category"] = cueResult.failureCategory ?: "unknown"
    }
    return m
}

/**
 * Play the start-listening cue for a wake-word capture attempt and record
 * truthful playback evidence. Returns the playback result for downstream use.
 */
internal fun playWakeCue(
    journal: WakeSessionJournal,
    cuePlayer: StartListeningCuePlayer,
): StartListeningCueResult {
    journal.record(
        AcousticEventType.CUE_REQUESTED,
        metadata = {
            mapOf(
                "context" to "wake_word",
                "policy_version" to "2026-07-cue-v1",
            )
        },
    )
    val cueResult = cuePlayer.playCue(StartListeningCueContext.WAKE_WORD)
    if (cueResult.started) {
        journal.record(
            AcousticEventType.CUE_PLAYBACK_STARTED,
            metadata = { cueMetadata(cueResult) },
        )
    } else {
        journal.record(
            AcousticEventType.CUE_PLAYBACK_ERROR,
            metadata = { cueMetadata(cueResult, isError = true) },
        )
    }
    return cueResult
}

/** Outcome of one bounded wake STT attempt. */
internal sealed interface WakeAttemptOutcome {
    /** Recognised and returned a transcript. */
    data class GotTranscript(val text: String) : WakeAttemptOutcome
    /** Recogniser started but produced no useful result. */
    data class NoTranscript(val category: String) : WakeAttemptOutcome
    /** STT could not be started. */
    data object Unavailable : WakeAttemptOutcome
}

/**
 * Thrown from [runWakeAttempt] when event collection fails.
 * [category] distinguishes pre-readiness ("startup_collection_failed") from
 * post-readiness ("transcript_collection_failed") failures.
 */
internal class WakeAttemptCollectionException(
    val category: String,
    cause: Throwable,
) : RuntimeException(cause)

/**
 * Execute one bounded wake-word STT attempt.
 *
 * Creates a temporary buffered collector before calling [startListening] so
 * events emitted synchronously before the call returns are not lost.
 * Plays the cue only after [VoiceInputEvent.ListeningStarted].
 * The collector and channel are always cleaned up in [finally].
 * [onError] is called for non-fatal user-facing messages.
 *
 * Throws [WakeAttemptCollectionException] when event collection fails,
 * distinguishing pre-readiness from post-readiness failures.
 * Does NOT convert [CancellationException].
 */
internal suspend fun runWakeAttempt(
    voiceInputController: VoiceInputController,
    journal: WakeSessionJournal,
    cuePlayer: StartListeningCuePlayer,
    attempt: Int,
    onError: (String) -> Unit = {},
): WakeAttemptOutcome = coroutineScope {
    val attemptEvents = Channel<VoiceInputEvent>(Channel.BUFFERED)
    val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            voiceInputController.events.collect(attemptEvents::send)
            attemptEvents.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attemptEvents.close(e)
        }
    }
    try {
        var reachedReadiness = false
        try {
            journal.record(
                AcousticEventType.STT_START_REQUESTED,
                metadata = { mapOf("attempt" to attempt.toString()) },
            )
            val startResult = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)
            if (startResult !is VoiceInputStartResult.Started) {
                journal.record(
                    AcousticEventType.STT_ERROR,
                    metadata = { mapOf("category" to "stt_unavailable") },
                )
                (startResult as? VoiceInputStartResult.Unavailable)?.message?.let { msg ->
                    if (msg.isNotBlank()) onError(msg)
                }
                return@coroutineScope WakeAttemptOutcome.Unavailable
            }

            val captureSessionId = startResult.captureSessionId
            suspend fun awaitEvent(
                isTerminal: (VoiceInputEvent) -> Boolean,
            ): VoiceInputEvent {
                while (true) {
                    val result = attemptEvents.receiveCatching()
                    val event = result.getOrNull()
                    if (event == null) {
                        val cause = result.exceptionOrNull()
                        if (cause is CancellationException) throw cause
                        throw cause ?: IllegalStateException(
                            "Voice event stream completed before a terminal event",
                        )
                    }
                    if (!event.isWakeSessionEvent(captureSessionId)) continue
                    when (event) {
                        is VoiceInputEvent.SpeechDetected -> journal.record(
                            AcousticEventType.STT_SPEECH_DETECTED,
                        )
                        is VoiceInputEvent.PartialTranscript -> journal.record(
                            AcousticEventType.STT_PARTIAL,
                            metadata = { mapOf("length" to event.text.length.toString()) },
                        )
                        else -> Unit
                    }
                    if (isTerminal(event)) return event
                }
            }

            val startupEvent = awaitEvent {
                it is VoiceInputEvent.ListeningStarted ||
                    it is VoiceInputEvent.Transcript ||
                    it is VoiceInputEvent.Error ||
                    it is VoiceInputEvent.ListeningStopped
            }

            if (startupEvent is VoiceInputEvent.ListeningStarted) {
                reachedReadiness = true
                journal.record(AcousticEventType.STT_READY)
            }

            val terminalEvent = when (startupEvent) {
                is VoiceInputEvent.ListeningStarted -> {
                    playWakeCue(journal, cuePlayer)
                    awaitEvent {
                        it is VoiceInputEvent.Transcript ||
                            it is VoiceInputEvent.Error ||
                            it is VoiceInputEvent.ListeningStopped
                    }
                }
                else -> startupEvent
            }

            val text = (terminalEvent as? VoiceInputEvent.Transcript)?.text
            if (!text.isNullOrBlank()) {
                journal.record(
                    AcousticEventType.STT_FINAL,
                    metadata = {
                        mapOf(
                            "length" to text.length.toString(),
                            "normalized_transcript_sha256" to transcriptEvidenceSha256(text),
                        )
                    },
                )
                WakeAttemptOutcome.GotTranscript(text)
            } else if (terminalEvent is VoiceInputEvent.Error) {
                journal.record(
                    AcousticEventType.STT_ERROR,
                    metadata = { mapOf("category" to "stt_recognition_failed") },
                )
                WakeAttemptOutcome.NoTranscript("stt_recognition_failed")
            } else {
                WakeAttemptOutcome.NoTranscript("stt_stopped_without_result")
            }
        } catch (e: WakeAttemptCollectionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val category = if (reachedReadiness) "transcript_collection_failed" else "startup_collection_failed"
            throw WakeAttemptCollectionException(category, e)
        }
    } finally {
        collectorJob.cancelAndJoin()
        attemptEvents.cancel()
    }
}

/**
 * Result of a bounded wake capture session (max 2 attempts).
 */
internal data class WakeSessionCaptureResult(
    val transcript: String?,
    val cancellationCategory: String,
)

/**
 * Bounded wake-word STT capture session with retry.
 *
 * Runs up to 2 attempts via [runAttempt], stopping at the first transcript.
 * Returns the transcript (if any) and the final cancellation category.
 *
 * Throws [WakeAttemptCollectionException] from individual attempts.
 * Does NOT convert [CancellationException].
 */
internal suspend fun runWakeCaptureSession(
    runAttempt: suspend (attempt: Int) -> WakeAttemptOutcome,
): WakeSessionCaptureResult {
    var transcript: String? = null
    var cancellationCategory = "stt_no_final_result"
    for (attempt in 1..2) {
        val outcome = try {
            runAttempt(attempt)
        } catch (e: WakeAttemptCollectionException) {
            cancellationCategory = e.category
            break
        }
        when (outcome) {
            is WakeAttemptOutcome.GotTranscript -> {
                transcript = outcome.text
                break
            }
            is WakeAttemptOutcome.NoTranscript -> {
                cancellationCategory = outcome.category
                if (attempt < 2) continue else break
            }
            is WakeAttemptOutcome.Unavailable -> {
                cancellationCategory = "stt_unavailable"
                break
            }
        }
    }
    return WakeSessionCaptureResult(transcript, cancellationCategory)
}
internal fun finalizeWakeSession(
    journal: WakeSessionJournal,
    completed: Boolean,
    cancellationCategory: String,
) {
    if (completed) {
        journal.complete()
    } else {
        journal.cancel(cancellationCategory)
    }
}
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
 * **Security:** [VoiceCommandActivity] is exported=true (required for assistant eligibility).
 * To prevent external apps from injecting arbitrary transcripts, [pendingWakeWordTranscript]
 * is set in this service's process memory immediately before [startActivity]. The activity
 * reads and clears it, and only trusts the extra when the in-process value matches.
 * External callers cannot access this JVM field.
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
    private var eventCollectorJob: Job? = null

    /** True while [handleDetection] owns a live STT session; suppresses the observer's re-arm. */
    @Volatile private var isHandlingDetection = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        instance = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If RECORD_AUDIO is not granted, refuse to start the foreground service.
        // startForeground with foregroundServiceType=microphone requires RECORD_AUDIO
        // or FOREGROUND_SERVICE_MICROPHONE — without it Android throws SecurityException.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WakeWordService: RECORD_AUDIO not granted — refusing to start")
            stopSelf(startId)
            AcousticJournalBridge.record(
                type = AcousticEventType.SERVICE_ERROR,
                metadata = { mapOf("category" to "record_audio_permission_missing") },
            )
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (!wakeWordDetector.isAvailable) {
            Log.i(TAG, "WakeWordService: model not yet available (#984) — stopping")
            AcousticJournalBridge.record(
                type = AcousticEventType.SERVICE_ERROR,
                metadata = { mapOf("category" to "wake_model_unavailable") },
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Guard: if the detector and collector are already running (re-delivery of a
        // START_STICKY intent or a spurious onResume retry), do not start duplicates.
        if (eventCollectorJob?.isActive == true) {
            Log.d(TAG, "WakeWordService: already running — ignoring duplicate onStartCommand")
            return START_STICKY
        }

        Log.i(TAG, "WakeWordService: starting wake word detection")
        rearmDetector()

        // Automatically yield the AudioRecord whenever another voice session is active.
        eventCollectorJob = serviceScope.launch {
            voiceInputController.events.collect { event ->
                when (event) {
                    is VoiceInputEvent.ListeningStarted -> {
                        Log.i(TAG, "WakeWordService: yielding mic to voice session (${event.mode})")
                        wakeWordDetector.stop()
                    }

                    is VoiceInputEvent.ListeningStopped -> {
                        if (isHandlingDetection) return@collect
                        Log.i(TAG, "WakeWordService: re-arming after voice session (${event.mode})")
                        rearmDetector()
                    }

                    is VoiceInputEvent.SpeechDetected,
                    is VoiceInputEvent.PartialTranscript,
                    is VoiceInputEvent.Transcript,
                    is VoiceInputEvent.Error,
                    -> Unit
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



    private fun handleDetection(generationId: Long, sessionId: Long) {
        serviceScope.launch {
            isHandlingDetection = true
            val journal = WakeSessionJournal(generationId, sessionId)
            journal.start()
            var completed = false
            var cancellationCategory = "stt_no_final_result"

            try {
                val sessionResult = runWakeCaptureSession { attempt ->
                    runWakeAttempt(
                        voiceInputController = voiceInputController,
                        journal = journal,
                        cuePlayer = cuePlayer,
                        attempt = attempt,
                        onError = { msg ->
                            if (msg.isNotBlank()) showWakeWordError(msg)
                        },
                    )
                }

                cancellationCategory = sessionResult.cancellationCategory
                val transcript = sessionResult.transcript

                if (transcript != null) {
                    Log.d(TAG, "WakeWordService: routing final transcript")
                    if (routeTranscript(transcript)) {
                        journal.record(
                            AcousticEventType.COMMAND_ROUTING_RESULT,
                            metadata = { mapOf("outcome" to "handed_off") },
                        )
                        completed = true
                    } else {
                        journal.record(
                            AcousticEventType.COMMAND_ROUTING_RESULT,
                            metadata = {
                                mapOf(
                                    "outcome" to "failed",
                                    "category" to "route_activity_failed",
                                )
                            },
                        )
                        cancellationCategory = "route_activity_failed"
                    }
                }
            } catch (e: WakeAttemptCollectionException) {
                cancellationCategory = e.category
                Log.w(TAG, "WakeWordService: collection ${e.category} (attempt)", e)
            } catch (e: CancellationException) {
                cancellationCategory = "session_cancelled"
                throw e
            } catch (e: Exception) {
                cancellationCategory = "session_failed"
                Log.e(TAG, "WakeWordService: wake session failed", e)
            } finally {
                finalizeWakeSession(journal, completed, cancellationCategory)
                isHandlingDetection = false
                rearmDetector()
            }
        }
    }
    private fun showWakeWordError(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }


    /** Re-arms [wakeWordDetector] with the standard callbacks. */
    private fun rearmDetector() {
        if (!wakeWordDetector.isAvailable) {
            AcousticJournalBridge.record(
                type = AcousticEventType.SERVICE_ERROR,
                metadata = { mapOf("category" to "wake_model_unavailable") },
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WakeWordService: RECORD_AUDIO not granted — not re-arming detector")
            AcousticJournalBridge.record(
                type = AcousticEventType.SERVICE_ERROR,
                metadata = { mapOf("category" to "record_audio_permission_missing") },
            )
            return
        }
        val generationId = AcousticJournalBridge.allocateGenerationId()
        wakeWordDetector.start(
            generationId = generationId,
            onDetected = {
                val sessionId = AcousticJournalBridge.allocateSessionId()
                AcousticJournalBridge.record(
                    type = AcousticEventType.WAKE_CALLBACK_INVOKED,
                    generationId = generationId,
                    sessionId = sessionId,
                )
                handleDetection(generationId, sessionId)
            },
            verifyWindow = { pcm ->
                try {
                    kotlinx.coroutines.runBlocking {
                        voiceInputController.transcribeBlocking(pcm)?.containsWakePhrase() ?: false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WakeWordService: wake word verification failed", e)
                    false
                }
            },
        )
        AcousticJournalBridge.record(
            type = AcousticEventType.DETECTOR_REARMED,
            generationId = generationId,
        )
    }

    private fun routeTranscript(transcript: String): Boolean {
        // Set the in-process authorisation token before launching the activity.
        // VoiceCommandActivity checks this field and clears it on read — external callers
        // cannot set it, so they cannot inject transcripts even though the activity is exported.
        WakeWordHandoff.pendingTranscript = transcript
        return try {
            startActivity(
                Intent(this, VoiceCommandActivity::class.java).apply {
                    putExtra(EXTRA_PREFILLED_TRANSCRIPT, transcript)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            // Clear the token if startActivity failed so it doesn't linger.
            WakeWordHandoff.pendingTranscript = null
            Log.e(TAG, "WakeWordService: failed to launch VoiceCommandActivity", e)
            false
        }
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
         * In-process authorisation token for the prefilled-transcript overlay path.
         *
         * Set by [WakeWordService.routeTranscript] immediately before [startActivity];
         * read and cleared by [VoiceCommandActivity]. Because this is a JVM field,
         * external apps cannot write it — so [VoiceCommandActivity] can trust the
         * prefilled transcript only when this matches the intent extra.
         */

        /**
         * Weak reference to the running service instance.
         * Set in [onCreate], cleared in [onDestroy].
         * All callers are in the same process — no IPC needed.
         */
        private var instance: WeakReference<WakeWordService>? = null

        fun start(context: Context) {
            // Don't start if RECORD_AUDIO is not granted — startForegroundService
            // for a service with foregroundServiceType=microphone requires it.
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "WakeWordService: RECORD_AUDIO not granted — cannot start FGS")
                return
            }
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
                Log.i("KernelAI", "WakeWordService: resuming wake word detection")
                svc.rearmDetector()
            }
        }
    }
}

internal fun VoiceInputEvent.isWakeSessionEvent(captureSessionId: Long): Boolean =
    mode == VoiceCaptureMode.AlertCommand && this.captureSessionId == captureSessionId
