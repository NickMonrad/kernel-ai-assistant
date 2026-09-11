package com.kernel.ai.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import androidx.core.content.ContextCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.NO_SPEECH_WINDOW_EXHAUSTED
import com.kernel.ai.core.voice.AcousticJournalBridge
import com.kernel.ai.core.voice.CameraForegroundMonitor
import com.kernel.ai.core.voice.HonorCameraCoexistence
import com.kernel.ai.core.voice.UsageStatsForegroundEventSource
import com.kernel.ai.core.voice.WakeWordPreferences
import com.kernel.ai.core.voice.containsWakePhrase
import com.kernel.ai.core.voice.mayRearmAfterCamera
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.core.voice.WakeWordHandoff
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.feature.widget.EXTRA_PREFILLED_TRANSCRIPT
import java.io.File
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
import kotlinx.coroutines.flow.first
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

/**
 * Low-confidence wake-candidate verification wiring used by [WakeWordService]:
 * the delegated wake transcript must contain a supported Hey Jandal form.
 * `null` (no wake-verification support or transcription failure) rejects.
 */
internal suspend fun verifyWakeWindow(
    voiceInputController: VoiceInputController,
    pcm: ShortArray,
): Boolean = voiceInputController.transcribeBlocking(pcm)?.containsWakePhrase() ?: false

/**
 * #1439: names of the wake-verifier model files (Whisper tiny.en catalogue
 * entries) that are missing on device. Mirrors [SherpaSttModelSpec.requiredFileNames]
 * and the controller's models directory (`getExternalFilesDir("models")`).
 */
internal fun missingWakeVerifierModelFiles(context: Context): List<String> {
    val modelsDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    return SherpaSttModelSpec.WHISPER.requiredFileNames.filter { name ->
        !File(modelsDir, name).exists()
    }
}

/** Catalogue entries backing the wake-verifier model files (#1439). */
internal fun wakeVerifierKernelModels(): List<KernelModel> =
    SherpaSttModelSpec.WHISPER.requiredFileNames.mapNotNull { name ->
        KernelModel.entries.firstOrNull { it.fileName == name }
    }

/** Build consistent cue-journal metadata from a playback result. */
internal fun cueMetadata(
    cueResult: StartListeningCueResult,
    context: String = "wake_word",
    isError: Boolean = false,
): Map<String, String> {
    val m = mutableMapOf(
        "context" to context,
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

    /**
     * Recogniser started but produced no useful result.
     * [skipRetry] suppresses the session-level retry: set only for the
     * no-speech-window-exhausted case (#1433) where the command window has closed
     * and a second attempt would only re-wait.  The journal's STT_ERROR category
     * remains the standard "stt_recognition_failed".
     */
    data class NoTranscript(val category: String, val skipRetry: Boolean = false) : WakeAttemptOutcome

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
                // #1433: when the first session's no-speech window is fully exhausted
                // (in-place refreshes spent and the platform still heard nothing), the
                // command window has closed — a second attempt would only re-wait.
                // Genuine failures (any other error) still use the bounded retry.
                WakeAttemptOutcome.NoTranscript(
                    "stt_recognition_failed",
                    skipRetry = terminalEvent.category == NO_SPEECH_WINDOW_EXHAUSTED,
                )
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
                if (attempt < 2 && !outcome.skipRetry) continue else break
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

/** Outcome of one wake-command handoff (session + routing). */
internal data class WakeCommandHandoffResult(
    val completed: Boolean,
    val cancellationCategory: String,
)

/**
 * Execute the full wake-command handoff for one confirmed activation.
 *
 * #1433 ordering contract:
 * 1. The wake detector is stopped FIRST, and its strengthened `stop()` returns only
 *    after the detection loop has terminated and released its AudioRecord — so
 *    attempt-1 live STT capture is never requested while the detector still owns
 *    the microphone.
 * 2. `VOICE_SESSION_STARTED` is recorded only after that release.
 * 3. The first STT attempt must reach readiness; the alert-session silence budget
 *    (see `NativeAndroidVoiceInputController.ALERT_SESSION_SILENCE_TIMEOUT_MS`)
 *    keeps the session open across the cue-to-command handoff so the runner's
 *    command is captured in attempt 1 and no retry is needed on the normal path.
 *
 * The bounded retry still runs for genuine post-readiness recognition failures, but never when
 * [capturePermitted] reports that the microphone was taken away in the meantime (#1502).
 * On cancellation the active recognizer is stopped so no microphone owner or
 * recognizer is left behind.  [rearmDetector] is invoked exactly once, after the
 * session is terminal (including on cancellation, matching the previous service
 * behaviour).  Throws [CancellationException].
 */
internal suspend fun runWakeCommandHandoff(
    wakeWordDetector: WakeWordDetector,
    voiceInputController: VoiceInputController,
    cuePlayer: StartListeningCuePlayer,
    generationId: Long,
    sessionId: Long,
    onError: (String) -> Unit = {},
    routeTranscript: (String) -> Boolean,
    onSessionTerminal: () -> Unit,
    journal: WakeSessionJournal? = null,
    capturePermitted: () -> Boolean = { true },
): WakeCommandHandoffResult {
    // #1433: a confirmed wake activation must not request live STT capture until the
    // wake detector has completed microphone-resource release.
    wakeWordDetector.stop()

    val sessionJournal = journal ?: WakeSessionJournal(generationId, sessionId)
    sessionJournal.start()
    var completed = false
    var cancellationCategory = "stt_no_final_result"

    try {
        val sessionResult = runWakeCaptureSession { attempt ->
            // #1502: the microphone can be yielded mid-session (Honor Camera taking the
            // foreground). Starting another attempt would take it straight back and block
            // camera recording again, so the retry loop is closed instead.
            if (!capturePermitted()) {
                throw WakeAttemptCollectionException(
                    category = "wake_capture_suspended",
                    cause = IllegalStateException("wake capture suspended before attempt $attempt"),
                )
            }
            runWakeAttempt(
                voiceInputController = voiceInputController,
                journal = sessionJournal,
                cuePlayer = cuePlayer,
                attempt = attempt,
                onError = onError,
            )
        }

        cancellationCategory = sessionResult.cancellationCategory
        val transcript = sessionResult.transcript

        if (transcript != null) {
            if (routeTranscript(transcript)) {
                sessionJournal.record(
                    AcousticEventType.COMMAND_ROUTING_RESULT,
                    metadata = { mapOf("outcome" to "handed_off") },
                )
                completed = true
            } else {
                sessionJournal.record(
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
        // No microphone owner or recognizer may survive a cancelled handoff.
        voiceInputController.stopListening()
        throw e
    } catch (e: Exception) {
        cancellationCategory = "session_failed"
        Log.e(TAG, "WakeWordService: wake session failed", e)
    } finally {
        finalizeWakeSession(sessionJournal, completed, cancellationCategory)
        onSessionTerminal()
    }
    return WakeCommandHandoffResult(completed, cancellationCategory)
}
private const val TAG = "KernelAI"
private const val CHANNEL_ID = "kernel_wake_word"
private const val NOTIFICATION_ID = 9_500
private const val NOTIFICATION_TEXT_LISTENING = "Listening for wake word…"
private const val NOTIFICATION_TEXT_CAMERA_PAUSED = "Paused while Camera is in use"

/** #1502: monitor cadence validated on the Honor BKQ-N49 (well below the measured detection need). */
internal const val HONOR_CAMERA_POLL_INTERVAL_MS = 2_000L

/**
 * #1502: bounded lookback used only for the monitor's *initial* foreground state, so a service
 * that starts while the camera is already foreground still releases the microphone. Every later
 * poll reads only the window since the previous poll.
 */
internal const val HONOR_CAMERA_INITIAL_LOOKBACK_MS = 60_000L

/**
 * #1502: release every Jandal-owned microphone capture so the Honor Camera can record.
 *
 * The suspension latch is set *before* any capture is released: every re-arm path (voice-session
 * event, wake-command handoff terminal, debug resume hook) checks that latch, so none of them can
 * take the microphone back while the release is still in flight. On BKQ-N49 the wake detector is
 * not the only Jandal-owned capture that can block the camera — a session left over from a
 * wake→STT flow holds one too, hence [releaseVoiceCapture].
 */
internal fun releaseWakeCaptureForCamera(
    suspendLatch: () -> Unit,
    releaseWakeDetector: () -> Unit,
    releaseVoiceCapture: () -> Unit,
) {
    suspendLatch()
    releaseWakeDetector()
    releaseVoiceCapture()
}

/**
 * Promote the service to the microphone foreground state without allowing Android's
 * while-in-use eligibility failure to escape the service start callback.
 *
 * The callback is deliberately limited to the [startForeground] call site so unrelated
 * service defects are not suppressed. The caller decides how to stop and diagnose a
 * rejected start.
 */
internal fun tryPromoteToMicrophoneForeground(
    promote: () -> Unit,
    onRejected: (SecurityException) -> Unit,
): Boolean {
    return try {
        promote()
        true
    } catch (error: SecurityException) {
        onRejected(error)
        false
    }
}

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
    @Inject lateinit var modelDownloadManager: ModelDownloadManager
    @Inject lateinit var wakeWordPreferences: WakeWordPreferences
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventCollectorJob: Job? = null

    /** #1502: exactly one Honor-camera foreground monitor per service lifetime. */
    private var cameraMonitorJob: Job? = null

    /**
     * #1502: latch set while Jandal must not hold the microphone (Honor Camera foreground, or the
     * camera-coexistence capability was lost). Re-arm paths refuse to take the microphone while it
     * is set, so a camera-suspension cannot be undone by a concurrent voice-session event.
     */
    @Volatile private var isWakeCaptureSuspended = false

    /** True while a Jandal voice session (push-to-talk, assistant, widget) owns the microphone. */
    @Volatile private var isVoiceSessionActive = false

    /** Last text pushed to the ongoing notification; avoids redundant updates on every re-arm. */
    @Volatile private var notificationText = NOTIFICATION_TEXT_LISTENING

    /** True while [handleDetection] owns a live STT session; suppresses the observer's re-arm. */
    @Volatile private var isHandlingDetection = false

    /** One-shot guard: verifier model provisioning is requested at most once per service lifetime. */
    @Volatile private var wakeVerifierProvisionRequested = false

    /**
     * #1439: the low-band wake verifier prefers the Whisper tiny.en catalogue model
     * (the only model that recognises the fixed wake phrase). Queue the download
     * through the existing catalogue flow when the files are missing; verification
     * falls back to the online recognizer until they arrive.
     */
    private fun ensureWakeVerifierModels() {
        if (wakeVerifierProvisionRequested) return
        wakeVerifierProvisionRequested = true
        val missing = missingWakeVerifierModelFiles(this)
        if (missing.isEmpty()) return
        val models = wakeVerifierKernelModels()
        if (models.isEmpty()) {
            Log.w(TAG, "WakeWordService: wake-verifier catalogue entries missing for $missing")
            return
        }
        models.forEach { modelDownloadManager.startDownload(it, source = DownloadSource.AUTO_QUEUED) }
        Log.i(TAG, "WakeWordService: queued wake-verifier model download(s): $models")
    }

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

        val foregroundStarted = tryPromoteToMicrophoneForeground(
            promote = { startForeground(NOTIFICATION_ID, buildNotification(NOTIFICATION_TEXT_LISTENING)) },
            onRejected = { error ->
                Log.w(
                    TAG,
                    "WakeWordService: microphone FGS promotion not allowed; " +
                        "will retry when the app is foreground (${error.message})",
                )
                AcousticJournalBridge.record(
                    type = AcousticEventType.SERVICE_ERROR,
                    metadata = { mapOf("category" to "microphone_fgs_start_not_allowed") },
                )
            },
        )
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // #1502: on the Honor device where the camera contends for the microphone, wake capture may
        // only run with Usage Access — without it Jandal cannot learn that the camera needs the
        // microphone and would silently block video recording.
        //
        // This refusal deliberately runs *after* the foreground promotion: a service started with
        // startForegroundService() must reach startForeground() or Android kills the process with
        // ForegroundServiceDidNotStartInTimeException. The service is stopped immediately after, so
        // the ongoing notification never outlives the refusal.
        if (!HonorCameraCoexistence.isWakeCaptureAllowed(this)) {
            Log.w(
                TAG,
                "WakeWordService: Usage Access missing on ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "— refusing to start wake capture",
            )
            stopSelf(startId)
            AcousticJournalBridge.record(
                type = AcousticEventType.SERVICE_ERROR,
                metadata = { mapOf("category" to "usage_access_missing") },
            )
            return START_NOT_STICKY
        }

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
                        isVoiceSessionActive = true
                        Log.i(TAG, "WakeWordService: yielding mic to voice session (${event.mode})")
                        wakeWordDetector.stop()
                    }

                    is VoiceInputEvent.ListeningStopped -> {
                        isVoiceSessionActive = false
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

        startHonorCameraMonitor()

        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        cameraMonitorJob = null
        wakeWordDetector.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Honor camera coexistence (#1502) ───────────────────────────────────────

    /**
     * #1502: start the single Honor-camera foreground monitor.
     *
     * Only runs on the proven affected device (Usage Access is already verified by the refusal
     * above), inside [serviceScope] so it never blocks the main thread and is cancelled with the
     * service. Guarded so repeated `onStartCommand` deliveries cannot create a second monitor.
     */
    private fun startHonorCameraMonitor() {
        if (!HonorCameraCoexistence.isAffectedDevice(this)) return
        if (cameraMonitorJob?.isActive == true) return
        cameraMonitorJob = serviceScope.launch {
            CameraForegroundMonitor(
                source = UsageStatsForegroundEventSource(applicationContext),
                targetPackage = HonorCameraCoexistence.CAMERA_PACKAGE,
                pollIntervalMs = HONOR_CAMERA_POLL_INTERVAL_MS,
                initialLookbackMillis = HONOR_CAMERA_INITIAL_LOOKBACK_MS,
                isReady = { HonorCameraCoexistence.hasUsageAccess(this@WakeWordService) },
                onCameraEntered = { suspendForHonorCamera() },
                onCameraExited = { resumeAfterHonorCamera() },
                onUnavailable = { onHonorCameraCoexistenceUnavailable() },
            ).run()
        }
    }

    /**
     * #1502: the Honor Camera took the foreground — hand the microphone over before it records.
     *
     * The service and its foreground notification stay alive; only Jandal-owned captures are
     * released.
     */
    private fun suspendForHonorCamera() {
        Log.i(TAG, "WakeWordService: ${HonorCameraCoexistence.CAMERA_PACKAGE} foreground — releasing microphone")
        releaseWakeCaptureForCamera(
            suspendLatch = { isWakeCaptureSuspended = true },
            releaseWakeDetector = { wakeWordDetector.stop() },
            releaseVoiceCapture = { voiceInputController.stopListening() },
        )
        AcousticJournalBridge.record(
            type = AcousticEventType.SERVICE_ERROR,
            metadata = { mapOf("category" to "camera_foreground_yield") },
        )
        updateNotification(NOTIFICATION_TEXT_CAMERA_PAUSED)
    }

    /**
     * #1502: the Honor Camera left the foreground — re-arm, but only when every condition that
     * permits wake listening still holds.
     */
    private suspend fun resumeAfterHonorCamera() {
        isWakeCaptureSuspended = false
        val heyJandalEnabled = try {
            wakeWordPreferences.heyJandalEnabled.first()
        } catch (e: Exception) {
            Log.w(TAG, "WakeWordService: could not read Hey Jandal preference", e)
            true
        }
        val mayArm = mayRearmAfterCamera(
            heyJandalEnabled = heyJandalEnabled,
            recordAudioGranted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            captureSuspended = isWakeCaptureSuspended,
            voiceSessionActive = isVoiceSessionActive || isHandlingDetection,
            captureAllowed = HonorCameraCoexistence.isWakeCaptureAllowed(this),
        )
        if (!mayArm) {
            Log.i(TAG, "WakeWordService: camera left foreground but wake capture may not re-arm yet")
            return
        }
        Log.i(TAG, "WakeWordService: camera left foreground — re-arming wake capture")
        rearmDetector()
    }

    /**
     * #1502: Usage Access was revoked while the monitor was running, so Jandal can no longer yield
     * the microphone to the camera. Release every Jandal-owned capture and stop: listening must
     * not continue indefinitely without the capability that protects the camera. Voice settings
     * turns Hey Jandal off and explains what is needed the next time it is opened.
     */
    private fun onHonorCameraCoexistenceUnavailable() {
        Log.w(TAG, "WakeWordService: Usage Access lost — releasing microphone and stopping")
        AcousticJournalBridge.record(
            type = AcousticEventType.SERVICE_ERROR,
            metadata = { mapOf("category" to "usage_access_revoked") },
        )
        releaseWakeCaptureForCamera(
            suspendLatch = { isWakeCaptureSuspended = true },
            releaseWakeDetector = { wakeWordDetector.stop() },
            releaseVoiceCapture = { voiceInputController.stopListening() },
        )
        stopSelf()
    }

    // ── Detection handoff ──────────────────────────────────────────────────────

    private fun handleDetection(generationId: Long, sessionId: Long) {
        serviceScope.launch {
            isHandlingDetection = true
            try {
                runWakeCommandHandoff(
                    wakeWordDetector = wakeWordDetector,
                    voiceInputController = voiceInputController,
                    cuePlayer = cuePlayer,
                    generationId = generationId,
                    sessionId = sessionId,
                    onError = { msg -> if (msg.isNotBlank()) showWakeWordError(msg) },
                    routeTranscript = ::routeTranscript,
                    onSessionTerminal = {
                        isHandlingDetection = false
                        rearmDetector()
                    },
                    // #1502: a camera-driven yield must not be undone by the session's retry.
                    capturePermitted = { !isWakeCaptureSuspended },
                )
            } catch (e: CancellationException) {
                isHandlingDetection = false
                throw e
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
        // #1502: a camera-driven suspension is a latch — no re-arm path may take the microphone
        // back while the Honor Camera needs it.
        if (isWakeCaptureSuspended) {
            Log.i(TAG, "WakeWordService: wake capture suspended — not re-arming")
            return
        }
        ensureWakeVerifierModels()
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
                        verifyWakeWindow(voiceInputController, pcm)
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
        updateNotification(NOTIFICATION_TEXT_LISTENING)
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

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Jandal")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * #1502: keep the ongoing notification truthful about whether Jandal is actually listening —
     * it must not claim to listen while wake capture is suspended for the Honor Camera. No-op when
     * the text is unchanged, so repeated re-arms do not churn the notification.
     */
    private fun updateNotification(text: String) {
        if (notificationText == text) return
        notificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
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
