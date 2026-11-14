package com.kernel.ai.core.voice

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SelectableVoiceInput"

internal const val LIVE_CHECK_SETTLE_MS = 400L

/**
 * Pause after the native recognizer is stopped and before Zipformer starts. The on-device
 * recognizer service is shared; starting a second client straight after teardown has produced
 * ERROR_SERVER_DISCONNECTED on the S23 Ultra (see [LIVE_CHECK_SETTLE_MS]).
 */
internal const val NATIVE_TEARDOWN_SETTLE_MS = 400L

internal const val AUTO_STT_UNAVAILABLE_MESSAGE =
    "Voice input is still getting ready. The offline speech model is downloading, so try again in a minute."

/** Notice when Android native could not start or never became ready, and Zipformer took the request. */
internal const val ZIPFORMER_START_NOTICE =
    "Android speech isn't available on this phone right now. Using Zipformer for this request."

/**
 * Notice when Android native stopped responding before any speech progress and Zipformer took
 * over. The user may have spoken and not been heard, and the cue is not replayed, so ask them
 * to repeat.
 */
internal const val ZIPFORMER_STALL_NOTICE =
    "Android speech stopped responding, so Zipformer is listening now. Please say that again."

/** Which recognizer [VoiceInputEngine.Auto] uses for one capture session. */
internal enum class AutoSttRoute {
    /** Android native, starting on-device. Platform fallback follows the user's setting. */
    Native,

    /** Offline Sherpa-ONNX Zipformer. */
    Zipformer,

    /** No usable recognizer yet (Zipformer still downloading, no on-device support). */
    Unavailable,
}

/**
 * Picks the recognizer for [VoiceInputEngine.Auto].
 *
 * Without the online fallback, the offline Zipformer model is preferred: on the S23 Ultra,
 * Android's on-device recognizer returned empty results for clearly audible speech that
 * Zipformer transcribed (#1008). On-device Android recognition is only used while Zipformer
 * is still downloading.
 *
 * With the online fallback allowed, Android native is preferred: it starts on-device and its
 * existing fallback retries empty or failed on-device results on the platform service.
 */
internal fun resolveAutoSttRoute(
    nativeOnDeviceReady: Boolean,
    zipformerReady: Boolean,
    allowOnlineFallback: Boolean,
): AutoSttRoute = when {
    allowOnlineFallback && nativeOnDeviceReady -> AutoSttRoute.Native
    zipformerReady -> AutoSttRoute.Zipformer
    nativeOnDeviceReady -> AutoSttRoute.Native
    else -> AutoSttRoute.Unavailable
}

/**
 * Whether a terminal Android native error may be handed to Zipformer for the same capture.
 *
 * Only two failures qualify: the recognizer never became ready ([NATIVE_NOT_READY]) and the
 * watchdog expired before any speech progress ([NATIVE_STALLED]; the native controller leaves
 * stalls after speech uncategorised). Recognizer-reported errors (no match, speech timeout,
 * network) are surfaced instead, because a second recognizer would only re-wait on the same
 * silence. [hadSpeechProgress] is the router's own record of SpeechDetected, partials and
 * transcripts for the session; any of them blocks the hand-off, so the user's words are never
 * silently replaced.
 */
internal fun isZipformerHandoffEligible(category: String?, hadSpeechProgress: Boolean): Boolean =
    !hadSpeechProgress && (category == NATIVE_NOT_READY || category == NATIVE_STALLED)

/** Native session facts needed to decide a hand-off. Main thread only. */
private data class NativeSessionFacts(
    val sessionId: Long,
    val readinessAnnounced: Boolean = false,
    val speechProgress: Boolean = false,
)

/**
 * A Zipformer session that replaced a native session mid-capture. Its events are re-stamped
 * with [nativeSessionId] so consumers keep matching the session they started.
 */
private data class ZipformerHandoff(
    val nativeSessionId: Long,
    val zipformerSessionId: Long,
    /** Native readiness already reached consumers, so the cue must not replay for Zipformer. */
    val readinessAnnounced: Boolean,
)

private fun VoiceInputEvent.withCaptureSessionId(id: Long): VoiceInputEvent = when (this) {
    is VoiceInputEvent.ListeningStarted -> copy(captureSessionId = id)
    is VoiceInputEvent.SpeechDetected -> copy(captureSessionId = id)
    is VoiceInputEvent.PartialTranscript -> copy(captureSessionId = id)
    is VoiceInputEvent.Transcript -> copy(captureSessionId = id)
    is VoiceInputEvent.Error -> copy(captureSessionId = id)
    is VoiceInputEvent.ListeningStopped -> copy(captureSessionId = id)
}

/**
 * Routes voice capture between Android native and Sherpa-ONNX Zipformer.
 *
 * Concurrency model:
 * - Routing state ([activeController], [openGeneration], [expectedNativeSessionId],
 *   [nativeFacts], [handoff]) is confined to the Main thread. [startListening] switches to
 *   Main, and the event pipeline and every Zipformer start job run on [pipelineScope] (Main).
 * - [stopListening] may be called from any thread. It only bumps the atomic [generation],
 *   cancels the atomic [pendingZipformerStart] job, and stops both recognizers, which are
 *   thread-safe themselves.
 * - Each capture owns the generation it was started with. A stop or a newer start bumps
 *   [generation], so a hand-off for an older capture can no longer commit.
 * - Every Zipformer start made on the user's behalf (start-time fallback, mid-session hand-off)
 *   runs as a cancellable job. Cancelling it while the model loads makes
 *   [SherpaOnnxVoiceInputController.startListening] give up before it takes audio focus or
 *   opens the microphone.
 * - Starts and hand-offs are serialised by [startMutex].
 */
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(sherpaOnnxVoiceInputController)

    private val generation = AtomicLong()
    private val pendingZipformerStart = AtomicReference<Job?>(null)
    private val startMutex = Mutex()

    /** Generation of the capture that is currently open, or -1. Main only. */
    private var openGeneration = -1L
    /** Native session id returned by the current start. Main only. */
    private var expectedNativeSessionId: Long? = null
    private var nativeFacts: NativeSessionFacts? = null
    private var handoff: ZipformerHandoff? = null

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outbound = voiceEventFlow()
    private val noticeFlow = MutableSharedFlow<VoiceNotice>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One pipeline for all collectors. Collecting per subscriber would run the Zipformer
     * hand-off once per collector and could start two recognizers.
     */
    override val events: Flow<VoiceInputEvent> = outbound.asSharedFlow()
    override val notices: SharedFlow<VoiceNotice> = noticeFlow.asSharedFlow()

    init {
        pipelineScope.launch {
            merge(
                nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
                sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
            ).collect { (controller, event) -> route(controller, event) }
        }
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        // Supersede any hand-off in flight before waiting for the start lock.
        val gen = generation.incrementAndGet()
        pendingZipformerStart.getAndSet(null)?.cancel()
        return withContext(Dispatchers.Main.immediate) {
            startMutex.withLock {
                openGeneration = -1L
                handoff = null
                nativeFacts = null
                expectedNativeSessionId = null
                val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
                val result = when (voiceInputPreferences.selectedEngine.first()) {
                    VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback, gen)
                    VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback, gen)
                    else -> startSherpa(mode) // All Sherpa families.
                }
                if (result is VoiceInputStartResult.Started) openGeneration = gen
                result
            }
        }
    }

    private suspend fun startAuto(
        mode: VoiceCaptureMode,
        allowOnlineFallback: Boolean,
        gen: Long,
    ): VoiceInputStartResult {
        val zipformerReady = zipformerDownloaded()
        if (zipformerReady && !allowOnlineFallback) {
            // Zipformer wins regardless of native support; skip the native availability check.
            Log.i(TAG, "Auto STT route=${AutoSttRoute.Zipformer} mode=$mode allowOnlineFallback=false")
            return startSherpa(mode)
        }
        // Use the cached locale check when there is one; only run the slower live check for
        // interactive captures that have no cached answer yet.
        var ranLiveCheck = false
        val cached = androidNativeRecognitionSupport.getCaptureAvailability()
        val availability = if (
            cached.localeStatus == AndroidNativeRecognitionLocaleStatus.Unknown &&
            !shouldUseCachedCaptureAvailability(mode)
        ) {
            ranLiveCheck = true
            androidNativeRecognitionSupport.getAvailability()
        } else {
            cached
        }
        val nativeOnDeviceReady = availability.blockingReason == null &&
            availability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
        val route = resolveAutoSttRoute(
            nativeOnDeviceReady = nativeOnDeviceReady,
            zipformerReady = zipformerReady,
            allowOnlineFallback = allowOnlineFallback,
        )
        Log.i(
            TAG,
            "Auto STT route=$route mode=$mode nativeOnDeviceReady=$nativeOnDeviceReady " +
                "zipformerReady=$zipformerReady allowOnlineFallback=$allowOnlineFallback",
        )
        return when (route) {
            AutoSttRoute.Native -> {
                // The live check's throwaway recognizer is torn down asynchronously; starting
                // a session immediately can lose the shared on-device service
                // (ERROR_SERVER_DISCONNECTED on the S23 Ultra), so let it settle first.
                if (ranLiveCheck) delay(LIVE_CHECK_SETTLE_MS)
                startNative(mode, allowOnlineFallback, gen)
            }
            AutoSttRoute.Zipformer -> startSherpa(mode)
            AutoSttRoute.Unavailable -> VoiceInputStartResult.Unavailable(AUTO_STT_UNAVAILABLE_MESSAGE)
        }
    }

    /**
     * Starts Android native. If it cannot start at all and Zipformer is downloaded, the capture
     * starts on Zipformer for this request. The saved engine setting is not changed.
     * Caller holds [startMutex].
     */
    private suspend fun startNative(
        mode: VoiceCaptureMode,
        allowOnlineFallback: Boolean,
        gen: Long,
    ): VoiceInputStartResult {
        activate(nativeAndroidVoiceInputController)
        val nativeResult = nativeAndroidVoiceInputController.startListening(mode, allowOnlineFallback)
        if (nativeResult is VoiceInputStartResult.Started) {
            expectedNativeSessionId = nativeResult.captureSessionId
            nativeFacts = nativeFacts?.takeIf { it.sessionId == nativeResult.captureSessionId }
                ?: NativeSessionFacts(sessionId = nativeResult.captureSessionId)
            return nativeResult
        }
        if (!zipformerDownloaded()) return nativeResult

        Log.w(TAG, "Android native unavailable at start; trying Zipformer: ${(nativeResult as? VoiceInputStartResult.Unavailable)?.message}")
        // Zipformer emits ListeningStarted while it starts, so route its events from now on.
        activeController.value = sherpaOnnxVoiceInputController
        val zipformer = runZipformerStart(gen) {
            val started = sherpaOnnxVoiceInputController.startListening(mode) as? VoiceInputStartResult.Started
            when {
                started == null -> null
                gen != generation.get() -> {
                    sherpaOnnxVoiceInputController.stopListening()
                    null
                }
                else -> started
            }
        }
        if (zipformer != null) {
            recordZipformerFallback(reason = NATIVE_NOT_READY, mode = mode)
            postNotice(VoiceNotice(zipformer.captureSessionId, ZIPFORMER_START_NOTICE))
            return zipformer
        }
        activeController.value = nativeAndroidVoiceInputController
        if (gen != generation.get()) {
            // Stopped or superseded while Zipformer was starting: report a capture that ended
            // at once, as a stop just after a start would, instead of the native failure.
            val stoppedSessionId = VoiceCaptureSessionIds.allocate()
            Log.i(TAG, "Capture stopped during the start-time Zipformer fallback; reporting sessionId=$stoppedSessionId as stopped")
            outbound.tryEmit(VoiceInputEvent.ListeningStopped(mode, stoppedSessionId))
            return VoiceInputStartResult.Started(stoppedSessionId)
        }
        return nativeResult
    }

    private suspend fun startSherpa(mode: VoiceCaptureMode): VoiceInputStartResult {
        activate(sherpaOnnxVoiceInputController)
        return sherpaOnnxVoiceInputController.startListening(mode)
    }

    private fun activate(controller: VoiceInputController) {
        activeController.value = controller
        if (controller !== nativeAndroidVoiceInputController) nativeAndroidVoiceInputController.stopListening()
        if (controller !== sherpaOnnxVoiceInputController) sherpaOnnxVoiceInputController.stopListening()
    }

    private suspend fun zipformerDownloaded(): Boolean =
        sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER)

    /**
     * Runs [block] as the cancellable Zipformer start for the capture with generation [gen].
     * [stopListening] and a newer [startListening] cancel it. Returns null when the capture
     * was stopped or superseded, or when [block] failed or returned null; callers compare
     * [gen] with [generation] to tell these apart.
     */
    private suspend fun runZipformerStart(
        gen: Long,
        block: suspend () -> VoiceInputStartResult.Started?,
    ): VoiceInputStartResult.Started? {
        val job = pipelineScope.async(start = CoroutineStart.LAZY) { block() }
        pendingZipformerStart.set(job)
        return try {
            // Publish first, then re-check: a stop that ran before the publish bumped the
            // generation; a stop after it cancels the job.
            if (gen != generation.get()) {
                Log.i(TAG, "Zipformer start skipped: capture already stopped or superseded")
                job.cancel()
                null
            } else {
                job.await()
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Log.i(TAG, "Zipformer start cancelled: capture stopped or superseded")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Zipformer failed to start", e)
            null
        } finally {
            pendingZipformerStart.compareAndSet(job, null)
        }
    }

    /**
     * Routes one controller event. Each event is isolated: if routing throws, the original
     * event is still delivered, so a terminal Error or ListeningStopped is never dropped.
     */
    private suspend fun route(controller: VoiceInputController, event: VoiceInputEvent) {
        try {
            routeEvent(controller, event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route voice event ${event::class.simpleName}; delivering it unchanged", e)
            outbound.tryEmit(event)
        }
    }

    private suspend fun routeEvent(controller: VoiceInputController, event: VoiceInputEvent) {
        // Events from a controller that is no longer active (native after a hand-off) are stale.
        if (controller !== activeController.value) return

        if (controller === nativeAndroidVoiceInputController) {
            trackNative(event)
            if (event is VoiceInputEvent.Error && isHandoffCandidate(event)) {
                val handled = try {
                    handOffToZipformer(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Zipformer hand-off failed; surfacing the native error", e)
                    false
                }
                // The native error is not surfaced: the capture continues on Zipformer, or the
                // user stopped or restarted during the hand-off.
                if (handled) return
            }
        }

        val current = handoff
        if (current != null && controller === sherpaOnnxVoiceInputController &&
            event.captureSessionId == current.zipformerSessionId
        ) {
            // The native cue already played for this capture; do not replay it.
            if (event is VoiceInputEvent.ListeningStarted && current.readinessAnnounced) return
            outbound.emit(event.withCaptureSessionId(current.nativeSessionId))
            return
        }
        outbound.emit(event)
    }

    /** Records native session facts; events from any other native session are ignored. */
    private fun trackNative(event: VoiceInputEvent) {
        val expected = expectedNativeSessionId
        if (expected != null && event.captureSessionId != expected) return
        val previous = nativeFacts?.takeIf { it.sessionId == event.captureSessionId }
            ?: NativeSessionFacts(sessionId = event.captureSessionId)
        nativeFacts = when (event) {
            is VoiceInputEvent.ListeningStarted -> previous.copy(readinessAnnounced = true)
            is VoiceInputEvent.SpeechDetected,
            is VoiceInputEvent.PartialTranscript,
            is VoiceInputEvent.Transcript,
            -> previous.copy(speechProgress = true)
            else -> previous
        }
    }

    /** The error belongs to the open capture's native session and qualifies for a hand-off. */
    private fun isHandoffCandidate(error: VoiceInputEvent.Error): Boolean {
        val facts = nativeFacts ?: return false
        return openGeneration != -1L &&
            openGeneration == generation.get() &&
            error.captureSessionId == expectedNativeSessionId &&
            facts.sessionId == error.captureSessionId &&
            isZipformerHandoffEligible(error.category, facts.speechProgress)
    }

    /**
     * Replaces the failed native capture with Zipformer for the same mode.
     *
     * Returns true when the native error must not be surfaced: the capture moved to Zipformer,
     * or the user stopped or restarted during the hand-off. Returns false when Zipformer could
     * not start, so the caller surfaces the native error and native stays active.
     */
    private suspend fun handOffToZipformer(error: VoiceInputEvent.Error): Boolean {
        val gen = openGeneration
        val nativeId = error.captureSessionId
        val reason = error.category.orEmpty()
        val readinessAnnounced = nativeFacts?.readinessAnnounced == true
        // Tear native down first, then let its shared on-device service settle.
        nativeAndroidVoiceInputController.stopListening()
        val zipformer = runZipformerStart(gen) {
            delay(NATIVE_TEARDOWN_SETTLE_MS)
            startMutex.withLock {
                if (gen != generation.get() || !zipformerDownloaded()) return@withLock null
                Log.w(TAG, "Android native failed ($reason); handing capture to Zipformer for this session")
                val started = sherpaOnnxVoiceInputController.startListening(error.mode)
                    as? VoiceInputStartResult.Started
                    ?: return@withLock null
                if (gen != generation.get()) {
                    // Stopped after Zipformer opened the microphone; no newer capture can have
                    // started while this job holds the lock.
                    sherpaOnnxVoiceInputController.stopListening()
                    return@withLock null
                }
                // Commit under the lock, before Zipformer's queued events are routed.
                activeController.value = sherpaOnnxVoiceInputController
                handoff = ZipformerHandoff(
                    nativeSessionId = nativeId,
                    zipformerSessionId = started.captureSessionId,
                    readinessAnnounced = readinessAnnounced,
                )
                recordZipformerFallback(reason = reason, mode = error.mode)
                val message = if (error.category == NATIVE_STALLED) ZIPFORMER_STALL_NOTICE else ZIPFORMER_START_NOTICE
                postNotice(VoiceNotice(nativeId, message))
                started
            }
        }
        if (zipformer != null) return true
        if (gen != generation.get()) return true
        Log.w(TAG, "Zipformer hand-off did not start; surfacing native error: ${error.message}")
        return false
    }

    private fun postNotice(notice: VoiceNotice) {
        noticeFlow.tryEmit(notice)
    }

    private fun recordZipformerFallback(reason: String, mode: VoiceCaptureMode) {
        AcousticJournalBridge.record(
            type = AcousticEventType.STT_ZIPFORMER_FALLBACK,
            metadata = { mapOf("reason" to reason, "mode" to mode.name) },
        )
    }

    /** Thread-safe: see the class concurrency notes. */
    override fun stopListening() {
        generation.incrementAndGet()
        pendingZipformerStart.getAndSet(null)?.cancel()
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
    }

    /**
     * Wake-word verification always runs through the dedicated Sherpa wake
     * recognizer ([SherpaOnnxVoiceInputController.transcribeBlocking]),
     * independent of the selected interactive STT engine. The interactive
     * selection and [activeController] are deliberately untouched so the
     * currently selected engine keeps its normal capture behaviour.
     */
    override suspend fun transcribeBlocking(pcm: ShortArray): String? =
        sherpaOnnxVoiceInputController.transcribeBlocking(pcm)
}
