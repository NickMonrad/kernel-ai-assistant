package com.kernel.ai.core.voice

import android.os.SystemClock
import android.util.Log
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.NativeFallbackReason
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SelectableVoiceInput"

internal const val LIVE_CHECK_SETTLE_MS = 400L

/**
 * Pause before opening a local recognizer after a Native capture. Android's framework stop is
 * app-local; Android 15 tears down the remote recognition service asynchronously and exposes no
 * reliable microphone-release signal. This bounded direct-selection settle is mitigation, not
 * proof of remote recorder release.
 */
internal const val NATIVE_TEARDOWN_SETTLE_MS = 400L

/** Bound on waiting for a superseded capture's backend to settle before its admission is retired. */
internal const val BACKEND_TEARDOWN_TIMEOUT_MS = 3000L

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

/** Admission facts for one asr4all session and the start attempt that owns it. */
private data class Asr4allAdmission(
    val seq: Long,
    val epoch: Long,
    val generation: Long,
    var resultSeen: Boolean = false,
    var resultDelivered: Boolean = false,
    var stopped: Boolean = false,
)

private data class CaptureOwnerState(val generation: Long, val sessionId: Long?)
private data class AdmittedCapture(
    val controller: VoiceInputController,
    val backendSessionId: Long,
    val generation: Long,
    val mode: VoiceCaptureMode,
    val teardown: CompletableDeferred<Unit>? = null,
)
private data class BackendCaptureKey(val controller: VoiceInputController, val sessionId: Long)
private data class NativeTeardownSettle(
    val ownerSessionId: Long,
    val settleUntilElapsedMs: Long,
    val settled: CompletableDeferred<Unit>,
)
private data class PendingZipformerStart(val generation: Long, val job: Job)

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
    is VoiceInputEvent.SpeechEnded -> copy(captureSessionId = id)
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
    private val asr4allVoiceInputController: Asr4allVoiceInputController,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController, VoiceCaptureAdmissionController {

    private val activeController = MutableStateFlow<VoiceInputController>(sherpaOnnxVoiceInputController)

    private val generation = AtomicLong()
    private val captureOwnerLock = Any()
    private var generationInvalidation = CompletableDeferred<Unit>()
    private var captureOwner: CaptureOwnerState? = null
    private val admittedControllers = HashMap<Long, AdmittedCapture>()
    private val backendAdmissions = HashMap<BackendCaptureKey, Long>()
    /** Start attempts remain separately owned until their caller's finally block unwinds. */
    private val inFlightStartGenerations = HashSet<Long>()
    private var pendingNativeTeardownSettle: NativeTeardownSettle? = null

    /** Called under [captureOwnerLock]; wakes a start holding the mutex on a native settle. */
    private fun advanceGenerationLocked(): Pair<Long, CompletableDeferred<Unit>> {
        val previousGenerationInvalidation = generationInvalidation
        generationInvalidation = CompletableDeferred()
        return generation.incrementAndGet() to previousGenerationInvalidation
    }
    private val _captureActivity = MutableStateFlow(false)
    override val captureActivity: StateFlow<Boolean> = _captureActivity
    private val pendingZipformerStart = AtomicReference<PendingZipformerStart?>(null)
    private val startMutex = Mutex()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumped by every start and by every engine switch away from asr4all. Any thread. */
    private val asr4allStartSeq = AtomicLong(0)
    /** Start sequence and admission epoch each asr4all session was admitted under. Main only. */
    private val asr4allSessionAdmission = HashMap<Long, Asr4allAdmission>()

    init {
        // Moving the selection away from asr4all releases its engine now, not on the next capture.
        // The first emission (startup) is a no-op because nothing is loaded yet.
        preferenceScope.launch {
            flow { emitAll(voiceInputPreferences.selectedEngine) }
                .distinctUntilChanged()
                .catch { Log.w(TAG, "asr4all selection observer stopped", it) }
                .collect { engine ->
                    val startIntent = asr4allStartSeq.get()
                    val admissionEpoch = asr4allVoiceInputController.admissionEpochNow()
                    if (engine != VoiceInputEngine.Asr4all &&
                        voiceInputPreferences.selectedEngine.first() != VoiceInputEngine.Asr4all &&
                        asr4allStartSeq.compareAndSet(startIntent, startIntent + 1)
                    ) {
                        // Discard the old mic before waiting behind a cold start's lock; close only
                        // if the same selection and admission intent still own the release.
                        val releaseEpoch =
                            asr4allVoiceInputController.supersedeCaptureIfAdmissionEpoch(admissionEpoch)
                                ?: return@collect
                        asr4allVoiceInputController.releaseEngineIf(releaseEpoch) {
                            voiceInputPreferences.selectedEngine.first() != VoiceInputEngine.Asr4all
                        }
                    }
                }
        }
    }

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

    /** Live mic level of whichever engine is capturing (#1850). Both delegates reset to 0 at rest. */
    override val inputLevel: StateFlow<Float> by lazy {
        combine(
            nativeAndroidVoiceInputController.inputLevel,
            sherpaOnnxVoiceInputController.inputLevel,
            asr4allVoiceInputController.inputLevel,
        ) { native, sherpa, asr4all ->
            maxOf(native, sherpa, asr4all)
        }.stateIn(pipelineScope, SharingStarted.Eagerly, 0f)
    }

    /**
     * #1850: warms the recognizer the next start will use, so the first tap does not wait for the
     * model load. Auto warms Zipformer only when [resolveAutoSttRoute] would pick it from the
     * cached native availability; it never runs the live check. Android native has no model, but
     * with online recognition on its start reuses the live language check's answer (#1973), so
     * that check runs here instead of on the first tap, and again once a not-Ready answer is stale.
     */
    override suspend fun prewarm() {
        val asrSeq = asr4allStartSeq.get()
        val asrEpoch = asr4allVoiceInputController.admissionEpochNow()
        when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Auto -> {
                val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
                val cached = androidNativeRecognitionSupport.getCaptureAvailability()
                val nativeOnDeviceReady = cached.blockingReason == null &&
                    cached.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
                val route = resolveAutoSttRoute(
                    nativeOnDeviceReady = nativeOnDeviceReady,
                    zipformerReady = zipformerDownloaded(),
                    allowOnlineFallback = allowOnlineFallback,
                )
                if (route == AutoSttRoute.Zipformer) {
                    sherpaOnnxVoiceInputController.prewarmSpec(SherpaSttModelSpec.ZIPFORMER)
                }
            }
            VoiceInputEngine.AndroidNative -> {
                if (!voiceInputPreferences.allowOnlineSpeechFallback.first()) return
                val languageTag = androidNativeRecognitionSupport.getCaptureAvailability().languageTag
                if (androidNativeRecognitionSupport.localeCheckDue(languageTag)) {
                    androidNativeRecognitionSupport.getAvailability()
                }
            }
            VoiceInputEngine.Asr4all -> startMutex.withLock {
                asr4allVoiceInputController.prewarmIfCurrent {
                    asrSeq == asr4allStartSeq.get() &&
                        asrEpoch == asr4allVoiceInputController.admissionEpochNow() &&
                        voiceInputPreferences.selectedEngine.first() == VoiceInputEngine.Asr4all
                }
            }
            else -> sherpaOnnxVoiceInputController.prewarm()
        }
    }

    init {
        pipelineScope.launch {
            merge(
                nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
                sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
                asr4allVoiceInputController.events.map { event -> asr4allVoiceInputController to event },
            ).collect { (controller, event) -> route(controller, event) }
        }
    }

    /** Start callbacks run on Main; bind synchronously before the delegate emits any event. */
    private fun admitSession(
        gen: Long,
        controller: VoiceInputController,
        sessionId: Long,
        mode: VoiceCaptureMode,
        onSessionAdmitted: (Long) -> Unit,
    ) {
        synchronized(captureOwnerLock) {
            if (pendingNativeTeardownSettle?.ownerSessionId != sessionId) {
                pendingNativeTeardownSettle = null
            }
            admittedControllers[sessionId] = AdmittedCapture(
                controller = controller,
                mode = mode,
                backendSessionId = sessionId,
                generation = gen,
                teardown = CompletableDeferred(),
            )
            backendAdmissions[BackendCaptureKey(controller, sessionId)] = sessionId
            if (gen == generation.get()) captureOwner = CaptureOwnerState(gen, sessionId)
            updateCaptureActivityLocked()
        }
        if (controller === nativeAndroidVoiceInputController && gen == generation.get()) {
            openGeneration = gen
            expectedNativeSessionId = sessionId
            nativeFacts = NativeSessionFacts(sessionId)
        }
        onSessionAdmitted(sessionId)
    }
    private fun updateCaptureActivityLocked() {
        _captureActivity.value =
            captureOwner != null || admittedControllers.isNotEmpty() || inFlightStartGenerations.isNotEmpty()
    }

    override fun stopListening(captureSessionId: Long) {
        val capture = captureForScopedStop(captureSessionId) ?: return
        when (capture.controller) {
            asr4allVoiceInputController ->
                asr4allVoiceInputController.stopListeningForSession(capture.backendSessionId)
            nativeAndroidVoiceInputController ->
                nativeAndroidVoiceInputController.stopListeningForSession(capture.backendSessionId)
            sherpaOnnxVoiceInputController ->
                sherpaOnnxVoiceInputController.stopListeningForSession(capture.backendSessionId)
        }
    }

    override fun cancelListening(captureSessionId: Long) {
        val capture = captureForScopedStop(captureSessionId, discardAsr4allResults = true) ?: return
        when (capture.controller) {
            asr4allVoiceInputController ->
                asr4allVoiceInputController.cancelListeningForSession(capture.backendSessionId)
            nativeAndroidVoiceInputController ->
                nativeAndroidVoiceInputController.cancelListeningForSession(capture.backendSessionId)
            sherpaOnnxVoiceInputController ->
                sherpaOnnxVoiceInputController.cancelListeningForSession(capture.backendSessionId)
        }
    }

    private fun captureForScopedStop(
        sessionId: Long,
        discardAsr4allResults: Boolean = false,
    ): AdmittedCapture? {
        var invalidation: CompletableDeferred<Unit>? = null
        val (capture, invalidatedGeneration) = synchronized(captureOwnerLock) {
            val capture = admittedControllers[sessionId] ?: return null
            val owner = captureOwner
            if (owner?.sessionId == sessionId && owner.generation == capture.generation) {
                val (_, previousGenerationInvalidation) = advanceGenerationLocked()
                invalidation = previousGenerationInvalidation
                if (discardAsr4allResults && capture.controller === asr4allVoiceInputController) {
                    asr4allStartSeq.incrementAndGet()
                }
                capture to capture.generation
            } else {
                capture to null
            }
        }
        invalidation?.complete(Unit)
        if (invalidatedGeneration != null) cancelPendingZipformerStart(invalidatedGeneration)
        return capture
    }

    private fun cancelPendingZipformerStart(generation: Long) {
        val pending = pendingZipformerStart.get()?.takeIf { it.generation == generation } ?: return
        if (pendingZipformerStart.compareAndSet(pending, null)) pending.job.cancel()
    }

    private fun retireAdmission(
        sessionId: Long,
        preserveGenerationFence: Boolean = false,
        preserveFenceWhileStartInFlight: Boolean = false,
    ) {
        synchronized(captureOwnerLock) {
            val retired = admittedControllers.remove(sessionId) ?: return
            backendAdmissions.entries.removeAll { it.value == sessionId }
            retired.teardown?.complete(Unit)
            captureOwner?.takeIf { it.sessionId == sessionId }?.let { owner ->
                val preserve = preserveGenerationFence ||
                    preserveFenceWhileStartInFlight &&
                    owner.generation == retired.generation &&
                    owner.generation == generation.get() &&
                    inFlightStartGenerations.contains(owner.generation)
                captureOwner = if (preserve) CaptureOwnerState(retired.generation, null) else null
            }
            updateCaptureActivityLocked()
        }
    }

    private fun retainStartFence(gen: Long) {
        synchronized(captureOwnerLock) {
            if (gen == generation.get()) {
                captureOwner = CaptureOwnerState(gen, null)
                updateCaptureActivityLocked()
            }
        }
    }

    override suspend fun awaitCaptureTeardown(captureSessionId: Long) {
        val capture = synchronized(captureOwnerLock) { admittedControllers[captureSessionId] }
        capture?.teardown?.await()
    }

    override fun hasCaptureOrStart(): Boolean = synchronized(captureOwnerLock) {
        captureOwner != null || admittedControllers.isNotEmpty() || inFlightStartGenerations.isNotEmpty()
    }

    private fun clearCaptureOwner(sessionId: Long) {
        synchronized(captureOwnerLock) {
            if (captureOwner?.sessionId == sessionId) captureOwner = null
            backendAdmissions.entries.removeAll { it.value == sessionId }
            val capture = admittedControllers.remove(sessionId)
            capture?.teardown?.complete(Unit)
            updateCaptureActivityLocked()
        }
    }
    private fun recordNativeTeardownSettle(ownerSessionId: Long) {
        val settle = synchronized(captureOwnerLock) {
            val capture = admittedControllers[ownerSessionId]
            if (capture?.controller !== nativeAndroidVoiceInputController) {
                null
            } else {
                NativeTeardownSettle(
                    ownerSessionId = ownerSessionId,
                    settleUntilElapsedMs = SystemClock.elapsedRealtime() + NATIVE_TEARDOWN_SETTLE_MS,
                    settled = CompletableDeferred(),
                ).also { pendingNativeTeardownSettle = it }
            }
        } ?: return

        pipelineScope.launch {
            val remainingMs = (settle.settleUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(remainingMs)
            settle.settled.complete(Unit)
        }
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult =
        startListeningInternal(mode, onSessionAdmitted = {})

    override suspend fun startListening(
        mode: VoiceCaptureMode,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult = startListeningInternal(mode, onSessionAdmitted)

    private suspend fun awaitNativeTeardownSettle(gen: Long): Boolean {
        while (gen == generation.get()) {
            val (settle, invalidation) = synchronized(captureOwnerLock) {
                if (generation.get() != gen) return false
                pendingNativeTeardownSettle to generationInvalidation
            }
            settle ?: return gen == generation.get()

            val completed = select<Boolean> {
                settle.settled.onAwait { true }
                invalidation.onAwait { false }
            }
            if (!completed || gen != generation.get()) return false
            synchronized(captureOwnerLock) {
                if (pendingNativeTeardownSettle === settle) pendingNativeTeardownSettle = null
            }
        }
        return false
    }

    private suspend fun stopAndAwaitPreviousBackends(
        gen: Long,
        settleNativeToLocal: Boolean,
    ): Boolean {
        while (gen == generation.get()) {
            val previous = synchronized(captureOwnerLock) {
                admittedControllers.entries.filter { (_, capture) ->
                    capture.controller === nativeAndroidVoiceInputController ||
                        capture.controller === sherpaOnnxVoiceInputController
                }.map { (ownerSessionId, capture) -> ownerSessionId to capture }
            }
            if (previous.isEmpty()) {
                return if (settleNativeToLocal) awaitNativeTeardownSettle(gen) else gen == generation.get()
            }

            for ((ownerSessionId, capture) in previous) {
                if (gen != generation.get()) return false
                when (capture.controller) {
                    nativeAndroidVoiceInputController ->
                        nativeAndroidVoiceInputController.stopListeningForSession(capture.backendSessionId)
                    sherpaOnnxVoiceInputController ->
                        sherpaOnnxVoiceInputController.stopListeningForSession(capture.backendSessionId)
                }
                val teardown = capture.teardown
                // A wedged backend must not block every later start. On timeout its admission is
                // retired so the next start proceeds; the backend may still hold the mic.
                // Retire by owner key: after a Native->Zipformer hand-off the backend id is Zipformer's,
                // so retiring by backend id found no entry and the loop re-waited forever.
                if (teardown != null && withTimeoutOrNull(BACKEND_TEARDOWN_TIMEOUT_MS) { teardown.await() } == null) {
                    Log.w(TAG, "backend for session ${capture.backendSessionId} (owner $ownerSessionId) did not settle; retiring its admission")
                    retireWedgedAdmission(ownerSessionId, capture)
                }
            }
        }
        return false
    }

    /**
     * Retires a superseded capture whose backend never settled. Keyed by owner because a hand-off
     * re-keys the admission to a new backend id. A wedged native gets a settle window so the next
     * native start waits before opening the mic. Consumers get a synthetic cancelled stop, and a
     * backend terminal that arrives later is dropped with its admission.
     */
    private fun retireWedgedAdmission(ownerSessionId: Long, capture: AdmittedCapture) {
        if (capture.controller === nativeAndroidVoiceInputController) recordNativeTeardownSettle(ownerSessionId)
        retireAdmission(ownerSessionId)
        outbound.tryEmit(VoiceInputEvent.ListeningStopped(capture.mode, ownerSessionId, cancelled = true))
    }

    private suspend fun startListeningInternal(
        mode: VoiceCaptureMode,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        var previousNativeSessionId: Long? = null
        val (gen, invalidation) = synchronized(captureOwnerLock) {
            previousNativeSessionId = captureOwner?.sessionId?.let(admittedControllers::get)
                ?.takeIf { it.controller === nativeAndroidVoiceInputController }
                ?.backendSessionId
            val (nextGeneration, previousGenerationInvalidation) = advanceGenerationLocked()
            inFlightStartGenerations.add(nextGeneration)
            captureOwner = CaptureOwnerState(nextGeneration, null)
            updateCaptureActivityLocked()
            nextGeneration to previousGenerationInvalidation
        }
        invalidation.complete(Unit)
        previousNativeSessionId?.let(nativeAndroidVoiceInputController::cancelListeningForSession)
        pendingZipformerStart.getAndSet(null)?.job?.cancel()
        val seq = asr4allStartSeq.incrementAndGet()
        asr4allVoiceInputController.supersedeCapture()
        val asr4allEpoch = asr4allVoiceInputController.admissionEpochNow()
        return try {
            withContext(Dispatchers.Main.immediate) {
                startMutex.withLock {
                    if (gen != generation.get()) return@withLock VoiceInputStartResult.Cancelled()
                    val selectedBeforeTeardown = voiceInputPreferences.selectedEngine.first()
                    val settleNativeToLocal =
                        selectedBeforeTeardown.isSherpaFamily || selectedBeforeTeardown == VoiceInputEngine.Asr4all
                    if (!stopAndAwaitPreviousBackends(gen, settleNativeToLocal)) {
                        return@withLock VoiceInputStartResult.Cancelled()
                    }
                    if (gen != generation.get()) return@withLock VoiceInputStartResult.Cancelled()
                    val engine = voiceInputPreferences.selectedEngine.first()
                    if (engine != selectedBeforeTeardown) return@withLock VoiceInputStartResult.Cancelled()
                    val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
                    if (gen != generation.get() || voiceInputPreferences.selectedEngine.first() != engine) {
                        return@withLock VoiceInputStartResult.Cancelled()
                    }
                    openGeneration = -1L
                    handoff = null
                    nativeFacts = null
                    expectedNativeSessionId = null
                    if (engine != VoiceInputEngine.Auto) {
                        JandalEvents.record("voice", "stt_route", "route" to engine, "online_fallback" to allowOnlineFallback)
                    }
                    if (engine != VoiceInputEngine.Asr4all) {
                        asr4allVoiceInputController.releaseEngineIf(asr4allEpoch) {
                            voiceInputPreferences.selectedEngine.first() != VoiceInputEngine.Asr4all
                        }
                    }
                    if (gen != generation.get() || voiceInputPreferences.selectedEngine.first() != engine) {
                        return@withLock VoiceInputStartResult.Cancelled()
                    }
                    val result = when (engine) {
                        VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback, gen, onSessionAdmitted)
                        VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback, gen, onSessionAdmitted)
                        VoiceInputEngine.Asr4all -> startAsr4all(
                            mode,
                            gen,
                            seq,
                            asr4allEpoch,
                            onSessionAdmitted,
                        )
                        else -> startSherpa(mode, gen, onSessionAdmitted)
                    }
                    val hasPhysicalAdmission = when (result) {
                        is VoiceInputStartResult.Started -> true
                        is VoiceInputStartResult.Cancelled -> result.captureSessionId?.let { id ->
                            synchronized(captureOwnerLock) { id in admittedControllers }
                        } == true
                        is VoiceInputStartResult.Unavailable -> false
                    }
                    if (result is VoiceInputStartResult.Started && gen == generation.get()) {
                        openGeneration = gen
                    } else if (result is VoiceInputStartResult.Unavailable ||
                        result is VoiceInputStartResult.Cancelled && !hasPhysicalAdmission
                    ) {
                        synchronized(captureOwnerLock) {
                            if (captureOwner?.generation == gen) captureOwner = null
                            updateCaptureActivityLocked()
                        }
                    }
                    result
                }
            }
        } finally {
            synchronized(captureOwnerLock) {
                inFlightStartGenerations.remove(gen)
                if (captureOwner?.generation == gen && captureOwner?.sessionId == null) {
                    captureOwner = null
                }
                updateCaptureActivityLocked()
            }
        }
    }

    private suspend fun startAuto(
        mode: VoiceCaptureMode,
        allowOnlineFallback: Boolean,
        gen: Long,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        val zipformerReady = zipformerDownloaded()
        if (zipformerReady && !allowOnlineFallback) {
            // Zipformer wins regardless of native support; skip the native availability check.
            Log.i(TAG, "Auto STT route=${AutoSttRoute.Zipformer} mode=$mode allowOnlineFallback=false")
            JandalEvents.record("voice", "stt_route", "route" to AutoSttRoute.Zipformer, "online_fallback" to false)
            return startSherpa(mode, gen, onSessionAdmitted)
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
        JandalEvents.record(
            "voice", "stt_route", "route" to route,
            "native_ready" to nativeOnDeviceReady, "zipformer_ready" to zipformerReady,
            "online_fallback" to allowOnlineFallback,
        )
        return when (route) {
            AutoSttRoute.Native -> {
                // The live check's throwaway recognizer is torn down asynchronously; starting
                // a session immediately can lose the shared on-device service
                // (ERROR_SERVER_DISCONNECTED on the S23 Ultra), so let it settle first.
                if (ranLiveCheck) delay(LIVE_CHECK_SETTLE_MS)
                startNative(mode, allowOnlineFallback, gen, onSessionAdmitted)
            }
            AutoSttRoute.Zipformer -> startSherpa(mode, gen, onSessionAdmitted)
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
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        activate(nativeAndroidVoiceInputController)
        val nativeStart = NativeStartFallback()
        nativeStartFallback = nativeStart
        var nativeSessionId: Long? = null
        val nativeResult = nativeAndroidVoiceInputController.startListening(
            mode,
            allowOnlineFallback,
            onSessionAdmitted = { sessionId ->
                nativeSessionId = sessionId
                nativeStart.sessionId = sessionId
                admitSession(gen, nativeAndroidVoiceInputController, sessionId, mode, onSessionAdmitted)
            },
            isWanted = { gen == generation.get() },
        )
        if (nativeResult is VoiceInputStartResult.Started) {
            endNativeStart(nativeStart)
            if (gen != generation.get()) {
                nativeAndroidVoiceInputController.cancelListeningForSession(nativeResult.captureSessionId)
                return VoiceInputStartResult.Cancelled(nativeResult.captureSessionId)
            }
            expectedNativeSessionId = nativeResult.captureSessionId
            nativeFacts = nativeFacts?.takeIf { it.sessionId == nativeResult.captureSessionId }
                ?: NativeSessionFacts(sessionId = nativeResult.captureSessionId)
            return nativeResult
        }
        if (nativeResult is VoiceInputStartResult.Cancelled) {
            endNativeStart(nativeStart)
            nativeResult.captureSessionId?.let(nativeAndroidVoiceInputController::cancelListeningForSession)
            return nativeResult
        }
        if (nativeSessionId == null) endNativeStart(nativeStart)
        // Keep the backend mapping until its post-cleanup terminal is routed. A start-time
        // fallback can suspend while that physical stop is queued.
        // Its post-cleanup terminal retires the Native mapping after fallback suspension.
        Log.w(TAG, "Android native unavailable at start; trying Zipformer: ${(nativeResult as? VoiceInputStartResult.Unavailable)?.message}")
        activeController.value = sherpaOnnxVoiceInputController
        var fallbackSessionId: Long? = null
        var fallbackResult: VoiceInputStartResult? = null
        val zipformer = try {
            runZipformerStart(gen) {
                val result = sherpaOnnxVoiceInputController.startListening(
                    mode,
                    isWanted = { gen == generation.get() },
                    onSessionAdmitted = { sessionId ->
                        fallbackSessionId = sessionId
                        admitSession(gen, sherpaOnnxVoiceInputController, sessionId, mode, onSessionAdmitted)
                    },
                )
                fallbackResult = result
                val started = result as? VoiceInputStartResult.Started
                when {
                    started == null -> null
                    gen != generation.get() -> {
                        sherpaOnnxVoiceInputController.cancelListeningForSession(started.captureSessionId)
                        null
                    }
                    else -> started
                }
            }
        } catch (e: CancellationException) {
            endNativeStart(nativeStart)
            throw e
        }
        if (nativeSessionId != null) settleNativeFallback(nativeStart, zipformerStarted = zipformer != null)
        if (zipformer != null) {
            recordZipformerFallback(
                reason = NATIVE_NOT_READY,
                mode = mode,
                jandalReason = NativeFallbackReason.NATIVE_NOT_READY,
            )
            postNotice(VoiceNotice(zipformer.captureSessionId, ZIPFORMER_START_NOTICE))
            return zipformer
        }
        activeController.value = nativeAndroidVoiceInputController
        if (fallbackResult == null) {
            fallbackSessionId?.let(sherpaOnnxVoiceInputController::cancelListeningForSession)
        }
        if (fallbackResult is VoiceInputStartResult.Unavailable) {
            retainStartFence(gen)
            // Keep its admission until the backend's matching terminal is routed.
            return nativeResult
        }
        if (fallbackSessionId != null) return VoiceInputStartResult.Cancelled(fallbackSessionId)
        if (gen != generation.get()) return VoiceInputStartResult.Cancelled()
        return nativeResult
    }

    /** Ends a Native start's terminal hold, delivering a terminal that was held for it. */
    private fun endNativeStart(start: NativeStartFallback) {
        if (nativeStartFallback === start) nativeStartFallback = null
        start.held?.let { outbound.tryEmit(it) }
        start.held = null
    }

    /**
     * Settles a Native start that was admitted and then handed to Zipformer. If the fallback
     * started, the Native terminal is superseded: drop it when already held, else drop it on arrival.
     */
    private fun settleNativeFallback(start: NativeStartFallback, zipformerStarted: Boolean) {
        if (!zipformerStarted) {
            endNativeStart(start)
            return
        }
        if (start.held != null) {
            start.held = null
            if (nativeStartFallback === start) nativeStartFallback = null
        } else {
            start.suppressLate = true
        }
    }

    /**
     * Holds the terminal of a Native start while its Zipformer fallback decides. Consumers end
     * capture on any cancelled stop, so a superseded Native stop must not reach them mid-fallback.
     * Main thread only.
     */
    private class NativeStartFallback {
        var sessionId: Long? = null
        var held: VoiceInputEvent? = null
        var suppressLate = false
    }

    private var nativeStartFallback: NativeStartFallback? = null

    /**
     * Starts Sherpa for the capture with generation [gen]. Sherpa's mic open is gated on
     * [gen] (a stop during this suspension must not open the mic); afterwards a stop that
     * raced the start is closed here too.
     */
    private suspend fun startSherpa(
        mode: VoiceCaptureMode,
        gen: Long,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        activate(sherpaOnnxVoiceInputController)
        var reservedSessionId: Long? = null
        val result = sherpaOnnxVoiceInputController.startListening(
            mode,
            isWanted = { gen == generation.get() },
            onSessionAdmitted = { sessionId ->
                reservedSessionId = sessionId
                admitSession(gen, sherpaOnnxVoiceInputController, sessionId, mode, onSessionAdmitted)
            },
        )
        if (result is VoiceInputStartResult.Started && gen != generation.get()) {
            sherpaOnnxVoiceInputController.cancelListeningForSession(result.captureSessionId)
            return VoiceInputStartResult.Cancelled(result.captureSessionId)
        }
        return result
    }

    /**
     * Starts the asr4all streaming recognizer for the capture with generation [gen]. Like
     * [startSherpa], a stop that raced the start is closed here too.
     */
    private suspend fun startAsr4all(
        mode: VoiceCaptureMode,
        gen: Long,
        seq: Long,
        epoch: Long,
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        activate(asr4allVoiceInputController)
        val result = asr4allVoiceInputController.startListening(
            mode = mode,
            isWanted = {
                gen == generation.get() &&
                    seq == asr4allStartSeq.get() &&
                    epoch == asr4allVoiceInputController.admissionEpochNow()
            },
            onSession = { sessionId ->
                asr4allSessionAdmission[sessionId] = Asr4allAdmission(seq, epoch, gen)
                admitSession(gen, asr4allVoiceInputController, sessionId, mode, onSessionAdmitted)
            },
        )
        if (result is VoiceInputStartResult.Started && gen != generation.get()) {
            asr4allVoiceInputController.cancelListeningForSession(result.captureSessionId)
            return VoiceInputStartResult.Cancelled(result.captureSessionId)
        }
        return result
    }


    /** Prior admitted backends have already been stopped and awaited by startListeningInternal. */
    private fun activate(controller: VoiceInputController) {
        activeController.value = controller
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
        val pending = PendingZipformerStart(gen, job)
        pendingZipformerStart.set(pending)
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
            // Covers both a cancelled job (stop / newer start) and a cancelled caller; in the
            // latter case the job must not keep loading Zipformer on its own.
            job.cancel()
            currentCoroutineContext().ensureActive()
            Log.i(TAG, "Zipformer start cancelled: capture stopped or superseded")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Zipformer failed to start", e)
            null
        } finally {
            pendingZipformerStart.compareAndSet(pending, null)
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
            Log.e(TAG, "Failed to route voice event ${event::class.simpleName}; delivering only if still owned", e)
            val ownerSessionId = synchronized(captureOwnerLock) {
                backendAdmissions[BackendCaptureKey(controller, event.captureSessionId)]
            }
            val current = ownerSessionId?.let { sessionId ->
                synchronized(captureOwnerLock) {
                    val capture = admittedControllers[sessionId]
                    val owner = captureOwner
                    capture != null && capture.generation == generation.get() &&
                        owner?.generation == capture.generation && owner.sessionId == sessionId
                }
            } ?: false
            if (event is VoiceInputEvent.ListeningStopped && ownerSessionId != null) {
                if (controller === nativeAndroidVoiceInputController) {
                    recordNativeTeardownSettle(ownerSessionId)
                }
                val stopped = if (current) event else event.copy(cancelled = true)
                outbound.tryEmit(stopped.withCaptureSessionId(ownerSessionId))
                retireAdmission(
                    ownerSessionId,
                    preserveFenceWhileStartInFlight = current && stopped.cancelled,
                )
            } else if (current) {
                ownerSessionId?.let { outbound.tryEmit(event.withCaptureSessionId(it)) }
            }
        }
    }


    /**
     * An asr4all session is current only while no newer start and no engine release (selection
     * switch or deletion) has happened since it was admitted.
     */
    private fun asr4allAdmissionCurrent(sessionId: Long): Boolean {
        val admission = asr4allSessionAdmission[sessionId] ?: return false
        return admission.seq == asr4allStartSeq.get() &&
            admission.epoch == asr4allVoiceInputController.admissionEpochNow()
    }

    private fun isTerminal(event: VoiceInputEvent): Boolean =
        event is VoiceInputEvent.ListeningStopped


    private suspend fun routeEvent(controller: VoiceInputController, event: VoiceInputEvent) {
        val backendKey = BackendCaptureKey(controller, event.captureSessionId)
        val backendOwnerSessionId = if (controller === asr4allVoiceInputController) {
            null
        } else {
            synchronized(captureOwnerLock) { backendAdmissions[backendKey] }
        }
        if (controller !== asr4allVoiceInputController && backendOwnerSessionId == null) return
        val currentBackend = backendOwnerSessionId?.let { ownerSessionId ->
            synchronized(captureOwnerLock) {
                admittedControllers[ownerSessionId]?.let {
                    it.controller === controller && it.backendSessionId == event.captureSessionId
                }
            }
        } ?: false
        if (backendOwnerSessionId != null && !currentBackend) {
            if (event is VoiceInputEvent.ListeningStopped) {
                synchronized(captureOwnerLock) { backendAdmissions.remove(backendKey) }
            }
            return
        }
        val logicalOwnerCurrent = backendOwnerSessionId?.let { ownerSessionId ->
            synchronized(captureOwnerLock) {
                val capture = admittedControllers[ownerSessionId]
                val owner = captureOwner
                capture != null &&
                    capture.generation == generation.get() &&
                    owner?.generation == capture.generation && owner.sessionId == ownerSessionId
            }
        } ?: false
        if (backendOwnerSessionId != null && !logicalOwnerCurrent &&
            event !is VoiceInputEvent.ListeningStopped
        ) return
        if (controller === asr4allVoiceInputController) {
            val sessionId = event.captureSessionId
            val admission = asr4allSessionAdmission[sessionId] ?: return
            if (admission.stopped) return
            val current = asr4allAdmissionCurrent(sessionId) && controller === activeController.value
            when (event) {
                is VoiceInputEvent.Transcript,
                is VoiceInputEvent.Error,
                -> {
                    if (admission.resultSeen) return
                    admission.resultSeen = true
                    if (!current) return
                    admission.resultDelivered = true
                    outbound.emit(event)
                }
                is VoiceInputEvent.ListeningStopped -> {
                    admission.stopped = true
                    val cancelled = !admission.resultDelivered && (event.cancelled || !current)
                    try {
                        outbound.emit(if (event.cancelled == cancelled) event else event.copy(cancelled = cancelled))
                    } finally {
                        asr4allSessionAdmission.remove(sessionId)
                        clearCaptureOwner(sessionId)
                    }
                }
                else -> if (current) outbound.emit(event)
            }
            return
        }
        if (event is VoiceInputEvent.ListeningStopped && backendOwnerSessionId != null) {
            if (controller === nativeAndroidVoiceInputController) {
                recordNativeTeardownSettle(backendOwnerSessionId)
            }
            val terminal = if (logicalOwnerCurrent) event else event.copy(cancelled = true)
            val ownedTerminal = terminal.withCaptureSessionId(backendOwnerSessionId)
            val start = nativeStartFallback?.takeIf {
                controller === nativeAndroidVoiceInputController && it.sessionId == backendOwnerSessionId
            }
            when {
                start == null -> outbound.emit(ownedTerminal)
                start.suppressLate -> nativeStartFallback = null
                else -> start.held = ownedTerminal
            }
            retireAdmission(
                backendOwnerSessionId,
                preserveFenceWhileStartInFlight = logicalOwnerCurrent && terminal.cancelled,
            )
            return
        }
        if (controller !== activeController.value) return
        fun logicalCaptureStillCurrent(): Boolean = backendOwnerSessionId?.let { ownerSessionId ->
            synchronized(captureOwnerLock) {
                val capture = admittedControllers[ownerSessionId]
                val owner = captureOwner
                capture != null && capture.generation == generation.get() &&
                    owner?.generation == capture.generation && owner.sessionId == ownerSessionId
            }
        } ?: false

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
                if (!handled && !logicalCaptureStillCurrent()) return
            }
        }

        val current = handoff
        if (current != null && controller === sherpaOnnxVoiceInputController &&
            event.captureSessionId == current.zipformerSessionId
        ) {
            // The native cue already played for this capture; do not replay it.
            if (event is VoiceInputEvent.ListeningStarted && current.readinessAnnounced) return
            val ownedEvent = event.withCaptureSessionId(current.nativeSessionId)
            outbound.emit(ownedEvent)
            if (isTerminal(ownedEvent)) clearCaptureOwner(current.nativeSessionId)
            return
        }
        val ownedEvent = backendOwnerSessionId?.let(event::withCaptureSessionId) ?: event
        outbound.emit(ownedEvent)
        if (isTerminal(ownedEvent) && backendOwnerSessionId != null) {
            clearCaptureOwner(backendOwnerSessionId)
        }
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
                val previousAdmission = synchronized(captureOwnerLock) { admittedControllers[nativeId] }
                    ?: return@withLock null
                var sherpaSessionId: Long? = null
                var backendRegistered = false
                val result = sherpaOnnxVoiceInputController.startListening(
                    error.mode,
                    isWanted = { gen == generation.get() },
                    onSessionAdmitted = { backendId ->
                        sherpaSessionId = backendId
                        backendRegistered = synchronized(captureOwnerLock) {
                            val current = admittedControllers[nativeId]
                            if (current == null) {
                                false
                            } else {
                                admittedControllers[nativeId] = current.copy(
                                    controller = sherpaOnnxVoiceInputController,
                                    backendSessionId = backendId,
                                )
                                backendAdmissions[BackendCaptureKey(sherpaOnnxVoiceInputController, backendId)] =
                                    nativeId
                                true
                            }
                        }
                    },
                )
                if (result is VoiceInputStartResult.Unavailable) {
                    if (backendRegistered) {
                        synchronized(captureOwnerLock) {
                            backendAdmissions.remove(
                                BackendCaptureKey(sherpaOnnxVoiceInputController, checkNotNull(sherpaSessionId)),
                            )
                            admittedControllers[nativeId] = previousAdmission
                            backendAdmissions[
                                BackendCaptureKey(previousAdmission.controller, previousAdmission.backendSessionId)
                            ] = nativeId
                        }
                    }
                    return@withLock null
                }
                val started = result as? VoiceInputStartResult.Started ?: return@withLock null
                if (sherpaSessionId != started.captureSessionId || !backendRegistered) {
                    sherpaOnnxVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                if (gen != generation.get()) {
                    sherpaOnnxVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                // Admission is already mapped to the logical native owner before Sherpa can
                // emit readiness or a terminal. Commit only if that owner is still current.
                val remapped = synchronized(captureOwnerLock) {
                    val current = admittedControllers[nativeId]
                    gen == generation.get() &&
                        captureOwner?.sessionId == nativeId &&
                        current?.controller === sherpaOnnxVoiceInputController &&
                        current.backendSessionId == started.captureSessionId
                }
                if (!remapped) {
                    sherpaOnnxVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                activeController.value = sherpaOnnxVoiceInputController
                handoff = ZipformerHandoff(
                    nativeSessionId = nativeId,
                    zipformerSessionId = started.captureSessionId,
                    readinessAnnounced = readinessAnnounced,
                )
                recordZipformerFallback(
                    reason = reason,
                    mode = error.mode,
                    jandalReason = NativeFallbackReason.NATIVE_ERROR,
                )
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

    private fun recordZipformerFallback(reason: String, jandalReason: NativeFallbackReason, mode: VoiceCaptureMode) {
        AcousticJournalBridge.record(
            type = AcousticEventType.STT_ZIPFORMER_FALLBACK,
            metadata = { mapOf("reason" to reason, "mode" to mode.name) },
        )
        JandalEvents.record("voice", "zipformer_fallback", "reason" to jandalReason, "mode" to mode)
    }

    /** Thread-safe: see the class concurrency notes. */
    override fun stopListening() {
        val invalidation = synchronized(captureOwnerLock) {
            val (_, previousGenerationInvalidation) = advanceGenerationLocked()
            asr4allStartSeq.incrementAndGet()
            if (captureOwner?.sessionId == null) captureOwner = null
            updateCaptureActivityLocked()
            previousGenerationInvalidation
        }
        invalidation.complete(Unit)
        pendingZipformerStart.getAndSet(null)?.job?.cancel()
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
        asr4allVoiceInputController.supersedeCapture()
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
