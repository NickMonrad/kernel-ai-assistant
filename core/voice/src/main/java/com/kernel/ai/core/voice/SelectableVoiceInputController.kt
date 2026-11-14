package com.kernel.ai.core.voice

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.withContext

private const val TAG = "SelectableVoiceInput"

internal const val LIVE_CHECK_SETTLE_MS = 400L

internal const val AUTO_STT_UNAVAILABLE_MESSAGE =
    "Voice input is still getting ready. The offline speech model is downloading, so try again in a minute."

/** Shown when Android native fails and the request is handled by Zipformer for this session only. */
internal const val ZIPFORMER_FALLBACK_NOTICE =
    "Android speech didn't respond. Used Zipformer for this request; your engine setting is unchanged."

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
 * watchdog expired with no partial or transcript ([NATIVE_STALLED]). Recognizer-reported
 * errors (no match, speech timeout, network) are surfaced to the user instead, because a
 * second recognizer would only re-wait on the same silence. Errors after speech progress are
 * never handed off.
 */
internal fun isZipformerHandoffEligible(category: String?, hadSpeechProgress: Boolean): Boolean =
    !hadSpeechProgress && (category == NATIVE_NOT_READY || category == NATIVE_STALLED)

/** Native session facts needed to decide a hand-off. Only touched on the routing pipeline. */
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

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(sherpaOnnxVoiceInputController)

    // Routing state. Only touched on [pipelineScope]'s dispatcher (Main), which also runs
    // startListening's body, so no extra synchronisation is needed.
    private var nativeFacts: NativeSessionFacts? = null
    private var handoff: ZipformerHandoff? = null

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outbound = MutableSharedFlow<VoiceInputEvent>(extraBufferCapacity = 64)
    private val noticeFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * One pipeline for all collectors. Collecting per subscriber would run the Zipformer
     * hand-off once per collector and could start two recognizers.
     */
    override val events: Flow<VoiceInputEvent> = outbound.asSharedFlow()
    override val notices: Flow<String> = noticeFlow.asSharedFlow()

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
            handoff = null
            val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
            when (voiceInputPreferences.selectedEngine.first()) {
                VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback)
                VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback)
                else -> startSherpa(mode) // All Sherpa families.
            }
        }

    private suspend fun startAuto(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
        val zipformerReady = sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER)
        if (zipformerReady && !allowOnlineFallback) {
            // Zipformer wins regardless of native support; skip the native availability check.
            Log.i(TAG, "Auto STT route=${AutoSttRoute.Zipformer} mode=$mode allowOnlineFallback=false")
            return startSherpa(mode)
        }
        // Use the cached locale check when there is one; only run the slower live check for
        // interactive captures that have no cached answer yet.
        var ranLiveCheck = false
        val availability = withContext(Dispatchers.Main.immediate) {
            val cached = androidNativeRecognitionSupport.getCaptureAvailability()
            if (
                cached.localeStatus == AndroidNativeRecognitionLocaleStatus.Unknown &&
                !shouldUseCachedCaptureAvailability(mode)
            ) {
                ranLiveCheck = true
                androidNativeRecognitionSupport.getAvailability()
            } else {
                cached
            }
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
     * Starts Android native. If it cannot start at all (no recognizer, missing language pack,
     * recognizer bind failure) and Zipformer is downloaded, the capture starts on Zipformer.
     * The user's saved engine is not changed.
     */
    private suspend fun startNative(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
        activate(nativeAndroidVoiceInputController)
        val nativeResult = nativeAndroidVoiceInputController.startListening(mode, allowOnlineFallback)
        if (nativeResult !is VoiceInputStartResult.Unavailable) return nativeResult
        if (!sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER)) return nativeResult

        Log.w(TAG, "Android native unavailable at start; using Zipformer: ${nativeResult.message}")
        recordZipformerFallback(reason = "native_start_unavailable", mode = mode)
        noticeFlow.tryEmit(ZIPFORMER_FALLBACK_NOTICE)
        val zipformerResult = startSherpa(mode)
        return if (zipformerResult is VoiceInputStartResult.Started) zipformerResult else nativeResult
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

    /** Routes one controller event. Runs on the pipeline; never called concurrently with itself. */
    private suspend fun route(controller: VoiceInputController, event: VoiceInputEvent) {
        // Events from a controller that is no longer active (e.g. native after a hand-off) are stale.
        if (controller !== activeController.value) return

        if (controller === nativeAndroidVoiceInputController) {
            trackNative(event)
            val facts = nativeFacts
            if (
                event is VoiceInputEvent.Error &&
                facts != null &&
                isZipformerHandoffEligible(event.category, facts.speechProgress) &&
                sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER) &&
                handOffToZipformer(event, reason = event.category.orEmpty())
            ) {
                // The native error is not surfaced: the capture continues on Zipformer.
                return
            }
        }

        val current = handoff
        if (current != null && controller === sherpaOnnxVoiceInputController) {
            if (event.captureSessionId == current.zipformerSessionId) {
                // The native cue already played for this capture; do not replay it.
                if (event is VoiceInputEvent.ListeningStarted && current.readinessAnnounced) return
                outbound.emit(event.withCaptureSessionId(current.nativeSessionId))
                return
            }
        }
        outbound.emit(event)
    }

    private fun trackNative(event: VoiceInputEvent) {
        val previous = nativeFacts?.takeIf { it.sessionId == event.captureSessionId }
            ?: NativeSessionFacts(sessionId = event.captureSessionId)
        nativeFacts = when (event) {
            is VoiceInputEvent.ListeningStarted -> previous.copy(readinessAnnounced = true)
            is VoiceInputEvent.PartialTranscript, is VoiceInputEvent.Transcript ->
                previous.copy(speechProgress = true)
            else -> previous
        }
    }

    /**
     * Replaces the failed native capture with Zipformer for the same mode. Returns false when
     * Zipformer cannot start, so the caller surfaces the original native error.
     */
    private suspend fun handOffToZipformer(error: VoiceInputEvent.Error, reason: String): Boolean {
        val started = startSherpa(error.mode) as? VoiceInputStartResult.Started ?: run {
            Log.w(TAG, "Zipformer hand-off failed to start; surfacing native error: ${error.message}")
            return false
        }
        Log.w(TAG, "Android native failed ($reason); handing capture to Zipformer for this session")
        handoff = ZipformerHandoff(
            nativeSessionId = error.captureSessionId,
            zipformerSessionId = started.captureSessionId,
            readinessAnnounced = nativeFacts?.readinessAnnounced == true,
        )
        recordZipformerFallback(reason = reason, mode = error.mode)
        noticeFlow.tryEmit(ZIPFORMER_FALLBACK_NOTICE)
        return true
    }

    private fun recordZipformerFallback(reason: String, mode: VoiceCaptureMode) {
        AcousticJournalBridge.record(
            type = AcousticEventType.STT_ZIPFORMER_FALLBACK,
            metadata = { mapOf("reason" to reason, "mode" to mode.name) },
        )
    }

    override fun stopListening() {
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
