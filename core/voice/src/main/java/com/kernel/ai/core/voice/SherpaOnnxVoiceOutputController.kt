package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spike implementation of [VoiceOutputController] backed by Sherpa-ONNX + downloaded Piper
 * English voices.
 *
 * **Design goals (spike):**
 * - Zero direct imports from `com.k2fsa.sherpa.onnx.*` — all Sherpa access goes through
 *   Java reflection so this file compiles even when the AAR has not been downloaded yet.
 * - Graceful [VoiceOutputResult.Unavailable] when either the AAR or the downloaded voice pack is
 *   absent.
 * - Streams PCM floats to [AudioTrack] in chunks so the audio focus and stop() interruptibility
 *   work the same way as [AndroidTextToSpeechController].
 *
 * **Prerequisites (not committed):**
 * Run `scripts/setup-sherpa-tts-spike.sh` to:
 *   1. Download `sherpa-onnx-1.13.0.aar` → `third_party/sherpa-onnx/`
 *
 * Voice packs are provisioned on device from Settings → Voice and extracted to [Context.getFilesDir]
 * because Sherpa-ONNX needs real filesystem paths, not `AssetManager` URIs for those files.
 *
 * When the AAR/voice pack are absent the class initialises cleanly to [InitState.UNAVAILABLE] and
 * [FallbackVoiceOutputController] will route to [AndroidTextToSpeechController] instead.
 */
@Singleton
class SherpaOnnxVoiceOutputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : VoiceOutputController {
    private data class ActiveStreamingPlayback(
        val token: Long,
        val chunks: Channel<StreamingChunk>,
        val worker: Job,
    )

    /** Text to synthesise, or (with [pauseMs] > 0 and blank text) silence between two parts of a reply. */
    private data class StreamingChunk(
        val text: String,
        val isFinal: Boolean,
        val pauseMs: Long = 0L,
    )

    // ── Coroutine scope on IO — never Main ──────────────────────────────────
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = _events.asSharedFlow()

    /** Live speech rate from user preferences; default 0.85. Range 0.5–1.5. */
    private val sherpaSpeed: StateFlow<Float> = voiceOutputPreferences.sherpaSpeed
        .stateIn(scope, SharingStarted.Eagerly, 0.85f)

    /** Live pitch from user preferences; default 1.0. Range 0.5–2.0. */
    private val sherpaPitch: StateFlow<Float> = voiceOutputPreferences.voicePitch
        .stateIn(scope, SharingStarted.Eagerly, 1.0f)

    /** PCM amplification gain; default 1.5 to compensate for Sherpa's quiet output. Range 0.5–3.0. */
    private val sherpaGain: StateFlow<Float> = voiceOutputPreferences.voiceGain
        .stateIn(scope, SharingStarted.Eagerly, 1.5f)

    /** Active speaker ID for multi-speaker voices (e.g. VCTK). Stored in preferences, default 0. */
    private val activeSpeakerId: StateFlow<Int> = voiceOutputPreferences.activeSpeakerId
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // ── Lifecycle state ──────────────────────────────────────────────────────
    private enum class InitState { UNINITIALIZED, AVAILABLE, UNAVAILABLE }

    @Volatile private var initState = InitState.UNINITIALIZED
    @Volatile private var initializedVoice: SherpaPiperVoice? = null

    /** Reflected handle to `OfflineTts` instance; non-null iff [initState] == AVAILABLE. */
    @Volatile private var ttsInstance: Any? = null

    /** Reflected `generate(String, Int, Float): GeneratedAudio` method. */
    @Volatile private var generateMethod: java.lang.reflect.Method? = null

    // ── Verbose logging ──────────────────────────────────────────────────────
    private val verboseLoggingEnabled: StateFlow<Boolean> = voiceOutputPreferences.verboseLogging
        .stateIn(scope, SharingStarted.Eagerly, false)

    // ── Playback cancellation ────────────────────────────────────────────────
    @Volatile private var stopped = false
    @Volatile private var nextPlaybackToken = 0L
    private val speakingStopGate = SpeakingStopGate()
    @Volatile private var activeStreamingPlayback: ActiveStreamingPlayback? = null
    private val playbackLock = Any()

    /**
     * Generation counter for non-streaming [speak] calls. Incremented before each new
     * [playOnAudioTrack] invocation so any previous write loop can detect it has been superseded
     * and abort, preventing stale audio from bleeding into the new playback.
     */
    private val nonStreamingPlaybackGeneration = AtomicLong(0)

    // ── AudioFocus ───────────────────────────────────────────────────────────
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    // ── VoiceOutputController ────────────────────────────────────────────────

    /** Returns 0 for single-speaker voices; clamps [sid] to valid range for multi-speaker. */
    private fun effectiveSid(speakerCount: Int, sid: Int): Int =
        if (speakerCount > 1) sid.coerceIn(0, speakerCount - 1) else 0

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        initialize(voiceOutputPreferences.selectedSherpaVoice.first())
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult =
        withContext(Dispatchers.IO) {
            PronunciationLexicon.ensureLoaded(context)
            val initResult: VoiceOutputResult
            val speakerCount: Int
            val voiceSid: Int
            val voice = voiceOutputPreferences.selectedSherpaVoice.first()
            initResult = initialize(voice)
            speakerCount = voice.speakerCount
            voiceSid = activeSpeakerId.value
            if (initResult is VoiceOutputResult.Unavailable) return@withContext initResult

            val tts = ttsInstance
                ?: return@withContext VoiceOutputResult.Unavailable("Sherpa TTS instance missing.")
            val genMethod = generateMethod
                ?: return@withContext VoiceOutputResult.Unavailable("Sherpa generate() not found.")

            stopped = true
            clearStreamingPlayback()
            stopped = false
            val myGeneration = nonStreamingPlaybackGeneration.incrementAndGet()

            // SpeakingStopped is paired to this reply's SpeakingStarted by the gate (SpeakingStopGate).
            val stopToken = allocatePlaybackToken()
            val effectiveSid = effectiveSid(speakerCount, voiceSid)
            return@withContext try {
                // Reflect: GeneratedAudio audio = tts.generate(text, sid, speed)
                // Synthesis can take 1-3s for medium models, longer for high-quality ones;
                // only emit SpeakingStarted once audio is ready and playback is about to begin.
                val synthStart = System.currentTimeMillis()
                val spokenText = SherpaSpeechText.prepare(request.text)
                val audioResult = genMethod.invoke(tts, spokenText, effectiveSid, sherpaSpeed.value)
                    ?: return@withContext VoiceOutputResult.Unavailable("Sherpa returned null audio.")
                if (verboseLoggingEnabled.value) Log.d(
                    TAG,
                    "Sherpa synthesis: ${System.currentTimeMillis() - synthStart}ms for " +
                        "${spokenText.length} chars, sid=$effectiveSid",
                )

                val samples = reflectGetSamples(audioResult)
                val sampleRate = reflectGetSampleRate(audioResult)

                if (!stopped) {
                    requestAudioFocus()
                    VoiceOutputTrace.speakingStarted("sherpa", spokenText)
                    VoiceOutputTrace.logSpeakingStarted(
                        "sherpa",
                        spokenText.length,
                    )
                    speakingStopGate.markStarted(stopToken)
                    _events.emit(VoiceOutputEvent.SpeakingStarted(request.text))
                    playOnAudioTrack(samples, sampleRate, myGeneration)
                }
                releaseAudioFocus()
                if (speakingStopGate.claimStopped(stopToken)) _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Spoken
            } catch (e: Exception) {
                Log.e(TAG, "Sherpa generate() failed", e)
                releaseAudioFocus()
                if (speakingStopGate.claimStopped(stopToken)) _events.emit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Unavailable("Sherpa generate failed: ${e.message}")
            }
        }

    override suspend fun openStreamingSession(
        request: VoiceSpeakRequest,
    ): VoiceOutputStreamingSession = openSession(request, held = false)

    override suspend fun openPreSynthesisedSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession =
        openSession(request, held = true)

    /**
     * [held] sessions synthesise their first chunk but play nothing until [VoiceOutputStreamingSession.release]
     * (#1923). A held session cancelled before release never reaches `markStarted`, so it emits no SpeakingStopped.
     */
    private suspend fun openSession(request: VoiceSpeakRequest, held: Boolean): VoiceOutputStreamingSession = withContext(Dispatchers.IO) {
        PronunciationLexicon.ensureLoaded(context)
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        val initResult = initialize(voice)
        val speakerCount = voice.speakerCount
        val activeSid = activeSpeakerId.value
        if (initResult is VoiceOutputResult.Unavailable) {
            return@withContext object : VoiceOutputStreamingSession {
                override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult = initResult
            }
        }

        ensureActive()
        val playbackToken = allocatePlaybackToken()
        stopped = true
        clearStreamingPlayback()
        stopped = false
        val playbackGate = if (held) CompletableDeferred<VoiceSpeakRequest?>() else null
        val chunks = Channel<StreamingChunk>(Channel.UNLIMITED)
        val outcome = CompletableDeferred<StreamPlaybackOutcome>()
        val worker = scope.launch {
            runStreamingPlayback(
                playbackToken = playbackToken,
                request = request,
                chunks = chunks,
                speakerCount = speakerCount,
                activeSid = activeSid,
                outcome = outcome,
                playbackGate = playbackGate,
            )
        }
        // A worker cancelled before it ran, or one that returned early, still reports an outcome.
        worker.invokeOnCompletion { outcome.complete(StreamPlaybackOutcome.CANCELLED) }
        synchronized(playbackLock) {
            activeStreamingPlayback = ActiveStreamingPlayback(
                token = playbackToken,
                chunks = chunks,
                worker = worker,
            )
        }

        return@withContext object : VoiceOutputStreamingSession, ReportsStreamPlaybackOutcome {
            private var closed = false

            override val playbackOutcome: Deferred<StreamPlaybackOutcome> = outcome

            override suspend fun release(request: VoiceSpeakRequest): Boolean {
                val gate = playbackGate ?: return false
                if (!isStreamingPlaybackActive(playbackToken)) return false
                // The request travels through the gate, so the consumer reads its text without a separate field.
                gate.complete(request)
                return gate.await() != null
            }

            override suspend fun cancel() {
                if (playbackGate == null) {
                    append("", isFinal = true)
                    return
                }
                // A held reply that was never released plays nothing; its worker's unwind emits no
                // SpeakingStopped because markStarted never ran. A released reply is stopped like any playback.
                closed = true
                synchronized(playbackLock) {
                    if (activeStreamingPlayback?.token == playbackToken) clearStreamingPlayback()
                }
            }

            override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                if (closed) return VoiceOutputResult.Spoken
                val normalized = text.trim()
                if (normalized.isNotBlank()) {
                    // trySend: a stop() closes the channel; send() would throw ClosedSendChannelException.
                    if (chunks.trySend(StreamingChunk(text = normalized, isFinal = isFinal)).isClosed) {
                        return VoiceOutputResult.Spoken
                    }
                } else if (isFinal) {
                    chunks.close()
                }
                if (isFinal) {
                    closed = true
                    chunks.close()
                }
                return VoiceOutputResult.Spoken
            }

            override suspend fun appendPause(durationMs: Long) {
                if (closed || durationMs <= 0L) return
                chunks.trySend(StreamingChunk(text = "", isFinal = false, pauseMs = durationMs))
            }
        }
    }

    override fun stop() {
        stopped = true
        releaseAudioFocus()
        clearStreamingPlayback()
    }

    // ── Initialisation ───────────────────────────────────────────────────────

    /**
     * Idempotent lazy initialisation.  Cached in [initState] after first attempt.
     * Returns [VoiceOutputResult.Spoken] when Sherpa is ready, [VoiceOutputResult.Unavailable]
     * otherwise (AAR missing, assets missing, or any reflection error).
     * Synchronized: warm-up and the first reply can arrive together, and two concurrent inits each
     * built a native OfflineTts (two "TTS initialised" lines on the S23U, #1942).
     */
    @Synchronized
    private fun initialize(voice: SherpaPiperVoice): VoiceOutputResult {
        if (initializedVoice != voice) {
            resetForVoice(voice)
        }
        // If init previously failed only because the voice pack was missing, retry now that
        // it may have been downloaded since. JNI/AAR failures stay permanently cached.
        if (initState == InitState.UNAVAILABLE && voice.isDownloaded(context)) {
            initState = InitState.UNINITIALIZED
        }
        return when (initState) {
            InitState.AVAILABLE -> VoiceOutputResult.Spoken
            InitState.UNAVAILABLE -> VoiceOutputResult.Unavailable(unavailableMessage(voice))
            InitState.UNINITIALIZED -> doInitialize(voice)
        }
    }

    private fun doInitialize(voice: SherpaPiperVoice): VoiceOutputResult {
        // libsherpa-onnx-jni.so was built against ORT as a shared library and calls
        // OrtGetApiBase() at load time. On Android the linker resolves that symbol from
        // whatever libonnxruntime.so is already open in this process. If ORT hasn't been
        // loaded yet (e.g. inference engine not yet warm) the dlopen fails with
        // UnsatisfiedLinkError. Loading it here is idempotent and ensures the symbol is
        // present before Class.forName() triggers Sherpa's static <clinit>.
        try {
            System.loadLibrary("onnxruntime")
        } catch (_: UnsatisfiedLinkError) {
            // Already loaded or not present — either way, proceed and let Class.forName
            // fail gracefully below if Sherpa truly can't find ORT.
        }

        // 1. Check AAR is on classpath via Class.forName (no import needed).
        // UnsatisfiedLinkError must also be caught: Class.forName triggers OfflineTts.<clinit>
        // which calls System.loadLibrary("sherpa-onnx-jni"). That JNI lib resolves OrtGetApiBase
        // from libonnxruntime.so at dlopen time; if the linker can't find it (namespace isolation,
        // version mismatch) Android throws UnsatisfiedLinkError — an Error, not an Exception —
        // which would otherwise escape the coroutine and crash the process.
        // ExceptionInInitializerError and NoClassDefFoundError are the JVM wrapper forms that
        // Class.forName can surface when the static initialiser itself throws.
        val ttsClass = try {
            Class.forName(SHERPA_OFFLINE_TTS_CLASS)
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Sherpa-ONNX not on classpath — AAR not present in APK.")
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable("Sherpa-ONNX runtime not available for ${voice.displayName}.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Sherpa-ONNX JNI failed to load (ORT symbol not found) — falling back to Android TTS", e)
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable("Sherpa-ONNX JNI unavailable (${e.message?.take(80)})")
        } catch (e: ExceptionInInitializerError) {
            Log.e(TAG, "Sherpa-ONNX class init failed — falling back to Android TTS", e)
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable("Sherpa-ONNX JNI unavailable (${e.message?.take(80)})")
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Sherpa-ONNX class not found after init failure — falling back to Android TTS", e)
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable("Sherpa-ONNX JNI unavailable (${e.message?.take(80)})")
        }

        // 2. Use the voice pack downloaded to internal storage.
        val downloadedDir = voice.voiceDir(context)
        val modelDir: File = if (voice.isDownloaded(context)) {
            Log.i(TAG, "Using downloaded voice pack at ${downloadedDir.absolutePath}")
            downloadedDir
        } else {
            Log.i(
                TAG,
                "Voice pack not downloaded for ${voice.displayName}. Use Settings → Voice to download it.",
            )
            initState = InitState.UNAVAILABLE
            return VoiceOutputResult.Unavailable(
                "Voice pack not yet downloaded for ${voice.displayName}. Tap Download in Settings → Voice.",
            )
        }

        return try {
            val config = buildOfflineTtsConfig(modelDir)
            val configClass = Class.forName(SHERPA_OFFLINE_TTS_CONFIG_CLASS)
            val ctor = ttsClass.getConstructor(
                android.content.res.AssetManager::class.java,
                configClass,
            )
            // Always use Sherpa's file-backed native path. Both downloaded packs and legacy
            // asset packs are normalised onto the real filesystem before init.
            val instance = ctor.newInstance(null, config)
            // Cache reflected generate() — signature: generate(String, int, float)
            val gen = ttsClass.getDeclaredMethod(
                "generate",
                String::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            ttsInstance = instance
            generateMethod = gen
            initState = InitState.AVAILABLE
            Log.i(TAG, "Sherpa-ONNX TTS initialised — voice: ${voice.downloadKey} at ${modelDir.absolutePath}")
            VoiceOutputResult.Spoken
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa-ONNX TTS init failed", e)
            initState = InitState.UNAVAILABLE
            VoiceOutputResult.Unavailable("Sherpa-ONNX init failed for ${voice.displayName}: ${e.message}")
        }
    }

    private fun resetForVoice(voice: SherpaPiperVoice) {
        clearStreamingPlayback()
        ttsInstance = null
        generateMethod = null
        initState = InitState.UNINITIALIZED
        initializedVoice = voice
    }

    private suspend fun runStreamingPlayback(
        playbackToken: Long,
        request: VoiceSpeakRequest,
        chunks: Channel<StreamingChunk>,
        speakerCount: Int,
        activeSid: Int,
        outcome: CompletableDeferred<StreamPlaybackOutcome>,
        playbackGate: CompletableDeferred<VoiceSpeakRequest?>?,
    ) {
        val tts = ttsInstance ?: return
        val genMethod = generateMethod ?: return
        val genWithCallback = generateWithCallbackMethod(tts.javaClass)
        // Sherpa runs a chunk one sentence at a time and calls this after each; 0 stops it there. A
        // native generate() cannot be interrupted, so without this a superseded chunk kept synthesising
        // in full and slowed the next reply's first chunk (S23U: 1.9 s → 3.9 s, #1942).
        val continueWhileActive = SherpaContinueCallback { !stopped && isStreamingPlaybackActive(playbackToken) }
        // For single-speaker voices always use sid=0; for multi-speaker, clamp to valid range.
        val effectiveSid = effectiveSid(speakerCount, activeSid)
        var emittedStarted = false
        var requestedAudioFocus = false

        // Pipeline: synthesis and playback run as concurrent coroutines connected by a bounded
        // channel. While chunk N is playing, chunk N+1 is already being synthesised. This
        // eliminates the per-chunk gap that occurred when synthesis only started after the
        // previous chunk finished playing (e.g. 6–8 s gaps for LessacHigh).
        // Capacity of 2 lets synthesis stay at most 2 chunks ahead of playback, capping memory
        // usage while still guaranteeing the next chunk is ready the moment the current one ends.
        val audioQueue = Channel<SynthesizedChunk>(capacity = 2)
        val timing = if (verboseLoggingEnabled.value) StreamTimingLog(playbackToken) else null
        var synthesisFailed = false
        var playedToEnd = false
        try {
            coroutineScope {
                // ── Synthesis producer ────────────────────────────────────────────────────
                val synthJob = launch {
                    try {
                        for (chunk in chunks) {
                            if (stopped || !isStreamingPlaybackActive(playbackToken)) break
                            if (chunk.pauseMs > 0L) {
                                audioQueue.send(SynthesizedChunk.pause(chunk.pauseMs))
                                continue
                            }
                            val synthStart = System.currentTimeMillis()
                            val spokenText = SherpaSpeechText.prepare(chunk.text, chunkEnd = chunk.isFinal)
                            val audioResult = try {
                                if (genWithCallback != null) {
                                    genWithCallback.invoke(tts, spokenText, effectiveSid, sherpaSpeed.value, continueWhileActive)
                                } else {
                                    genMethod.invoke(tts, spokenText, effectiveSid, sherpaSpeed.value)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Sherpa streaming generate() failed", e)
                                null
                            }
                            if (audioResult == null) {
                                synthesisFailed = true
                                break
                            }
                            val synthesized = SynthesizedChunk(
                                spokenText,
                                reflectGetSamples(audioResult),
                                reflectGetSampleRate(audioResult),
                            )
                            timing?.synthesized(synthesized, synthStart, sid = effectiveSid)
                            if (!isStreamingPlaybackActive(playbackToken)) {
                                timing?.discarded(synthesized)
                                break
                            }
                            audioQueue.send(synthesized)
                            if (chunk.isFinal) break
                        }
                    } finally {
                        // A held session that fails to synthesise must not be released into silence.
                        playbackGate?.complete(null)
                        audioQueue.close()
                    }
                }

                // ── Playback consumer ─────────────────────────────────────────────────────
                launch {
                    var stoppedEarly = false
                    // A held session waits here until release(), which supplies the text SpeakingStarted reports;
                    // null when synthesis ended first.
                    val releasedRequest = if (playbackGate == null) request else playbackGate.await()
                    for (chunk in audioQueue) {
                        if (releasedRequest == null || stopped || !isStreamingPlaybackActive(playbackToken)) {
                            stoppedEarly = true
                            break
                        }
                        if (chunk.pauseMs > 0L) {
                            // Silence between parts of a reply (SpeechPause, #1942): nothing is written, so
                            // the previous track has drained and the next one has not started.
                            timing?.paused(chunk.pauseMs)
                            delay(chunk.pauseMs)
                            continue
                        }
                        // Every chunk, with its own text: a token-streamed session opens with
                        // empty request text.
                        VoiceOutputTrace.speakingStarted("sherpa_stream", chunk.text)
                        if (!emittedStarted) {
                            VoiceOutputTrace.logSpeakingStarted(
                                "sherpa",
                                chunk.text.length,
                            )
                            speakingStopGate.markStarted(playbackToken)
                            _events.emit(VoiceOutputEvent.SpeakingStarted(releasedRequest.text))
                            emittedStarted = true
                        }
                        if (!requestedAudioFocus) {
                            requestAudioFocus()
                            requestedAudioFocus = true
                        }
                        timing?.playStarted(chunk)
                        playOnAudioTrack(chunk.samples, chunk.sampleRate, playbackToken = playbackToken)
                        timing?.playEnded(chunk)
                    }
                    playedToEnd = !stoppedEarly && !synthesisFailed && !stopped && isStreamingPlaybackActive(playbackToken)
                    // Playback finished (or stopped early) — cancel synthesis so it doesn't
                    // keep producing chunks that will never be consumed.
                    synthJob.cancel()
                }
            }
        } catch (e: Throwable) {
            if (e !is CancellationException) synthesisFailed = true
            throw e
        } finally {
            // Runs exactly once per worker, including when stop() cancels it. The gate, not
            // activeStreamingPlayback (which stop() clears first), decides whether this unwind ends speech.
            releaseAudioFocus()
            finishStreamingPlayback(playbackToken)
            outcome.complete(
                when {
                    synthesisFailed -> StreamPlaybackOutcome.FAILED
                    playedToEnd -> StreamPlaybackOutcome.COMPLETED
                    else -> StreamPlaybackOutcome.CANCELLED
                },
            )
            if (speakingStopGate.claimStopped(playbackToken)) _events.tryEmit(VoiceOutputEvent.SpeakingStopped)
        }
    }

    private fun allocatePlaybackToken(): Long = synchronized(playbackLock) {
        nextPlaybackToken += 1L
        nextPlaybackToken
    }

    private fun isStreamingPlaybackActive(playbackToken: Long): Boolean = synchronized(playbackLock) {
        activeStreamingPlayback?.token == playbackToken
    }

    private fun finishStreamingPlayback(playbackToken: Long) = synchronized(playbackLock) {
        if (activeStreamingPlayback?.token == playbackToken) activeStreamingPlayback = null
    }

    private fun clearStreamingPlayback(): Boolean = synchronized(playbackLock) {
        val active = activeStreamingPlayback ?: return@synchronized false
        activeStreamingPlayback = null
        active.chunks.close()
        active.worker.cancel()
        true
    }

    // ── Config construction via reflection ───────────────────────────────────

    /**
     * Builds `OfflineTtsConfig` entirely through reflection so no Sherpa import is needed.
     *
     * Sherpa-ONNX Kotlin data classes all have Kotlin-default–value constructors (every field
     * has a default), so we call the no-arg/all-defaults constructor then mutate the mutable
     * (`var`) properties via their setter methods (`setModel(String)`, etc.).
     *
     * Field layout targeted:
     * ```
     * OfflineTtsVitsModelConfig { model, tokens, dataDir, noiseScale, noiseScaleW, lengthScale }
     * OfflineTtsModelConfig     { vits, numThreads, debug, provider }
     * OfflineTtsConfig          { model, ruleFsts, maxNumSentences }
     * ```
     */
    private fun buildOfflineTtsConfig(modelDir: File): Any {
        val vitsClass = Class.forName(SHERPA_VITS_MODEL_CONFIG_CLASS)
        val modelConfigClass = Class.forName(SHERPA_MODEL_CONFIG_CLASS)
        val configClass = Class.forName(SHERPA_OFFLINE_TTS_CONFIG_CLASS)

        // OfflineTtsVitsModelConfig — all-defaults constructor
        val vitsConfig = newInstance(vitsClass)
        setProperty(vitsConfig, "model", File(modelDir, SHERPA_MODEL_ONNX_FILE).absolutePath)
        setProperty(vitsConfig, "tokens", File(modelDir, SHERPA_TOKENS_FILE).absolutePath)
        setProperty(vitsConfig, "dataDir", File(modelDir, SHERPA_ESPEAK_DATA_DIR).absolutePath)

        // OfflineTtsModelConfig
        val modelConfig = newInstance(modelConfigClass)
        setProperty(modelConfig, "vits", vitsConfig)
        // 4 threads, as Kokoro uses (#1942). With 2, Cori high took 0.63–0.88 s per second of audio on the S23U
        // (foreground); with 4, 0.40–0.56 s. At 2, a chunk longer than the one playing arrived late (1–1.7 s gap).
        setProperty(modelConfig, "numThreads", PIPER_NUM_THREADS)
        setProperty(modelConfig, "debug", false)

        // OfflineTtsConfig
        val config = newInstance(configClass)
        setProperty(config, "model", modelConfig)
        return config
    }

    /**
     * Instantiates a Kotlin class whose constructor has all-default parameters.
     * Tries the no-arg form first; if absent (Kotlin doesn't generate a *true* no-arg unless
     * annotated with `@JvmOverloads` or no-arg Gradle plugin) falls back to the Kotlin synthetic
     * default constructor `(…, int mask, DefaultConstructorMarker marker)`.
     */
    private fun newInstance(cls: Class<*>): Any {
        // Attempt 1: genuine no-arg constructor (generated by no-arg compiler plugin or @JvmOverloads)
        cls.declaredConstructors.firstOrNull { it.parameterCount == 0 }?.let { ctor ->
            ctor.isAccessible = true
            return ctor.newInstance()
        }
        // Attempt 2: Kotlin synthetic default constructor with trailing (int, DefaultConstructorMarker?)
        // Signature: (T1, T2, ..., int, DefaultConstructorMarker?) where int is the bit-mask.
        val synthetic = cls.declaredConstructors.minByOrNull { it.parameterCount }
            ?: error("No usable constructor found for ${cls.name}")
        synthetic.isAccessible = true
        val argCount = synthetic.parameterCount
        // Fill leading params with null/0/false; second-to-last is the int mask (0 = use all defaults);
        // last is DefaultConstructorMarker (pass null — Kotlin accepts null for the marker).
        val args = arrayOfNulls<Any>(argCount).also { it[argCount - 2] = 0 }
        return synthetic.newInstance(*args)
    }

    /**
     * Sets a mutable Kotlin property on [obj] via its generated JVM setter (`setXxx`).
     * Falls back to direct field access if no setter is found.
     */
    private fun setProperty(obj: Any, name: String, value: Any) {
        val setterName = "set${name.replaceFirstChar { it.uppercase() }}"
        val valueClass: Class<*> = when (value) {
            is String -> String::class.java
            is Int -> Int::class.javaPrimitiveType!!
            is Float -> Float::class.javaPrimitiveType!!
            is Boolean -> Boolean::class.javaPrimitiveType!!
            else -> value.javaClass
        }
        try {
            val setter = obj.javaClass.getDeclaredMethod(setterName, valueClass)
            setter.isAccessible = true
            setter.invoke(obj, value)
        } catch (_: NoSuchMethodException) {
            // Fall back to direct field mutation (Kotlin backing field)
            try {
                val field = obj.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.set(obj, value)
            } catch (e2: Exception) {
                Log.w(TAG, "setProperty($name) failed on ${obj.javaClass.simpleName}", e2)
            }
        }
    }

    // ── GeneratedAudio reflection helpers ────────────────────────────────────

    private fun reflectGetSamples(audio: Any): FloatArray {
        return try {
            audio.javaClass.getDeclaredMethod("getSamples")
                .also { it.isAccessible = true }
                .invoke(audio) as FloatArray
        } catch (_: NoSuchMethodException) {
            audio.javaClass.getDeclaredField("samples")
                .also { it.isAccessible = true }
                .get(audio) as FloatArray
        }
    }

    private fun reflectGetSampleRate(audio: Any): Int {
        return try {
            audio.javaClass.getDeclaredMethod("getSampleRate")
                .also { it.isAccessible = true }
                .invoke(audio) as Int
        } catch (_: NoSuchMethodException) {
            audio.javaClass.getDeclaredField("sampleRate")
                .also { it.isAccessible = true }
                .getInt(audio)
        }
    }

    // ── AudioTrack playback ──────────────────────────────────────────────────

    /**
     * Writes [samples] (PCM Float32, mono) to the shared voice-output AudioTrack path.
     * Checks [stopped] and the non-streaming generation between chunks so [stop] can interrupt
     * mid-utterance and a newer request cannot receive stale audio.
     *
     * [generation] is the value of [nonStreamingPlaybackGeneration] at the time this invocation
     * was started. Pass the default (-1) from the streaming path, which uses its own token guard.
     */
    private fun playOnAudioTrack(
        samples: FloatArray,
        sampleRate: Int,
        generation: Long = -1L,
        playbackToken: Long = -1L,
    ) {
        playVoicePcmOnAudioTrack(
            samples = samples,
            sampleRate = sampleRate,
            gain = sherpaGain.value,
            pitch = sherpaPitch.value,
            shouldContinue = {
                !stopped &&
                    (generation < 0L || nonStreamingPlaybackGeneration.get() == generation) &&
                    (playbackToken < 0L || isStreamingPlaybackActive(playbackToken))
            },
        )
    }

    // ── AudioFocus ───────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "SherpaOnnx audio focus not granted: $result")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun unavailableMessage(voice: SherpaPiperVoice): String =
        "Sherpa-ONNX TTS is not available for ${voice.displayName}. " +
            "Download the voice pack in Settings → Voice."

    /**
     * Called by [SherpaVoicePackDownloadManager] after a voice pack is successfully downloaded.
     *
     * If this voice was previously [InitState.UNAVAILABLE] (pack was missing), resets the
     * state to [InitState.UNINITIALIZED] so the next [speak] call re-initialises Sherpa
     * using the newly extracted files — without requiring a voice-selection change.
     */
    fun markVoiceAvailable(voice: SherpaPiperVoice) {
        if (initState == InitState.UNAVAILABLE && (initializedVoice == voice || initializedVoice == null)) {
            Log.i(TAG, "Voice pack available for ${voice.displayName} — resetting init state for retry.")
            resetForVoice(voice)
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "KernelAI"
        // Sherpa-ONNX class names — accessed only via reflection so no AAR import required
        const val SHERPA_PKG = "com.k2fsa.sherpa.onnx"
        const val SHERPA_OFFLINE_TTS_CLASS = "$SHERPA_PKG.OfflineTts"
        const val SHERPA_OFFLINE_TTS_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsConfig"
        const val SHERPA_MODEL_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsModelConfig"
        const val SHERPA_VITS_MODEL_CONFIG_CLASS = "$SHERPA_PKG.OfflineTtsVitsModelConfig"
        const val PIPER_NUM_THREADS = 4
    }
}

/**
 * `OfflineTts.generateWithCallback(String, Int, Float, (FloatArray) -> Int)`, or null when the runtime
 * lacks it. Sherpa calls the callback after each sentence of the text; returning 0 stops there.
 */
private fun generateWithCallbackMethod(ttsClass: Class<*>): java.lang.reflect.Method? = try {
    ttsClass.getDeclaredMethod(
        "generateWithCallback",
        String::class.java,
        Int::class.javaPrimitiveType,
        Float::class.javaPrimitiveType,
        Function1::class.java,
    )
} catch (_: NoSuchMethodException) {
    null
}

/**
 * Callback for `generateWithCallback`. Sherpa's JNI calls `invoke([F)Ljava/lang/Integer;` on it; a Kotlin
 * lambda only has the erased `invoke(Object)` bridge and aborts the process. A class that overrides
 * `invoke(FloatArray): Int` of `(FloatArray) -> Int` gets that exact boxed signature. Kept by
 * `consumer-rules.pro`, since nothing in Kotlin calls it directly.
 */
internal class SherpaContinueCallback(private val active: () -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (active()) 1 else 0
}

/**
 * One synthesised streaming chunk, keeping its text for [VoiceOutputTrace]. A pause chunk has no
 * samples and [pauseMs] > 0: playback waits that long instead of playing audio.
 */
private class SynthesizedChunk(
    val text: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val pauseMs: Long = 0L,
) {
    val audioMs: Long get() = samples.size * 1000L / sampleRate.coerceAtLeast(1)

    companion object {
        fun pause(durationMs: Long) = SynthesizedChunk("", FloatArray(0), sampleRate = 1, pauseMs = durationMs)
    }
}

/**
 * Verbose-only per-chunk timing for one streamed reply (#1942): synthesis time against the audio it
 * produced (real-time factor = synth ms / audio ms), and the silence between one chunk's playback
 * ending and the next one starting. Tag `VoiceTiming`; one line per event.
 */
private class StreamTimingLog(private val token: Long) {
    private var synthIndex = 0
    private var lastPlayEnd = 0L
    private val indices = java.util.IdentityHashMap<SynthesizedChunk, Int>()

    @Synchronized
    fun synthesized(chunk: SynthesizedChunk, startMs: Long, sid: Int) {
        val end = System.currentTimeMillis()
        val index = synthIndex++
        indices[chunk] = index
        val synthMs = end - startMs
        val rtf = synthMs.toDouble() / chunk.audioMs.coerceAtLeast(1)
        Log.i(
            TIMING_TAG,
            "synth token=$token chunk=$index chars=${chunk.text.length} sid=$sid startEpochMs=$startMs " +
                "endEpochMs=$end synthMs=$synthMs audioMs=${chunk.audioMs} rtf=${"%.2f".format(java.util.Locale.ROOT, rtf)}",
        )
    }

    @Synchronized
    fun discarded(chunk: SynthesizedChunk) {
        Log.i(TIMING_TAG, "discard token=$token chunk=${indices.remove(chunk)} reason=stale")
    }

    @Synchronized
    fun playStarted(chunk: SynthesizedChunk) {
        val now = System.currentTimeMillis()
        val gap = if (lastPlayEnd > 0L) " gapMs=${now - lastPlayEnd}" else ""
        Log.i(TIMING_TAG, "playStart token=$token chunk=${indices[chunk]} epochMs=$now$gap")
    }

    /** An inserted pause: the next playStart's gapMs leaves it out, so gapMs stays the unintended silence. */
    @Synchronized
    fun paused(durationMs: Long) {
        Log.i(TIMING_TAG, "pause token=$token ms=$durationMs")
        if (lastPlayEnd > 0L) lastPlayEnd += durationMs
    }

    @Synchronized
    fun playEnded(chunk: SynthesizedChunk) {
        lastPlayEnd = System.currentTimeMillis()
        Log.i(TIMING_TAG, "playEnd token=$token chunk=${indices.remove(chunk)} epochMs=$lastPlayEnd")
    }

    private companion object {
        const val TIMING_TAG = "VoiceTiming"
    }
}
