package com.kernel.ai.core.voice

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Notice when Android native could not start and Zipformer took the request. */
internal const val ZIPFORMER_START_NOTICE =
    "Android speech isn't available on this phone right now. Used Zipformer for this request."

/** Notice when Android native stopped responding and Zipformer took over the request. */
internal const val ZIPFORMER_STALL_NOTICE =
    "Android speech stopped responding. Used Zipformer for this request."

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
 * watchdog expired with no speech progress ([NATIVE_STALLED]). Recognizer-reported errors
 * (no match, speech timeout, network) are surfaced instead, because a second recognizer would
 * only re-wait on the same silence. Once speech was detected, or any partial or transcript
 * arrived, the native error is never handed off, so the user's words are not silently replaced.
 */
internal fun isZipformerHandoffEligible(category: String?, hadSpeechProgress: Boolean): Boolean =
    !hadSpeechProgress && (category == NATIVE_NOT_READY || category == NATIVE_STALLED)

/** Native session facts needed to decide a hand-off. Touched only on Main. */
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
 * Concurrency model: [startListening] and the hand-off run under [handoffMutex]. [stopListening]
 * is not a suspend function, so it cannot take the mutex; it bumps [generation] instead. A
 * hand-off checks [generation] after each suspension point and undoes its own Zipformer start
 * if the capture was stopped or superseded in the meantime. All routing state
 * ([generation], [nativeFacts], [handoff], [captureOpen]) is touched only on Main.
 */
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(sherpaOnnxVoiceInputController)

    private var generation = 0L
    private var captureOpen = false
    private var nativeFacts: NativeSessionFacts? = null
    private var handoff: ZipformerHandoff? = null
    private val handoffMutex = Mutex()

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outbound = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val noticeFlow = MutableSharedFlow<VoiceNotice>(extraBufferCapacity = 8)

    /**
     * One pipeline for all collectors. Collecting per subscriber would run the Zipformer
     * hand-off once per collector and could start two recognizers.
     */
    override val events: Flow<VoiceInputEvent> = outbound.asSharedFlow()
    override val notices: Flow<VoiceNotice> = noticeFlow.asSharedFlow()

    init {
        pipelineScope.launch {
            merge(
                nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
                sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
            ).collect { (controller, event) -> route(controller, event) }
        }
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        withContext(Dispatchers.Main.immediate) {
            handoffMutex.withLock {
                generation++
                handoff = null
                nativeFacts = null
                val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
                val result = when (voiceInputPreferences.selectedEngine.first()) {
                    VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback)
                    VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback)
                    else -> startSherpa(mode) // All Sherpa families.
                }
                captureOpen = result is VoiceInputStartResult.Started
                result
            }
        }

    private suspend fun startAuto(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
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
                startNative(mode, allowOnlineFallback)
            }
            AutoSttRoute.Zipformer -> startSherpa(mode)
            AutoSttRoute.Unavailable -> VoiceInputStartResult.Unavailable(AUTO_STT_UNAVAILABLE_MESSAGE)
        }
    }

    /**
     * Starts Android native. If it cannot start at all and Zipformer is downloaded, the capture
     * starts on Zipformer for this request. The saved engine setting is not changed.
     */
    private suspend fun startNative(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
        activate(nativeAndroidVoiceInputController)
        val nativeResult = nativeAndroidVoiceInputController.startListening(mode, allowOnlineFallback)
        if (nativeResult !is VoiceInputStartResult.Unavailable) return nativeResult
        if (!zipformerDownloaded()) return nativeResult

        Log.w(TAG, "Android native unavailable at start; trying Zipformer: ${nativeResult.message}")
        val gen = generation
        val zipformer = startZipformerSession(mode, gen) ?: return nativeResult
        activeController.value = sherpaOnnxVoiceInputController
        recordZipformerFallback(reason = NATIVE_NOT_READY, mode = mode)
        postNotice(VoiceNotice(zipformer.captureSessionId, ZIPFORMER_START_NOTICE))
        return zipformer
    }

    /**
     * Starts Zipformer without activating it. Returns null when the capture was stopped or
     * superseded while it was starting; in that case any started session is stopped again.
     * Callers must hold [handoffMutex].
     */
    private suspend fun startZipformerSession(mode: VoiceCaptureMode, gen: Long): VoiceInputStartResult.Started? {
        val result = try {
            sherpaOnnxVoiceInputController.startListening(mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Zipformer failed to start", e)
            return null
        }
        if (result !is VoiceInputStartResult.Started) return null
        if (gen != generation) {
            // Stop was called while Zipformer was loading. Its activeJob now exists, so stop it.
            sherpaOnnxVoiceInputController.stopListening()
            return null
        }
        return result
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
     * Routes one controller event. Each event is isolated: an exception here is logged and
     * the next event is still routed, so one failure cannot stop voice input for the process.
     */
    private suspend fun route(controller: VoiceInputController, event: VoiceInputEvent) {
        try {
            routeEvent(controller, event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route voice event ${event::class.simpleName}", e)
        }
    }

    private suspend fun routeEvent(controller: VoiceInputController, event: VoiceInputEvent) {
        // Events from a controller that is no longer active (native after a hand-off) are stale.
        if (controller !== activeController.value) return

        if (controller === nativeAndroidVoiceInputController) {
            trackNative(event)
            val facts = nativeFacts
            if (
                event is VoiceInputEvent.Error &&
                captureOpen &&
                facts != null &&
                isZipformerHandoffEligible(event.category, facts.speechProgress) &&
                handOffToZipformer(event)
            ) {
                // The native error is not surfaced: the capture continues on Zipformer.
                return
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

    private fun trackNative(event: VoiceInputEvent) {
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

    /**
     * Replaces the failed native capture with Zipformer for the same mode.
     *
     * Returns true when the native error must not be surfaced: the capture moved to Zipformer,
     * or the user stopped or restarted during the hand-off. Returns false when Zipformer could
     * not start, so the caller surfaces the native error and native stays active.
     */
    private suspend fun handOffToZipformer(error: VoiceInputEvent.Error): Boolean {
        val gen = generation
        val nativeId = error.captureSessionId
        val reason = error.category.orEmpty()
        // Tear native down first, then let its shared on-device service settle.
        nativeAndroidVoiceInputController.stopListening()
        delay(NATIVE_TEARDOWN_SETTLE_MS)
        return handoffMutex.withLock {
            if (gen != generation) return@withLock true
            if (!zipformerDownloaded()) return@withLock false

            Log.w(TAG, "Android native failed ($reason); handing capture to Zipformer for this session")
            val zipformer = startZipformerSession(error.mode, gen)
            if (zipformer == null) {
                if (gen != generation) return@withLock true
                Log.w(TAG, "Zipformer hand-off failed to start; surfacing native error: ${error.message}")
                return@withLock false
            }
            activeController.value = sherpaOnnxVoiceInputController
            handoff = ZipformerHandoff(
                nativeSessionId = nativeId,
                zipformerSessionId = zipformer.captureSessionId,
                readinessAnnounced = nativeFacts?.readinessAnnounced == true,
            )
            recordZipformerFallback(reason = reason, mode = error.mode)
            val message = if (error.category == NATIVE_STALLED) ZIPFORMER_STALL_NOTICE else ZIPFORMER_START_NOTICE
            postNotice(VoiceNotice(nativeId, message))
            true
        }
    }

    /**
     * Emits [notice] on Main after the current task yields. A start-time notice is produced before
     * `startListening` returns, so the caller has not yet recorded the session id it would match on.
     */
    private fun postNotice(notice: VoiceNotice) {
        pipelineScope.launch(Dispatchers.Main) { noticeFlow.emit(notice) }
    }

    private fun recordZipformerFallback(reason: String, mode: VoiceCaptureMode) {
        AcousticJournalBridge.record(
            type = AcousticEventType.STT_ZIPFORMER_FALLBACK,
            metadata = { mapOf("reason" to reason, "mode" to mode.name) },
        )
    }

    override fun stopListening() {
        generation++
        handoff = null
        captureOpen = false
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
