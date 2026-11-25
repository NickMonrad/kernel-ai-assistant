package com.kernel.ai.assistant
import com.kernel.ai.core.inference.diagnostics.WakeCategory

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import android.util.Log
import androidx.core.content.ContextCompat
import com.kernel.ai.MainActivity
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.OwnMicCaptureSessions
import com.kernel.ai.core.voice.NO_SPEECH_WINDOW_EXHAUSTED
import com.kernel.ai.core.voice.NATIVE_NOT_READY
import com.kernel.ai.core.voice.wakeVerifierKernelModels
import com.kernel.ai.core.voice.NATIVE_STALLED
import com.kernel.ai.core.voice.AcousticJournalBridge
import com.kernel.ai.core.voice.CameraForegroundMonitor
import com.kernel.ai.core.voice.HonorCameraCoexistence
import com.kernel.ai.core.voice.UsageStatsCurrentForegroundSource
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
import com.kernel.ai.core.voice.latestNoticeFor
import com.kernel.ai.core.voice.noticesFor
import com.kernel.ai.core.voice.WakeWordHandoff
import com.kernel.ai.core.voice.WakeOverlayChannel
import com.kernel.ai.core.voice.WAKE_OVERLAY_NOTIFICATION_TIMEOUT_MS
import com.kernel.ai.core.voice.WAKE_OVERLAY_CHANNEL_ID
import com.kernel.ai.core.voice.WAKE_OVERLAY_NOTIFICATION_ID
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.LifecycleOutcome
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.voice.SherpaSttModelSpec
import com.kernel.ai.feature.widget.EXTRA_WAKE_OVERLAY
import com.kernel.ai.feature.widget.EXTRA_WAKE_HELD
import com.kernel.ai.feature.widget.EXTRA_WAKE_TOKEN
import java.io.File
import com.kernel.ai.feature.widget.VoiceCommandActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * [skipRetry] suppresses the session-level retry: set for the no-speech-window-exhausted
     * case (#1433), where the command window has closed and a second attempt would only
     * re-wait; for a native recognizer that never became ready or stalled before speech with no
     * native hand-off to asr4all (#1853); and after that hand-off, where a retry would start the
     * failing native recognizer again. The journal's STT_ERROR category remains the
     * standard "stt_recognition_failed".
     * [errorMessage] is the recognizer's terminal error, shown only if no retry follows.
     */
    data class NoTranscript(
        val category: String,
        val skipRetry: Boolean = false,
        val errorMessage: String? = null,
    ) : WakeAttemptOutcome

    /** STT could not be started. */
    data object Unavailable : WakeAttemptOutcome
}

/**
 * Terminal error categories that end the wake session without the bounded retry. The native
 * ones mean the on-device recognizer never became ready or stalled before speech with no
 * native hand-off to asr4all; a retry would wait the same way again (#1853).
 */
private val NO_RETRY_ERROR_CATEGORIES = setOf(NO_SPEECH_WINDOW_EXHAUSTED, NATIVE_STALLED, NATIVE_NOT_READY)

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
 * The collectors and channel are always cleaned up in [finally].
 * [onError] is called for non-fatal user-facing messages: a start failure, and every
 * controller note for this capture (#1853 native hand-off to asr4all). A terminal recognition error is
 * returned in [WakeAttemptOutcome.NoTranscript.errorMessage] instead, so the session shows it
 * only when no retry follows.
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
    onWakeEvent: (VoiceInputEvent) -> Unit = {},
    onCaptureReady: () -> Unit = {},
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
    var noticeJob: Job? = null
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
            // #1853/#1866: notes for this capture (a native hand-off to asr4all) are shown at once. The
            // latest note is replayed, so one posted during startListening is not lost.
            noticeJob = launch(start = CoroutineStart.UNDISPATCHED) {
                voiceInputController.noticesFor(captureSessionId).collect { onError(it) }
            }
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
                    onWakeEvent(event)
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
                    // #1930: the overlay launches only after the mic-ready cue has played.
                    onCaptureReady()
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
                // The hand-off note is posted before the replacement recognizer's events, so it
                // is already in the controller's replay cache here.
                val handedOff = voiceInputController.latestNoticeFor(captureSessionId) != null
                // #1433: when the first session's no-speech window is fully exhausted
                // (in-place refreshes spent and the platform still heard nothing), the
                // command window has closed — a second attempt would only re-wait.
                // Genuine failures (any other error) still use the bounded retry.
                WakeAttemptOutcome.NoTranscript(
                    "stt_recognition_failed",
                    skipRetry = handedOff || terminalEvent.category in NO_RETRY_ERROR_CATEGORIES,
                    errorMessage = terminalEvent.message.takeIf { it.isNotBlank() },
                )
            } else {
                WakeAttemptOutcome.NoTranscript(
                    "stt_stopped_without_result",
                    skipRetry = voiceInputController.latestNoticeFor(captureSessionId) != null,
                )
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
        noticeJob?.cancel()
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
 * [onFinalError] receives the terminal recognition error of the last attempt, once, when the
 * session ends without a transcript; an error that is followed by a retry is not shown.
 *
 * Throws [WakeAttemptCollectionException] from individual attempts.
 * Does NOT convert [CancellationException].
 */
internal suspend fun runWakeCaptureSession(
    onFinalError: (String) -> Unit = {},
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
                if (attempt < 2 && !outcome.skipRetry) continue
                outcome.errorMessage?.let(onFinalError)
                break
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
    routeTranscript: suspend (String) -> Boolean,
    onSessionTerminal: () -> Unit,
    journal: WakeSessionJournal? = null,
    capturePermitted: () -> Boolean = { true },
    onWakeEvent: (VoiceInputEvent) -> Unit = {},
    onCaptureReady: () -> Unit = {},
    isCancelled: () -> Boolean = { false },
): WakeCommandHandoffResult {
    // #1433: a confirmed wake activation must not request live STT capture until the
    // wake detector has completed microphone-resource release.
    wakeWordDetector.stop()

    val sessionJournal = journal ?: WakeSessionJournal(generationId, sessionId)
    sessionJournal.start()
    var completed = false
    var cancellationCategory = "stt_no_final_result"

    try {
        val sessionResult = runWakeCaptureSession(onFinalError = onError) { attempt ->
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
                onWakeEvent = onWakeEvent,
                onCaptureReady = onCaptureReady,
            )
        }

        cancellationCategory = sessionResult.cancellationCategory
        val transcript = sessionResult.transcript

        if (transcript != null && isCancelled()) {
            // #1930: the user closed the overlay after capture; the command must not run.
            cancellationCategory = "wake_overlay_cancelled"
        } else if (transcript != null) {
            // #2081: the overlay took the transcript (handed_off), or it is held for a tap (held_for_tap).
            val handedOff = routeTranscript(transcript)
            sessionJournal.record(
                AcousticEventType.COMMAND_ROUTING_RESULT,
                metadata = { mapOf("outcome" to if (handedOff) "handed_off" else "held_for_tap") },
            )
            // #2081: a held transcript is not a completed command; it may still expire untapped.
            completed = handedOff
            if (!handedOff) cancellationCategory = "held_for_tap"
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

/**
 * #1930: runs one Hey Jandal capture with its overlay. [openOverlay] runs once, after the mic-ready
 * cue, with this capture's token. [WakeOverlayChannel.requestCancel] with that token cancels the
 * handoff; its cancellation path stops the recognizer and re-arms the detector exactly once.
 */
internal suspend fun runWakeOverlayHandoff(
    wakeWordDetector: WakeWordDetector,
    voiceInputController: VoiceInputController,
    cuePlayer: StartListeningCuePlayer,
    generationId: Long,
    sessionId: Long,
    onError: (String) -> Unit,
    routeTranscript: suspend (String) -> Boolean,
    onSessionTerminal: () -> Unit,
    capturePermitted: () -> Boolean,
    openOverlay: (token: Long) -> Unit,
): Unit = coroutineScope {
    val token = WakeOverlayChannel.begin()
    var overlayRequested = false
    val handoff = launch {
        runWakeCommandHandoff(
            wakeWordDetector = wakeWordDetector,
            voiceInputController = voiceInputController,
            cuePlayer = cuePlayer,
            generationId = generationId,
            sessionId = sessionId,
            onError = onError,
            routeTranscript = routeTranscript,
            onSessionTerminal = {
                WakeOverlayChannel.end()
                onSessionTerminal()
            },
            capturePermitted = capturePermitted,
            onWakeEvent = WakeOverlayChannel::onEvent,
            onCaptureReady = {
                if (!overlayRequested) {
                    overlayRequested = true
                    openOverlay(token)
                }
            },
            isCancelled = { WakeOverlayChannel.isCancelRequested(token) },
        )
    }
    val cancelWatcher = launch {
        WakeOverlayChannel.awaitCancelRequest(token)
        handoff.cancel()
    }
    handoff.invokeOnCompletion { cancelWatcher.cancel() }
}
private const val TAG = "KernelAI"
private const val CHANNEL_ID = "kernel_wake_word"
private const val NOTIFICATION_ID = 9_500
private const val LEGACY_WAKE_OVERLAY_CHANNEL_ID = "kernel_wake_overlay"

/** How the Hey Jandal overlay opens after a wake (#2081). */
internal enum class WakeOverlayRoute { DIRECT_ACTIVITY, ASSISTANT_SESSION, TAP_NOTIFICATION }

/**
 * Picks the overlay route for a wake (#2081). The direct start needs an interactive, unlocked device
 * AND a Jandal window already visible: with no visible window the start is a background launch that
 * Android blocks, so the wake takes the same route as a locked device. A locked or backgrounded device
 * uses the assistant session when Jandal holds the assistant role, and otherwise a tap-to-open
 * notification. A transcript the overlay does not take is held for a tap.
 */
internal fun chooseWakeOverlayRoute(
    interactive: Boolean,
    keyguardLocked: Boolean,
    jandalVisible: Boolean,
    assistantSessionAvailable: Boolean,
): WakeOverlayRoute = when {
    interactive && !keyguardLocked && jandalVisible -> WakeOverlayRoute.DIRECT_ACTIVITY
    assistantSessionAvailable -> WakeOverlayRoute.ASSISTANT_SESSION
    else -> WakeOverlayRoute.TAP_NOTIFICATION
}


/** #1502: monitor cadence validated on the Honor BKQ-N49 (well below the measured detection need). */
internal const val HONOR_CAMERA_POLL_INTERVAL_MS = 2_000L

/**
 * #1502: release every Jandal-owned microphone capture so the Honor Camera can record.
 *
 * The suspension latch is set *before* any capture is released: every re-arm path (voice-session
 * event, wake-command handoff terminal, debug resume hook) checks that latch, so none of them can
 * take the microphone back while the release is still in flight. On BKQ-N49 the wake detector is
 * not the only Jandal-owned capture that can block the camera — a session left over from a
 * wake→STT flow holds one too, hence [releaseVoiceCapture].
 *
 * Both releases are attempted even when one of them throws: a single failing release must not
 * leave the other capture holding the microphone. The first failure is re-thrown so the caller can
 * retry the whole release on its next attempt.
 */
internal fun releaseWakeCaptureForCamera(
    suspendLatch: () -> Unit,
    releaseWakeDetector: () -> Unit,
    releaseVoiceCapture: () -> Unit,
) {
    suspendLatch()
    var failure: Exception? = null
    fun attempt(release: () -> Unit) {
        try {
            release()
        } catch (e: Exception) {
            Log.w(TAG, "WakeWordService: releasing a microphone capture failed", e)
            val first = failure
            if (first == null) failure = e else first.addSuppressed(e)
        }
    }
    attempt(releaseWakeDetector)
    attempt(releaseVoiceCapture)
    failure?.let { throw it }
}

/**
 * #1502: release Jandal's captures, then stop the wake service — but only once they were released.
 *
 * Stopping the service cancels the scope the camera monitor runs in, and that monitor owns the
 * only retry cadence for this cleanup. Stopping after a *failed* release therefore destroys the one
 * mechanism that can hand a capture back: on BKQ-N49 a recognizer-held capture would keep blocking
 * the camera while nothing was left to release it. The failure propagates instead, so the monitor
 * stays alive and repeats the whole release — latch, wake detector, voice capture — on its next
 * tick. Only a completed release stops the service.
 */
internal fun releaseWakeCaptureThenStop(
    suspendLatch: () -> Unit,
    releaseWakeDetector: () -> Unit,
    releaseVoiceCapture: () -> Unit,
    stopService: () -> Unit,
) {
    releaseWakeCaptureForCamera(suspendLatch, releaseWakeDetector, releaseVoiceCapture)
    stopService()
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
 * - [VoiceInputEvent.ListeningRequested] (native, before the recognizer exists) or
 *   [VoiceInputEvent.ListeningStarted] → stop the detector (release AudioRecord); #2027
 * - [VoiceInputEvent.ListeningStopped] → re-arm the detector
 *
 * This handles every caller (widget, assistant session, chat) automatically with no
 * explicit coordination required from those callers.
 *
 * On wake word detection:
 * 1. Plays the start-listening cue
 * 2. Starts STT via [VoiceInputController] on [VoiceCaptureMode.AlertCommand]
 * 3. Opens the Hey Jandal overlay (#2081), which takes the transcript through [WakeOverlayChannel]
 *    and shows the same listening overlay as the long-press flow, then routes to ActionsScreen.
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
    /** The user's "Pause Hey Jandal" choice from the notification (#2109); see [WakeUserPause]. */
    private val userPause = WakeUserPause()

    /** True while [handleDetection] owns a live STT session; suppresses the observer's re-arm. */
    @Volatile private var isHandlingDetection = false
    /** Releases and re-arms the mic once per voice session (#2027); see [WakeMicYieldGate]. */
    private val micGate = WakeMicYieldGate(
        releaseMic = {
            Log.i(TAG, "WakeWordService: yielding mic to voice session")
            wakeWordDetector.stop()
        },
        rearmMic = { rearmDetectorLocked() },
        // The wake handoff owns the session and re-arms on its own terminal event.
        rearmAllowed = { !isHandlingDetection },
    )

    /** Serialises voice events and recording-client updates, since both start and stop the detector (#2108). */
    private val micLock = Mutex()

    /** Active microphone clients from [recordingCallback], consumed in order by [recordingClientsJob]. */
    private val recordingClients = Channel<List<MicRecordingClient>>(Channel.UNLIMITED)
    private var recordingClientsJob: Job? = null
    @Volatile private var recordingCallbackRegistered = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            recordingClients.trySend(configs.map { it.toMicClient() })
        }
    }

    /**
     * Pauses wake capture while another app records or Jandal's capture is silenced, and re-arms when that
     * ends (#2108). See [WakeMicContentionGate]. Yields to voice sessions through [micGate] as well.
     */
    private val contentionGate: WakeMicContentionGate = WakeMicContentionGate(
        releaseMic = {
            Log.i(TAG, "WakeWordService: another app is recording — releasing wake mic")
            recordServiceError(WakeCategory.MIC_CONTENDED_BY_OTHER_APP)
            wakeWordDetector.stop()
            refreshNotification()
        },
        rearmMic = { rearmDetectorLocked() },
        // A voice session or wake handoff owns the mic; its own terminal event re-arms the detector.
        rearmAllowed = { !isHandlingDetection && !micGate.voiceSessionActive },
        // The wake detector's own capture and the speech-to-text recorders are Jandal's, not another app's (#2109).
        ownSessionIds = { setOfNotNull(wakeWordDetector.captureSessionId) + OwnMicCaptureSessions.snapshot() },
        clockMs = { SystemClock.elapsedRealtime() },
        scheduleRecheck = { delayMs ->
            serviceScope.launch {
                delay(delayMs)
                micLock.withLock { contentionGate.onRecordingClients(currentMicClients()) }
            }
        },
    )

    /** One-shot guard: verifier model provisioning is requested at most once per service lifetime. */
    @Volatile private var wakeVerifierProvisionRequested = false

    /**
     * #1439: the low-band wake verifier prefers the Whisper tiny.en catalogue model
     * (the only model that recognises the fixed wake phrase). Queue the download
     * through the existing catalogue flow when the files are missing; verification
     * rejects low-band candidates until they arrive.
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
            recordServiceError(WakeCategory.RECORD_AUDIO_PERMISSION_MISSING)
            return START_NOT_STICKY
        }

        val foregroundStarted = tryPromoteToMicrophoneForeground(
            promote = { startForeground(NOTIFICATION_ID, wakeNotification()) },
            onRejected = { error ->
                Log.w(
                    TAG,
                    "WakeWordService: microphone FGS promotion not allowed; " +
                        "will retry when the app is foreground (${error.message})",
                )
                recordServiceError(WakeCategory.MICROPHONE_FGS_START_NOT_ALLOWED)
            },
        )
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // #2109: the notification's Pause and Resume actions arrive here, after the foreground promotion.
        when (intent?.action) {
            ACTION_PAUSE -> {
                pauseByUser()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeByUser()
                return START_STICKY
            }
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
            recordServiceError(WakeCategory.USAGE_ACCESS_MISSING)
            return START_NOT_STICKY
        }

        if (!wakeWordDetector.isAvailable) {
            Log.i(TAG, "WakeWordService: model not yet available (#984) — stopping")
            recordServiceError(WakeCategory.WAKE_MODEL_UNAVAILABLE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Guard: if the detector and collector are already running (re-delivery of a
        // START_STICKY intent or a spurious onResume retry), do not start duplicates.
        if (eventCollectorJob?.isActive == true) {
            Log.d(TAG, "WakeWordService: already running — ignoring duplicate onStartCommand")
            return START_STICKY
        }
        JandalEvents.record("service", "wake_word", "outcome" to LifecycleOutcome.STARTED)

        Log.i(TAG, "WakeWordService: starting wake word detection")
        // #2108: observe other apps' mic clients before the first arm, so a recorder already running at
        // service start keeps the wake detector paused from the beginning.
        registerRecordingCallback()
        startWakeCapture()

        // Automatically yield the AudioRecord whenever another voice session is active.
        eventCollectorJob = serviceScope.launch {
            // #2027: yield on the pre-start ListeningRequested as well as ListeningStarted; re-arm on the
            // ListeningStopped of the session that owns the yield. micLifecycleEvents also carries a
            // replaced native session's close, which the gate's owner check keeps from re-arming.
            voiceInputController.micLifecycleEvents.collect { event ->
                micLock.withLock {
                    micGate.onVoiceEvent(event)
                    isVoiceSessionActive = micGate.voiceSessionActive
                }
            }
        }
        // #2108: step aside while another app records. One consumer keeps callback updates in order.
        recordingClientsJob = serviceScope.launch {
            for (clients in recordingClients) {
                micLock.withLock { contentionGate.onRecordingClients(clients) }
            }
        }

        startHonorCameraMonitor()

        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        JandalEvents.record("service", "wake_word", "outcome" to LifecycleOutcome.STOPPED)
        cameraMonitorJob = null
        unregisterRecordingCallback()
        wakeWordDetector.stop()
        // #2081: a transcript still held when the service dies can no longer be tapped; journal it.
        if (WakeWordHandoff.dropAll() != null) {
            cancelWakeOverlayNotification()
            AcousticJournalBridge.record(
                type = AcousticEventType.COMMAND_ROUTING_RESULT,
                metadata = { mapOf("outcome" to "failed", "category" to "held_expired_undelivered") },
            )
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Refusals and yields reach the acoustic journal (debug) and the support export (#1870). */
    private fun recordServiceError(category: WakeCategory) {
        AcousticJournalBridge.record(
            type = AcousticEventType.SERVICE_ERROR,
            metadata = { mapOf("category" to category.name.lowercase()) },
        )
        JandalEvents.record("service", "wake_error", "category" to category)
    }

    // ── Honor camera coexistence (#1502) ───────────────────────────────────────

    /**
     * #1502: take the microphone, but only once wake capture may legitimately run.
     *
     * Unaffected devices arm straight away. On the affected Honor device the camera monitor owns
     * the first arm: it establishes the current foreground package from usage aggregation and
     * reports it, so a service or process restart while the Honor Camera has already been in front
     * cannot take the microphone first and fix it on a later poll — a camera foreground for longer
     * than any event window appears in no window at all.
     */
    private fun startWakeCapture() {
        if (HonorCameraCoexistence.isAffectedDevice(this)) {
            startHonorCameraMonitor()
        } else {
            requestRearm()
        }
    }

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
                currentForegroundPackage = UsageStatsCurrentForegroundSource(applicationContext),
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
        recordServiceError(WakeCategory.CAMERA_FOREGROUND_YIELD)
        refreshNotification()
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
        Log.i(TAG, "WakeWordService: camera not foreground — arming wake capture")
        micLock.withLock { rearmDetectorLocked() }
    }

    /**
     * #1502: Usage Access was revoked while the monitor was running, so Jandal can no longer yield
     * the microphone to the camera. Release every Jandal-owned capture and then stop: listening
     * must not continue indefinitely without the capability that protects the camera.
     *
     * The stop is conditional on the release completing. A failed release is reported to the
     * monitor, which keeps this service alive and retries the whole release on its next cadence;
     * stopping here instead would cancel that retry along with the monitor and could leave a
     * capture holding the microphone with no one left to release it. Voice settings turns Hey
     * Jandal off and explains what is needed the next time it is opened.
     */
    private fun onHonorCameraCoexistenceUnavailable() {
        Log.w(TAG, "WakeWordService: Usage Access lost — releasing microphone and stopping")
        recordServiceError(WakeCategory.USAGE_ACCESS_REVOKED)
        releaseWakeCaptureThenStop(
            suspendLatch = { isWakeCaptureSuspended = true },
            releaseWakeDetector = { wakeWordDetector.stop() },
            releaseVoiceCapture = { voiceInputController.stopListening() },
            stopService = { stopSelf() },
        )
    }

    // ── Detection handoff ──────────────────────────────────────────────────────

    private fun handleDetection(generationId: Long, sessionId: Long) {
        serviceScope.launch {
            isHandlingDetection = true
            try {
                runWakeOverlayHandoff(
                    wakeWordDetector = wakeWordDetector,
                    voiceInputController = voiceInputController,
                    cuePlayer = cuePlayer,
                    generationId = generationId,
                    sessionId = sessionId,
                    onError = { msg -> if (msg.isNotBlank()) showWakeWordError(msg) },
                    routeTranscript = ::routeTranscript,
                    onSessionTerminal = {
                        isHandlingDetection = false
                        if (WakeWordHandoff.heldTranscript?.wakeToken != WakeOverlayChannel.currentToken()) {
                            cancelWakeOverlayNotification()
                        }
                        requestRearm()
                    },
                    // #1502: a camera-driven yield must not be undone by the session's retry.
                    capturePermitted = { !isWakeCaptureSuspended },
                    openOverlay = ::showWakeOverlay,
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

    /**
     * #1930: opens the Hey Jandal overlay once the mic-ready cue has played. [token] names the live
     * capture, so the overlay's cancel applies only to it. A failed launch leaves the capture running.
     * #2081: without an interactive, unlocked device and a visible Jandal window, it opens through the
     * assistant session when Jandal is the assistant; otherwise through a tap-to-open notification.
     */
    private fun showWakeOverlay(token: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val route = chooseWakeOverlayRoute(
            interactive = pm.isInteractive,
            keyguardLocked = km.isKeyguardLocked,
            jandalVisible = JandalActivityVisibility.isVisible,
            assistantSessionAvailable = JandalVoiceInteractionService.isActive,
        )
        Log.i(TAG, "WakeWordService: wake overlay route=$route token=$token")
        when (route) {
            WakeOverlayRoute.DIRECT_ACTIVITY -> try {
                startActivity(wakeOverlayIntent(token, held = false))
            } catch (e: Exception) {
                // Not fatal: the transcript is held for a tap if the overlay does not open (#2081).
                Log.w(TAG, "WakeWordService: could not open the wake overlay", e)
            }
            WakeOverlayRoute.ASSISTANT_SESSION -> {
                if (!JandalVoiceInteractionService.showWakeSession(token)) {
                    postWakeOverlayNotification(nm = nm, token = token, held = false)
                }
            }
            WakeOverlayRoute.TAP_NOTIFICATION -> postWakeOverlayNotification(nm = nm, token = token, held = false)
        }
    }

    /** The overlay activity for capture [token]; [held] delivers a transcript the overlay could not take (#2081). */
    private fun wakeOverlayIntent(token: Long, held: Boolean): Intent =
        Intent(this, VoiceCommandActivity::class.java).apply {
            putExtra(EXTRA_WAKE_OVERLAY, true)
            putExtra(EXTRA_WAKE_TOKEN, token)
            putExtra(EXTRA_WAKE_HELD, held)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Posts the overlay as a tap-to-open notification (#2081). A tap is user-initiated, so the start is
     * allowed with the screen off or locked. The channel is silent: the notification appears while the
     * user is speaking.
     */
    private fun postWakeOverlayNotification(nm: NotificationManager, token: Long, held: Boolean) {
        nm.deleteNotificationChannel(LEGACY_WAKE_OVERLAY_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(
                WAKE_OVERLAY_CHANNEL_ID,
                "Hey Jandal voice overlay",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Opens the Hey Jandal voice overlay after a wake word"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val pending = PendingIntent.getActivity(
            this,
            token.toInt(),
            wakeOverlayIntent(token, held),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            WAKE_OVERLAY_NOTIFICATION_ID,
            Notification.Builder(this, WAKE_OVERLAY_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Hey Jandal")
                .setContentText("Tap to continue")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(WAKE_OVERLAY_NOTIFICATION_TIMEOUT_MS)
                .build(),
        )
    }

    /** #2081: the overlay is up, or the session ended with nothing held, so its notification goes. */
    private fun cancelWakeOverlayNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(WAKE_OVERLAY_NOTIFICATION_ID)
    }

    /**
     * #2081: a held transcript lives as long as its notification. If nothing taps it in time it is
     * dropped and journalled as undelivered, so no command is lost silently. The tap path checks the
     * same window against the elapsed-realtime clock, so a late timer cannot deliver a stale transcript.
     */
    private fun scheduleHeldExpiry(token: Long) {
        serviceScope.launch {
            delay(WAKE_OVERLAY_NOTIFICATION_TIMEOUT_MS)
            if (WakeWordHandoff.expireHeld(token) != null) {
                cancelWakeOverlayNotification()
                Log.w(TAG, "WakeWordService: held wake command expired without a tap (token=$token)")
                AcousticJournalBridge.record(
                    type = AcousticEventType.COMMAND_ROUTING_RESULT,
                    metadata = { mapOf("outcome" to "failed", "category" to "held_expired_undelivered") },
                )
            }
        }
    }


    // ── Other apps' microphone use (#2108) ─────────────────────────────────────

    /** Registers the recording callback and seeds the contention state from the current clients. */
    private fun registerRecordingCallback() {
        if (recordingCallbackRegistered) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
        recordingCallbackRegistered = true
        // Synchronous: the detector is not armed yet, so this only sets the initial pause state.
        contentionGate.onRecordingClients(
            audioManager.activeRecordingConfigurations.map { it.toMicClient() },
        )
    }

    private fun unregisterRecordingCallback() {
        recordingClientsJob?.cancel()
        recordingClientsJob = null
        if (!recordingCallbackRegistered) return
        recordingCallbackRegistered = false
        getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback)
    }

    private fun AudioRecordingConfiguration.toMicClient() = MicRecordingClient(
        sessionId = clientAudioSessionId,
        source = clientAudioSource,
        silenced = isClientSilenced,
    )

    /** Active microphone clients now, for a re-sync outside the recording callback (#2109). */
    private fun currentMicClients(): List<MicRecordingClient> =
        getSystemService(AudioManager::class.java)?.activeRecordingConfigurations?.map { it.toMicClient() }.orEmpty()

    /** Re-arms the detector from a caller outside [micLock]. The re-arm itself checks every condition (#2109). */
    private fun requestRearm() {
        serviceScope.launch { micLock.withLock { rearmDetectorLocked() } }
    }

    /** Releases the detector from a caller outside [micLock], so a start cannot race the stop (#2109). */
    private fun requestDetectorRelease() {
        serviceScope.launch { micLock.withLock { wakeWordDetector.stop() } }
    }

    /** The notification's Pause action: release the mic until the user resumes (#2109). */
    private fun pauseByUser() {
        if (!userPause.pause()) return
        Log.i(TAG, "WakeWordService: paused by user — releasing wake mic")
        requestDetectorRelease()
        refreshNotification()
    }

    /** The notification's Resume action: clear the user pause and re-arm (#2109). */
    private fun resumeByUser() {
        if (!userPause.resume()) return
        Log.i(TAG, "WakeWordService: resumed by user — re-arming wake word detection")
        refreshNotification()
        requestRearm()
    }

    /**
     * Re-arms [wakeWordDetector] with the standard callbacks. The caller holds [micLock]: the user pause,
     * the contention gate and the detector change only under that lock, so the checks below still hold when
     * `start()` runs (#2109). Callers outside the lock use [requestRearm].
     */
    private fun rearmDetectorLocked() {
        // #2109: the user paused Hey Jandal from the notification. Only its Resume action re-arms.
        if (userPause.paused) {
            Log.i(TAG, "WakeWordService: paused by user — not re-arming")
            return
        }
        // #2108: another app is recording (or our capture is silenced); WakeMicContentionGate re-arms.
        // Re-read the clients first, so a missed callback cannot keep wake paused (#2109). If the gate
        // unpauses here it re-arms through rearmMic, so this path returns without arming a second time.
        if (contentionGate.paused) {
            Log.i(TAG, "WakeWordService: another app is recording — re-syncing before re-arm")
            contentionGate.onRecordingClients(currentMicClients())
            return
        }
        // #1502: a camera-driven suspension is a latch — no re-arm path may take the microphone
        // back while the Honor Camera needs it. On top of the latch, ask whether the camera owns
        // the foreground right now: that covers a session ending between the camera appearing and
        // the monitor's next poll, and a restart that lost the latch while the camera was already
        // in front (see HonorCameraCoexistence.shouldWithholdWakeCapture).
        if (isWakeCaptureSuspended || HonorCameraCoexistence.shouldWithholdWakeCapture(this)) {
            Log.i(TAG, "WakeWordService: wake capture suspended — not re-arming")
            return
        }
        ensureWakeVerifierModels()
        if (!wakeWordDetector.isAvailable) {
            recordServiceError(WakeCategory.WAKE_MODEL_UNAVAILABLE)
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WakeWordService: RECORD_AUDIO not granted — not re-arming detector")
            recordServiceError(WakeCategory.RECORD_AUDIO_PERMISSION_MISSING)
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
        refreshNotification()
    }

    /**
     * Hands the final transcript to the overlay attached for this capture (#2081). Returns true when the
     * overlay took it. Otherwise the transcript is held for a tap on the overlay notification (returns false)
     * and expires with a journal entry if untapped, so a command is never dropped silently.
     */
    private suspend fun routeTranscript(transcript: String): Boolean {
        val token = WakeOverlayChannel.currentToken()
        return when (val result = handOffWakeTranscript(transcript, token, clock = SystemClock::elapsedRealtime)) {
            WakeTranscriptHandoffResult.Consumed -> {
                WakeOverlayChannel.markRouted()
                cancelWakeOverlayNotification()
                true
            }
            is WakeTranscriptHandoffResult.Held -> {
                Log.w(TAG, "WakeWordService: overlay did not take the transcript (${result.reason}); held for a tap (token=$token)")
                if (result.displaced != null) {
                    AcousticJournalBridge.record(
                        type = AcousticEventType.COMMAND_ROUTING_RESULT,
                        metadata = { mapOf("outcome" to "failed", "category" to "held_replaced_undelivered") },
                    )
                }
                WakeOverlayChannel.end()
                postWakeOverlayNotification(
                    nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager,
                    token = token,
                    held = true,
                )
                scheduleHeldExpiry(token)
                false
            }
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
        // #2109: one action, Pause while listening and Resume after the user paused.
        val paused = userPause.paused
        val toggleIntent = PendingIntent.getService(
            this,
            if (paused) 2 else 1,
            Intent(this, WakeWordService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            ),
            if (paused) "Resume" else "Pause",
            toggleIntent,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Jandal")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .addAction(toggleAction)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** The notification for the current state; also records it as the shown text (#2109). */
    private fun wakeNotification(): Notification {
        val text = currentWakeText()
        notificationText = text
        return buildNotification(text)
    }

    private fun currentWakeText(): String =
        wakeNotificationText(userPause.paused, isWakeCaptureSuspended, contentionGate.paused)

    /**
     * #1502, #2109: keep the ongoing notification truthful about why wake is or is not listening. No-op when
     * the text is unchanged, so repeated re-arms do not churn the notification. Each state has its own text,
     * so the text is enough to detect a change, including the Pause/Resume action.
     */
    private fun refreshNotification() {
        updateNotification(currentWakeText())
    }

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
                svc.requestDetectorRelease()
            }
        }

        /**
         * Re-arm wake word detection after the STT session ends.
         * No-op (safe) if the service is not running.
         */
        fun resume(context: Context) {
            instance?.get()?.let { svc ->
                Log.i("KernelAI", "WakeWordService: resuming wake word detection")
                svc.requestRearm()
            }
        }
    }
}

internal fun VoiceInputEvent.isWakeSessionEvent(captureSessionId: Long): Boolean =
    mode == VoiceCaptureMode.AlertCommand && this.captureSessionId == captureSessionId
