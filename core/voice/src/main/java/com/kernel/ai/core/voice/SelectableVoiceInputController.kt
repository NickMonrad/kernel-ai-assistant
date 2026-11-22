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

/** Notice when Android native could not start or never became ready, and the offline model took the request. */
internal const val ASR4ALL_START_NOTICE =
    "Android speech isn't available on this phone right now. Using the offline speech model for this request."

/**
 * Notice when Android native stopped responding before any speech progress and the offline model took over. The
 * user may have spoken and not been heard, and the cue is not replayed, so ask them to repeat.
 */
internal const val ASR4ALL_STALL_NOTICE =
    "Android speech stopped responding, so the offline speech model is listening now. Please say that again."

/** Which recognizer [VoiceInputEngine.Auto] uses for one capture session. */
internal enum class AutoSttRoute {
    /** Android native, starting on-device. Platform fallback follows the user's setting. */
    Native,

    /** Offline asr4all streaming model, once it is downloaded. */
    Asr4all,

    /** No usable recognizer yet (no offline model downloaded, no on-device support). */
    Unavailable,
}

/**
 * Picks the recognizer for [VoiceInputEngine.Auto].
 *
 * Without the online fallback, an offline model wins: asr4all once it is downloaded. Android's on-device recognizer is
 * not preferred there: on the S23 Ultra it returned empty results for clearly audible speech that an offline model
 * transcribed (#1008). On-device Android recognition is used only while no offline model is downloaded.
 *
 * With the online fallback allowed, Android native is preferred: it starts on-device and its
 * existing fallback retries empty or failed on-device results on the platform service.
 */
internal fun resolveAutoSttRoute(
    nativeOnDeviceReady: Boolean,
    asr4allReady: Boolean,
    allowOnlineFallback: Boolean,
): AutoSttRoute = when {
    allowOnlineFallback && nativeOnDeviceReady -> AutoSttRoute.Native
    asr4allReady -> AutoSttRoute.Asr4all
    nativeOnDeviceReady -> AutoSttRoute.Native
    else -> AutoSttRoute.Unavailable
}

/** Auto and asr4all share one loaded engine, so an Auto start keeps it when its route is asr4all. */
private fun VoiceInputEngine.usesAsr4all(): Boolean = this == VoiceInputEngine.Asr4all || this == VoiceInputEngine.Auto

/**
 * Whether a terminal Android native error may be handed to asr4all for the same capture.
 *
 * Only two failures qualify: the recognizer never became ready ([NATIVE_NOT_READY]) and the
 * watchdog expired before any speech progress ([NATIVE_STALLED]; the native controller leaves
 * stalls after speech uncategorised). Recognizer-reported errors (no match, speech timeout,
 * network) are surfaced instead, because a second recognizer would only re-wait on the same
 * silence. [hadSpeechProgress] is the router's own record of SpeechDetected, partials and
 * transcripts for the session; any of them blocks the hand-off, so the user's words are never
 * silently replaced.
 */
internal fun isAsr4allHandoffEligible(category: String?, hadSpeechProgress: Boolean): Boolean =
    !hadSpeechProgress && (category == NATIVE_NOT_READY || category == NATIVE_STALLED)

/** Native session facts needed to decide a hand-off. Main thread only. */
private data class NativeSessionFacts(
    val sessionId: Long,
    val readinessAnnounced: Boolean = false,
    val speechProgress: Boolean = false,
)

private data class CaptureOwnerState(val generation: Long, val sessionId: Long?)
private data class AdmittedCapture(
    val controller: VoiceInputController,
    val backendSessionId: Long,
    val generation: Long,
    val mode: VoiceCaptureMode,
    val teardown: CompletableDeferred<Unit>? = null,
    /** Set by a finalizing scoped stop; guarded by captureOwnerLock. */
    var finalizing: Boolean = false,
)
private data class BackendCaptureKey(val controller: VoiceInputController, val sessionId: Long)
private data class NativeTeardownSettle(
    val ownerSessionId: Long,
    val settleUntilElapsedMs: Long,
    val settled: CompletableDeferred<Unit>,
)
private data class PendingAsr4allStart(val generation: Long, val job: Job)

/**
 * An asr4all session that replaced a native session mid-capture. Its events are re-stamped
 * with [nativeSessionId] so consumers keep matching the session they started.
 */
private data class Asr4allHandoff(
    val nativeSessionId: Long,
    val asr4allSessionId: Long,
    /** Native readiness already reached consumers, so the cue must not replay for asr4all. */
    val readinessAnnounced: Boolean,
)

private fun VoiceInputEvent.withCaptureSessionId(id: Long): VoiceInputEvent = when (this) {
    is VoiceInputEvent.ListeningStarted -> copy(captureSessionId = id)
    is VoiceInputEvent.ListeningRequested -> copy(captureSessionId = id)
    is VoiceInputEvent.SpeechDetected -> copy(captureSessionId = id)
    is VoiceInputEvent.PartialTranscript -> copy(captureSessionId = id)
    is VoiceInputEvent.Transcript -> copy(captureSessionId = id)
    is VoiceInputEvent.Error -> copy(captureSessionId = id)
    is VoiceInputEvent.ListeningStopped -> copy(captureSessionId = id)
    is VoiceInputEvent.SpeechEnded -> copy(captureSessionId = id)
}

/**
 * Routes voice capture between Android native, Sherpa-ONNX, and asr4all, one owner at a time.
 *
 * Concurrency model:
 * - Routing state ([activeController], [openGeneration], [expectedNativeSessionId],
 *   [nativeFacts], [handoff]) is confined to the Main thread. [startListening] switches to
 *   Main, and the event pipeline and every asr4all start job run on [pipelineScope] (Main).
 * - [stopListening] may be called from any thread. It only bumps the atomic [generation],
 *   cancels the atomic [pendingAsr4allStart] job, and stops both recognizers, which are
 *   thread-safe themselves.
 * - Each capture owns the generation it was started with. A stop or a newer start bumps
 *   [generation], so a hand-off for an older capture can no longer commit.
 * - Every asr4all start made on the user's behalf (start-time fallback, mid-session hand-off)
 *   runs as a cancellable job. Cancelling it while the model loads makes
 *   [Asr4allVoiceInputController.startListening] give up before it takes audio focus or
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
    /** Test seam, null in production: runs after a wedged backend times out, just before its admission is retired. */
    internal var beforeWedgedRetireForTest: (suspend () -> Unit)? = null

    /** Called under [captureOwnerLock]; wakes a start holding the mutex on a native settle. */
    private fun advanceGenerationLocked(): Pair<Long, CompletableDeferred<Unit>> {
        val previousGenerationInvalidation = generationInvalidation
        generationInvalidation = CompletableDeferred()
        return generation.incrementAndGet() to previousGenerationInvalidation
    }
    private val _captureActivity = MutableStateFlow(false)
    override val captureActivity: StateFlow<Boolean> = _captureActivity
    private val asr4allStartsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingAsr4allStart = AtomicReference<PendingAsr4allStart?>(null)
    private val startMutex = Mutex()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Discards and releases asr4all when the selection leaves it. Only a live asr4all capture or start
     * is retired by the generation bump: a start for another engine issued just after the switch must
     * survive the observer, which runs asynchronously. The first emission is a no-op: nothing is loaded yet.
     */
    init {
        preferenceScope.launch {
            var previous: VoiceInputEngine? = null
            voiceInputPreferences.selectedEngine
                .distinctUntilChanged()
                .catch { Log.w(TAG, "asr4all selection observer stopped", it) }
                .collect { engine ->
                    val leftAsr4all = previous?.usesAsr4all() == true && !engine.usesAsr4all()
                    previous = engine
                    if (!leftAsr4all) return@collect
                    // Supersede before waiting behind a start's lock, so a cold mic closes at once.
                    retireAsr4allForDeselect()
                    // Re-read under startMutex: a reselect that ran first keeps the loaded engine.
                    startMutex.withLock {
                        if (!voiceInputPreferences.selectedEngine.first().usesAsr4all()) {
                            asr4allVoiceInputController.releaseEngine()
                        }
                    }
                }
        }
    }

    /**
     * Supersedes the shared generation for a selection switch away from asr4all: late events of its
     * capture are dropped, its terminal still retires the admission, and any start in flight is cancelled.
     */
    private fun retireAsr4allForDeselect() {
        val asr4allLive = asr4allStartsInFlight.get() > 0 || synchronized(captureOwnerLock) {
            admittedControllers.values.any { it.controller === asr4allVoiceInputController }
        }
        if (asr4allLive) {
            val invalidation = synchronized(captureOwnerLock) {
                val (_, previousGenerationInvalidation) = advanceGenerationLocked()
                if (captureOwner?.sessionId == null) captureOwner = null
                updateCaptureActivityLocked()
                previousGenerationInvalidation
            }
            invalidation.complete(Unit)
            pendingAsr4allStart.getAndSet(null)?.job?.cancel()
        }
        asr4allVoiceInputController.supersedeCapture()
    }

    /** Generation of the capture that is currently open, or -1. Main only. */
    private var openGeneration = -1L
    /** Native session id returned by the current start. Main only. */
    private var expectedNativeSessionId: Long? = null
    private var nativeFacts: NativeSessionFacts? = null
    private var handoff: Asr4allHandoff? = null

    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outbound = voiceEventFlow()
    private val micLifecycle = voiceEventFlow()
    private val noticeFlow = MutableSharedFlow<VoiceNotice>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One pipeline for all collectors. Collecting per subscriber would run the
     * hand-off once per collector and could start two recognizers.
     */
    override val events: Flow<VoiceInputEvent> = outbound.asSharedFlow()

    /** Forwarded before the active-controller filter; see [VoiceInputController.micLifecycleEvents] (#2027). */
    override val micLifecycleEvents: Flow<VoiceInputEvent> = micLifecycle.asSharedFlow()
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
     * model load. Auto warms the offline model [resolveAutoSttRoute] picks from the cached
     * native availability; it never runs the live check. Android native has no model, but
     * with online recognition on its start reuses the live language check's answer (#1973), so
     * that check runs here instead of on the first tap, and again once a not-Ready answer is stale.
     */
    override suspend fun prewarm() {
        when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Auto -> {
                val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
                val cached = androidNativeRecognitionSupport.getCaptureAvailability()
                val nativeOnDeviceReady = cached.blockingReason == null &&
                    cached.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
                val route = resolveAutoSttRoute(
                    nativeOnDeviceReady = nativeOnDeviceReady,
                    asr4allReady = asr4allVoiceInputController.isAvailable(),
                    allowOnlineFallback = allowOnlineFallback,
                )
                when (route) {
                    AutoSttRoute.Asr4all -> startMutex.withLock {
                        asr4allVoiceInputController.prewarmIfCurrent {
                            voiceInputPreferences.selectedEngine.first() == VoiceInputEngine.Auto
                        }
                    }
                    AutoSttRoute.Native, AutoSttRoute.Unavailable -> Unit
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
                    voiceInputPreferences.selectedEngine.first() == VoiceInputEngine.Asr4all
                }
            }
            else -> if (voiceInputPreferences.allowsSherpaRuntime()) {
                sherpaOnnxVoiceInputController.prewarm()
            }
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

    /** Finalizes [captureSessionId]: its final result and terminal still route. Thread-safe. */
    override fun stopListening(captureSessionId: Long) {
        val capture = captureForScopedStop(captureSessionId, finalize = true) ?: return
        when (capture.controller) {
            asr4allVoiceInputController ->
                asr4allVoiceInputController.stopListeningForSession(capture.backendSessionId)
            nativeAndroidVoiceInputController ->
                nativeAndroidVoiceInputController.stopListeningForSession(capture.backendSessionId)
            sherpaOnnxVoiceInputController ->
                sherpaOnnxVoiceInputController.stopListeningForSession(capture.backendSessionId)
        }
    }

    /** Discards [captureSessionId]: its late results are dropped and it never finalizes. Thread-safe. */
    override fun cancelListening(captureSessionId: Long) {
        val capture = captureForScopedStop(captureSessionId, finalize = false) ?: return
        when (capture.controller) {
            asr4allVoiceInputController ->
                asr4allVoiceInputController.cancelListeningForSession(capture.backendSessionId)
            nativeAndroidVoiceInputController ->
                nativeAndroidVoiceInputController.cancelListeningForSession(capture.backendSessionId)
            sherpaOnnxVoiceInputController ->
                sherpaOnnxVoiceInputController.cancelListeningForSession(capture.backendSessionId)
        }
    }

    /**
     * Shared fence for a scoped stop. A cancel, or a stop racing a start or hand-off still in flight, supersedes
     * the generation, so late events of that capture are dropped. A finalize of a started capture keeps
     * the generation, so its final result and terminal still route. Its give-up retires the admission
     * if no terminal arrives within [BACKEND_TEARDOWN_TIMEOUT_MS].
     */
    private fun captureForScopedStop(sessionId: Long, finalize: Boolean): AdmittedCapture? {
        var invalidation: CompletableDeferred<Unit>? = null
        var finalizing: AdmittedCapture? = null
        val (capture, invalidatedGeneration) = synchronized(captureOwnerLock) {
            val capture = admittedControllers[sessionId] ?: return null
            val owner = captureOwner
            if (owner?.sessionId != sessionId || owner.generation != capture.generation) {
                return@synchronized capture to null
            }
            val handOffOwned = inFlightStartGenerations.contains(capture.generation) ||
                pendingAsr4allStart.get()?.generation == capture.generation ||
                pendingNativeTeardownSettle?.ownerSessionId == sessionId
            if (finalize && !handOffOwned) {
                if (!capture.finalizing) {
                    capture.finalizing = true
                    finalizing = capture
                }
                return@synchronized capture to null
            }
            val (_, previousGenerationInvalidation) = advanceGenerationLocked()
            invalidation = previousGenerationInvalidation
            capture to capture.generation
        }
        invalidation?.complete(Unit)
        if (invalidatedGeneration != null) cancelPendingAsr4allStart(invalidatedGeneration)
        finalizing?.let(::armFinalizeGiveUp)
        return capture
    }

    /** A finalized capture whose terminal never arrives is retired like a wedged backend. */
    private fun armFinalizeGiveUp(capture: AdmittedCapture) {
        pipelineScope.launch {
            delay(BACKEND_TEARDOWN_TIMEOUT_MS)
            val ownerSessionId = synchronized(captureOwnerLock) {
                admittedControllers.entries.firstOrNull { it.value === capture }?.key
            } ?: return@launch
            Log.w(TAG, "finalized session ${capture.backendSessionId} sent no terminal; retiring its admission")
            retireWedgedAdmission(ownerSessionId, capture)
        }
    }

    private fun cancelPendingAsr4allStart(generation: Long) {
        val pending = pendingAsr4allStart.get()?.takeIf { it.generation == generation } ?: return
        if (pendingAsr4allStart.compareAndSet(pending, null)) pending.job.cancel()
    }

    private fun retireAdmission(
        sessionId: Long,
        preserveGenerationFence: Boolean = false,
        preserveFenceWhileStartInFlight: Boolean = false,
    ): AdmittedCapture? {
        synchronized(captureOwnerLock) {
            val retired = admittedControllers.remove(sessionId) ?: return null
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
            return retired
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
                        capture.controller === sherpaOnnxVoiceInputController ||
                        capture.controller === asr4allVoiceInputController
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
                    asr4allVoiceInputController ->
                        asr4allVoiceInputController.cancelListeningForSession(capture.backendSessionId)
                }
                val teardown = capture.teardown
                // A wedged backend must not block every later start. On timeout its admission is
                // retired so the next start proceeds, but the backend may still hold the mic. A wedged
                // Sherpa can also still block inside its session mutex (joins of the previous job and
                // reader); that is why retirement, not a clean settle, is the only bound here. Once
                // retired, capture activity goes false while that backend may still hold the mic.
                // Retire by owner key: after a Native->asr4all hand-off the backend id is asr4all's,
                // so retiring by backend id found no entry and the loop re-waited forever.
                if (teardown != null && withTimeoutOrNull(BACKEND_TEARDOWN_TIMEOUT_MS) { teardown.await() } == null) {
                    Log.w(TAG, "backend for session ${capture.backendSessionId} (owner $ownerSessionId) did not settle; retiring its admission")
                    beforeWedgedRetireForTest?.invoke()
                    retireWedgedAdmission(ownerSessionId, capture)
                }
            }
        }
        return false
    }

    /**
     * Retires a superseded capture whose backend never settled. Keyed by owner because a hand-off
     * re-keys the admission to a new backend id. A wedged native records a settle window, which the
     * next start awaits only when its engine is Sherpa-family, asr4all or Auto (settleNativeToLocal). Consumers
     * get one synthetic cancelled stop, only if this call removed the admission; a backend terminal that
     * arrives later is dropped with its admission.
     */
    private fun retireWedgedAdmission(ownerSessionId: Long, capture: AdmittedCapture) {
        if (capture.controller === nativeAndroidVoiceInputController) recordNativeTeardownSettle(ownerSessionId)
        // A backend terminal can retire this admission between the timeout and here; that terminal has
        // already reported the stop, so a synthetic one would duplicate it.
        // Correct only because the terminal route and this wedge wait both run on Main and outbound is
        // UNLIMITED (tryEmit never suspends), so the terminal's emit-then-retire cannot interleave with
        // this retire-then-emit. Revisit if either assumption changes.
        retireAdmission(ownerSessionId) ?: return
        val stopped = VoiceInputEvent.ListeningStopped(capture.mode, ownerSessionId, cancelled = true)
        outbound.tryEmit(stopped)
        micLifecycle.tryEmit(stopped)
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
        pendingAsr4allStart.getAndSet(null)?.job?.cancel()
        return try {
            withContext(Dispatchers.Main.immediate) {
                startMutex.withLock {
                    if (gen != generation.get()) return@withLock VoiceInputStartResult.Cancelled()
                    val selectedBeforeTeardown = voiceInputPreferences.selectedEngine.first()
                    val settleNativeToLocal =
                        selectedBeforeTeardown.isSherpaFamily || selectedBeforeTeardown.usesAsr4all()
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
                    if (!engine.usesAsr4all()) {
                        asr4allVoiceInputController.releaseEngine()
                    }
                    if (gen != generation.get() || voiceInputPreferences.selectedEngine.first() != engine) {
                        return@withLock VoiceInputStartResult.Cancelled()
                    }
                    val result = when (engine) {
                        VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback, gen, onSessionAdmitted)
                        VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback, gen, onSessionAdmitted)
                        VoiceInputEngine.Asr4all -> startAsr4all(mode, gen, onSessionAdmitted)
                        else -> if (voiceInputPreferences.allowsSherpaRuntime()) {
                            startSherpa(mode, gen, onSessionAdmitted)
                        } else {
                            VoiceInputStartResult.Unavailable("This speech engine is not available in this release.")
                        }
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
        val asr4allReady = asr4allVoiceInputController.isAvailable()
        // An offline model wins without the online fallback, so the native availability check is skipped then.
        val offlineModelWins = !allowOnlineFallback && asr4allReady
        // Use the cached locale check when there is one; only run the slower live check for
        // interactive captures that have no cached answer yet.
        var ranLiveCheck = false
        // Null when the check is skipped, so the log and JandalEvents say "skipped" rather than a false "not ready" (#2051).
        val nativeCheck: Boolean? = if (offlineModelWins) {
            null
        } else {
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
            availability.blockingReason == null &&
                availability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
        }
        val nativeLog = nativeCheck?.toString() ?: "skipped"
        val route = resolveAutoSttRoute(
            nativeOnDeviceReady = nativeCheck == true,
            asr4allReady = asr4allReady,
            allowOnlineFallback = allowOnlineFallback,
        )
        Log.i(
            TAG,
            "Auto STT route=$route mode=$mode native=$nativeLog " +
                "asr4allReady=$asr4allReady " +
                "allowOnlineFallback=$allowOnlineFallback",
        )
        JandalEvents.record(
            "voice", "stt_route", "route" to route,
            "native_ready" to nativeLog, "asr4all_ready" to asr4allReady,
            "online_fallback" to allowOnlineFallback,
        )
        // Auto keeps a loaded asr4all engine for its next start. Every other route releases it first.
        if (route != AutoSttRoute.Asr4all) {
            asr4allVoiceInputController.releaseEngine()
            if (gen != generation.get()) return VoiceInputStartResult.Cancelled()
        }
        return when (route) {
            AutoSttRoute.Native -> {
                // The live check's throwaway recognizer is torn down asynchronously; starting
                // a session immediately can lose the shared on-device service
                // (ERROR_SERVER_DISCONNECTED on the S23 Ultra), so let it settle first.
                if (ranLiveCheck) delay(LIVE_CHECK_SETTLE_MS)
                startNative(mode, allowOnlineFallback, gen, onSessionAdmitted)
            }
            AutoSttRoute.Asr4all -> startAsr4all(mode, gen, onSessionAdmitted)
            AutoSttRoute.Unavailable -> VoiceInputStartResult.Unavailable(AUTO_STT_UNAVAILABLE_MESSAGE)
        }
    }

    /**
     * Starts Android native. If it cannot start at all and the asr4all model is ready, the capture
     * starts on asr4all for this request. The saved engine setting is not changed.
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
        val nativeResult = try {
            nativeAndroidVoiceInputController.startListening(
                mode,
                allowOnlineFallback,
                onSessionAdmitted = { sessionId ->
                    nativeSessionId = sessionId
                    nativeStart.sessionId = sessionId
                    admitSession(gen, nativeAndroidVoiceInputController, sessionId, mode, onSessionAdmitted)
                },
                isWanted = { gen == generation.get() },
            )
        } catch (e: Throwable) {
            // A throw from the native start must still release the held terminal and the fallback slot.
            endNativeStart(nativeStart)
            throw e
        }
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
        // A native failure stays a native failure: release builds never start asr4all here, and debug starts it only when the model can take the request.
        if (!voiceInputPreferences.allowsSherpaRuntime() || !asr4allVoiceInputController.isAvailable()) return nativeResult
        // Keep the backend mapping until its post-cleanup terminal is routed. A start-time
        // fallback can suspend while that physical stop is queued.
        // Its post-cleanup terminal retires the Native mapping after fallback suspension.
        Log.w(TAG, "Android native unavailable at start; trying asr4all: ${(nativeResult as? VoiceInputStartResult.Unavailable)?.message}")
        activeController.value = asr4allVoiceInputController
        var fallbackSessionId: Long? = null
        var fallbackResult: VoiceInputStartResult? = null
        val offline = try {
            runAsr4allStart(gen) {
                val result = asr4allVoiceInputController.startListening(
                    mode,
                    isWanted = { gen == generation.get() },
                    onSession = { sessionId ->
                        fallbackSessionId = sessionId
                        admitSession(gen, asr4allVoiceInputController, sessionId, mode, onSessionAdmitted)
                    },
                )
                fallbackResult = result
                val started = result as? VoiceInputStartResult.Started
                when {
                    started == null -> null
                    gen != generation.get() -> {
                        asr4allVoiceInputController.cancelListeningForSession(started.captureSessionId)
                        null
                    }
                    else -> started
                }
            }
        } catch (e: CancellationException) {
            endNativeStart(nativeStart)
            throw e
        }
        if (nativeSessionId != null) settleNativeFallback(nativeStart, asr4allStarted = offline != null)
        if (offline != null) {
            recordAsr4allFallback(
                reason = NATIVE_NOT_READY,
                mode = mode,
                jandalReason = NativeFallbackReason.NATIVE_NOT_READY,
            )
            postNotice(VoiceNotice(offline.captureSessionId, ASR4ALL_START_NOTICE))
            return offline
        }
        activeController.value = nativeAndroidVoiceInputController
        if (fallbackResult == null) {
            fallbackSessionId?.let(asr4allVoiceInputController::cancelListeningForSession)
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
     * Settles a Native start that was admitted and then handed to asr4all. If the fallback
     * started, the Native terminal is superseded: drop it when already held, else drop it on arrival.
     */
    private fun settleNativeFallback(start: NativeStartFallback, asr4allStarted: Boolean) {
        if (!asr4allStarted) {
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
     * Holds the terminal of a Native start while its asr4all fallback decides. Consumers end
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
        onSessionAdmitted: (Long) -> Unit,
    ): VoiceInputStartResult {
        asr4allStartsInFlight.incrementAndGet()
        try {
            activate(asr4allVoiceInputController)
            val result = asr4allVoiceInputController.startListening(
                mode = mode,
                isWanted = { gen == generation.get() },
                onSession = { sessionId ->
                    admitSession(gen, asr4allVoiceInputController, sessionId, mode, onSessionAdmitted)
                },
            )
            if (result is VoiceInputStartResult.Started && gen != generation.get()) {
                asr4allVoiceInputController.cancelListeningForSession(result.captureSessionId)
                return VoiceInputStartResult.Cancelled(result.captureSessionId)
            }
            return result
        } finally {
            asr4allStartsInFlight.decrementAndGet()
        }
    }


    /** Prior admitted backends have already been stopped and awaited by startListeningInternal. */
    private fun activate(controller: VoiceInputController) {
        activeController.value = controller
    }

    /**
     * Runs [block] as the cancellable asr4all start for the capture with generation [gen].
     * [stopListening] and a newer [startListening] cancel it. Returns null when the capture
     * was stopped or superseded, or when [block] failed or returned null; callers compare
     * [gen] with [generation] to tell these apart.
     */
    private suspend fun runAsr4allStart(
        gen: Long,
        block: suspend () -> VoiceInputStartResult.Started?,
    ): VoiceInputStartResult.Started? {
        val job = pipelineScope.async(start = CoroutineStart.LAZY) { block() }
        val pending = PendingAsr4allStart(gen, job)
        pendingAsr4allStart.set(pending)
        return try {
            // Publish first, then re-check: a stop that ran before the publish bumped the
            // generation; a stop after it cancels the job.
            if (gen != generation.get()) {
                Log.i(TAG, "asr4all start skipped: capture already stopped or superseded")
                job.cancel()
                null
            } else {
                job.await()
            }
        } catch (e: CancellationException) {
            // Covers both a cancelled job (stop / newer start) and a cancelled caller; in the
            // latter case the job must not keep loading asr4all on its own.
            job.cancel()
            currentCoroutineContext().ensureActive()
            Log.i(TAG, "asr4all start cancelled: capture stopped or superseded")
            null
        } catch (e: Exception) {
            Log.e(TAG, "asr4all failed to start", e)
            null
        } finally {
            pendingAsr4allStart.compareAndSet(pending, null)
        }
    }

    /**
     * Routes one controller event. Each event is isolated: if routing throws, the original
     * event is still delivered, so a terminal Error or ListeningStopped is never dropped.
     */
    private suspend fun route(controller: VoiceInputController, event: VoiceInputEvent) {
        // #2027: mic arbitration sees lifecycle events from every controller, including one replaced by a
        // hand-off, so a replaced native session still gets its ListeningStopped.
        if (event is VoiceInputEvent.ListeningRequested ||
            event is VoiceInputEvent.ListeningStarted ||
            event is VoiceInputEvent.ListeningStopped
        ) {
            micLifecycle.tryEmit(event)
        }
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


    private fun isTerminal(event: VoiceInputEvent): Boolean =
        event is VoiceInputEvent.ListeningStopped


    private suspend fun routeEvent(controller: VoiceInputController, event: VoiceInputEvent) {
        val backendKey = BackendCaptureKey(controller, event.captureSessionId)
        val backendOwnerSessionId = synchronized(captureOwnerLock) { backendAdmissions[backendKey] }
        if (backendOwnerSessionId == null) return
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
                    handOffToAsr4all(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "asr4all hand-off failed; surfacing the native error", e)
                    false
                }
                // The native error is not surfaced: the capture continues on asr4all, or the
                // user stopped or restarted during the hand-off.
                if (handled) return
                if (!handled && !logicalCaptureStillCurrent()) return
            }
        }

        val current = handoff
        if (current != null && controller === asr4allVoiceInputController &&
            event.captureSessionId == current.asr4allSessionId
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
        // allowsSherpaRuntime() is the debug-build gate; it also keeps the mid-session asr4all hand-off debug-only.
        return voiceInputPreferences.allowsSherpaRuntime() &&
            openGeneration != -1L &&
            openGeneration == generation.get() &&
            error.captureSessionId == expectedNativeSessionId &&
            facts.sessionId == error.captureSessionId &&
            isAsr4allHandoffEligible(error.category, facts.speechProgress)
    }

    /**
     * Replaces the failed native capture with asr4all for the same mode.
     *
     * Returns true when the native error must not be surfaced: the capture moved to asr4all,
     * or the user stopped or restarted during the hand-off. Returns false when asr4all could
     * not start, so the caller surfaces the native error and native stays active.
     */
    private suspend fun handOffToAsr4all(error: VoiceInputEvent.Error): Boolean {
        val gen = openGeneration
        val nativeId = error.captureSessionId
        val reason = error.category.orEmpty()
        val readinessAnnounced = nativeFacts?.readinessAnnounced == true
        // Tear native down first, then let its shared on-device service settle.
        nativeAndroidVoiceInputController.stopListening()
        val offline = runAsr4allStart(gen) {
            delay(NATIVE_TEARDOWN_SETTLE_MS)
            startMutex.withLock {
                if (gen != generation.get() || !asr4allVoiceInputController.isAvailable()) return@withLock null
                Log.w(TAG, "Android native failed ($reason); handing capture to asr4all for this session")
                val previousAdmission = synchronized(captureOwnerLock) { admittedControllers[nativeId] }
                    ?: return@withLock null
                var asr4allSessionId: Long? = null
                var backendRegistered = false
                val result = asr4allVoiceInputController.startListening(
                    error.mode,
                    isWanted = { gen == generation.get() },
                    onSession = { backendId ->
                        asr4allSessionId = backendId
                        backendRegistered = synchronized(captureOwnerLock) {
                            val current = admittedControllers[nativeId]
                            if (current == null) {
                                false
                            } else {
                                admittedControllers[nativeId] = current.copy(
                                    controller = asr4allVoiceInputController,
                                    backendSessionId = backendId,
                                )
                                backendAdmissions[BackendCaptureKey(asr4allVoiceInputController, backendId)] =
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
                                BackendCaptureKey(asr4allVoiceInputController, checkNotNull(asr4allSessionId)),
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
                if (asr4allSessionId != started.captureSessionId || !backendRegistered) {
                    asr4allVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                if (gen != generation.get()) {
                    asr4allVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                // Admission is already mapped to the logical native owner before asr4all can
                // emit readiness or a terminal. Commit only if that owner is still current.
                val remapped = synchronized(captureOwnerLock) {
                    val current = admittedControllers[nativeId]
                    gen == generation.get() &&
                        captureOwner?.sessionId == nativeId &&
                        current?.controller === asr4allVoiceInputController &&
                        current.backendSessionId == started.captureSessionId
                }
                if (!remapped) {
                    asr4allVoiceInputController.cancelListeningForSession(started.captureSessionId)
                    return@withLock null
                }
                activeController.value = asr4allVoiceInputController
                handoff = Asr4allHandoff(
                    nativeSessionId = nativeId,
                    asr4allSessionId = started.captureSessionId,
                    readinessAnnounced = readinessAnnounced,
                )
                recordAsr4allFallback(
                    reason = reason,
                    mode = error.mode,
                    jandalReason = NativeFallbackReason.NATIVE_ERROR,
                )
                val message = if (error.category == NATIVE_STALLED) ASR4ALL_STALL_NOTICE else ASR4ALL_START_NOTICE
                postNotice(VoiceNotice(nativeId, message))
                started
            }
        }
        if (offline != null) return true
        if (gen != generation.get()) return true
        Log.w(TAG, "asr4all hand-off did not start; surfacing native error: ${error.message}")
        return false
    }

    private fun postNotice(notice: VoiceNotice) {
        noticeFlow.tryEmit(notice)
    }

    private fun recordAsr4allFallback(reason: String, jandalReason: NativeFallbackReason, mode: VoiceCaptureMode) {
        AcousticJournalBridge.record(
            type = AcousticEventType.STT_ASR4ALL_FALLBACK,
            metadata = { mapOf("reason" to reason, "mode" to mode.name) },
        )
        JandalEvents.record("voice", "asr4all_fallback", "reason" to jandalReason, "mode" to mode)
    }

    /** Thread-safe: see the class concurrency notes. */
    override fun stopListening() {
        val invalidation = synchronized(captureOwnerLock) {
            val (_, previousGenerationInvalidation) = advanceGenerationLocked()
            if (captureOwner?.sessionId == null) captureOwner = null
            updateCaptureActivityLocked()
            previousGenerationInvalidation
        }
        invalidation.complete(Unit)
        pendingAsr4allStart.getAndSet(null)?.job?.cancel()
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
        asr4allVoiceInputController.supersedeCapture()
    }

    /**
     * Wake-word verification always runs through the dedicated Whisper wake
     * recognizer ([SherpaOnnxVoiceInputController.transcribeBlocking]),
     * independent of the selected interactive STT engine. The interactive
     * selection and [activeController] are deliberately untouched so the
     * currently selected engine keeps its normal capture behaviour. Whisper
     * tiny.en is the only wake verifier in every build.
     */
    override suspend fun transcribeBlocking(pcm: ShortArray): String? =
        sherpaOnnxVoiceInputController.transcribeBlocking(pcm)
}
