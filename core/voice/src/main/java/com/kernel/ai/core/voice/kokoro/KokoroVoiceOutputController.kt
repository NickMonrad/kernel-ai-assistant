package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.inference.InferenceEngine
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.kernel.ai.core.voice.AudioTrackVoicePcmSink
import com.kernel.ai.core.voice.SpeakingStopGate
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceOutputTrace
import com.kernel.ai.core.voice.VoiceReplyHooks
import com.kernel.ai.core.voice.VoiceReplyStream
import com.kernel.ai.core.voice.VoiceSpeakRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kokoro-82M (ExecuTorch) text -> phonemes -> waveform -> AudioTrack controller (#2117). Same reply and Stop
 * sequence as [com.kernel.ai.core.voice.InflectMicroVoiceOutputController] (#2106).
 *
 * A native chunk cannot be interrupted, so a stop silences queued audio at once but a new reply waits for the chunk
 * already synthesizing (up to ~4 s on the S23U).
 */
@Singleton
class KokoroVoiceOutputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val loadMarker: KokoroLoadMarker,
    private val memoryGate: KokoroWarmUpMemoryGate,
    private val inferenceEngine: InferenceEngine,
) : VoiceOutputController {
    private val eventsFlow = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = eventsFlow.asSharedFlow()

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val playbackGeneration = AtomicLong(0L)
    private val replyIds = AtomicLong(0L)
    /** Guards engine load, unload and chunk synthesis: a voice switch never closes an engine mid-chunk. */
    private val synthesisLock = Mutex()
    /** Serialises reply opens so stop-capture-publish happens as one step; the newest open always wins. */
    private val openLock = Mutex()

    @Volatile private var stopped = false
    @Volatile private var engine: KokoroEngine? = null
    @Volatile private var engineVoice: KokoroVoice? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var latestTiming: KokoroReplyTiming? = null
    @Volatile private var latestGeneration = -1L
    private val speakingStopGate = SpeakingStopGate()
    private val activeReply = AtomicReference<KokoroReplySession?>(null)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sink = AudioTrackVoicePcmSink()
    private val hooks = ControllerHooks()

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        val voice = voiceOutputPreferences.selectedKokoroVoice.first()
        synthesisLock.withLock { prepare(voice) }
    }

    /** Settings "Test voice speed" (#2117): times a fixed passage without playing it. Waits behind a reply being made. */
    suspend fun measureSpeed(): KokoroSpeedResult = withContext(Dispatchers.IO) {
        if (!inferenceEngine.isReady.value) {
            return@withContext KokoroSpeedResult.Unavailable("Couldn't prepare the check on this phone.")
        }
        val voice = voiceOutputPreferences.selectedKokoroVoice.first()
        val cancellation = currentCoroutineContext()
        synthesisLock.withLock {
            // Read before any load: the speed-fail details carry these figures whether or not a load is needed.
            val snapshot = memoryGate.snapshot()
            val details = snapshot.describe(KOKORO_LOAD_MIN_AVAILABLE_BYTES)
            Log.i(TAG, "Kokoro check memory: $details")
            // A load needs memory headroom; a check that needs no load is not blocked by it.
            if (needsFullLoad(voice) && !kokoroHasHeadroom(snapshot, KOKORO_LOAD_MIN_AVAILABLE_BYTES)) {
                return@withLock KokoroSpeedResult.Unavailable(
                    message = KOKORO_LOW_MEMORY_MESSAGE,
                    details = details,
                    memoryShort = true,
                )
            }
            val preparation = prepare(voice)
            val loaded = engine.takeIf { preparation == VoiceOutputResult.Spoken && engineVoice == voice }
                ?: return@withLock KokoroSpeedResult.Unavailable(
                    (preparation as? VoiceOutputResult.Unavailable)?.message ?: "Kokoro engine is not initialized.",
                )
            val result = measureKokoroSpeed(
                sentences = KOKORO_SPEED_TEST_SENTENCES,
                splitText = { sentence -> loaded.splitText(KokoroTextFrontend.phonemizerInput(sentence, voice.locale)) },
                synthesizeChunk = { chunk -> loaded.synthesizeChunk(chunk) },
                sampleRate = KokoroTts.SAMPLE_RATE_HZ,
                peakMemoryMb = ::readPeakMemoryMb,
                ensureActive = { cancellation.ensureActive() },
            )
            if (result is KokoroSpeedResult.Measured) result.copy(memoryDetails = details) else result
        }
    }

    override suspend fun openStreamingSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession =
        openReply(request, speakRemainder = null)

    /**
     * Streams a reply. If a chunk fails after audio has queued, the unspoken rest goes to [speakRemainder] (Inflect
     * Micro) once Kokoro's audio ends. With nothing queued, the append returns Unavailable and the caller speaks it all.
     */
    suspend fun openStreamingSessionWithRemainder(
        request: VoiceSpeakRequest,
        speakRemainder: suspend (String) -> VoiceOutputResult,
    ): VoiceOutputStreamingSession = openReply(request, speakRemainder)

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult =
        speakWithRemainder(request, speakRemainder = null)

    /** Speaks [request] with Kokoro; the hand-off rule is described on [openStreamingSessionWithRemainder]. */
    suspend fun speakWithRemainder(
        request: VoiceSpeakRequest,
        speakRemainder: (suspend (String) -> VoiceOutputResult)?,
    ): VoiceOutputResult = withContext(Dispatchers.IO) {
        if (request.text.isBlank()) return@withContext VoiceOutputResult.Spoken
        val reply = openReply(request, speakRemainder)
        try {
            val appended = reply.append(request.text, isFinal = true)
            reply.awaitPlayback()
            if (appended is VoiceOutputResult.Unavailable) appended else reply.playbackResult()
        } catch (error: CancellationException) {
            // Caller cancellation finishes queued audio; only stop() silences a reply.
            reply.cancel()
            throw error
        }
    }

    /** Stops any reply still playing, then prepares a new one. Only the newest reply may emit audio. */
    private suspend fun openReply(
        request: VoiceSpeakRequest,
        speakRemainder: (suspend (String) -> VoiceOutputResult)?,
    ): KokoroReplySession = withContext(Dispatchers.IO) {
        val timing = KokoroReplyTiming(replyIds.incrementAndGet())
        timing.mark("open", "chars=${request.text.length}")
        openLock.withLock {
            stop()
            stopped = false
            val generation = playbackGeneration.get()
            val gain = voiceOutputPreferences.voiceGain.first()
            val pitch = voiceOutputPreferences.voicePitch.first()
            val voice = voiceOutputPreferences.selectedKokoroVoice.first()
            timing.mark("settingsRead", "voice=${voice.name}")
            val cached = engine != null && engineVoice == voice
            val preparation = synthesisLock.withLock { prepareForReply(voice, timing) }
            timing.mark("prepared", "cached=$cached ok=${preparation == VoiceOutputResult.Spoken}")
            val loaded = engine.takeIf { preparation == VoiceOutputResult.Spoken && engineVoice == voice }
            val unavailable = preparation as? VoiceOutputResult.Unavailable
                ?: if (loaded == null) VoiceOutputResult.Unavailable("Kokoro engine is not initialized.") else null
            // Every Kokoro-unavailable reply falls back to Inflect; log the reason so a silent fallback is diagnosable.
            unavailable?.let { Log.w(TAG, "Kokoro reply falls back: ${it.message}") }
            val handoff = KokoroHandoff()
            val stream: VoiceReplyStream<KokoroUnit> = VoiceReplyStream(
                engine = "Kokoro",
                spokenText = request.text,
                generation = generation,
                isCurrent = { isCurrent(generation) },
                unavailable = unavailable,
                // Each sentence is phonemized on its own, so a failing one leaves exactly its unspoken text (#2117).
                chunker = { text, _ ->
                    kokoroSentenceUnits(text, handoff) { sentence ->
                        val started = System.currentTimeMillis()
                        loaded?.splitText(KokoroTextFrontend.phonemizerInput(sentence, voice.locale)).orEmpty().also {
                            val splitMs = System.currentTimeMillis() - started
                            timing.mark("split", "chars=${sentence.length} chunks=${it.size} splitMs=$splitMs")
                        }
                    }
                },
                synthesize = { unit ->
                    handoff.synthesizeOrHandOff(unit) { chunk ->
                        loaded?.let { synthesizeChunk(it, voice, chunk, generation, timing) }
                    }
                },
                sink = timing.timed(sink),
                sampleRate = KokoroTts.SAMPLE_RATE_HZ,
                hooks = hooks,
                gain = gain,
                pitch = pitch,
                scope = playbackScope,
            )
            val reply = KokoroReplySession(
                stream = stream,
                handoff = handoff,
                timing = timing,
                voice = voice.name,
                isCurrent = { isCurrent(generation) },
                speakRemainder = speakRemainder,
                scope = playbackScope,
            )
            latestTiming = timing
            latestGeneration = generation
            activeReply.getAndSet(reply)?.abort()
            reply
        }
    }

    override fun stop() {
        stopped = true
        playbackGeneration.incrementAndGet()
        activeReply.getAndSet(null)?.abort()
        sink.silenceActive()
        releaseAudioFocus()
    }

    /**
     * Frees the engine (about 0.95 GB resident) under memory pressure, after a switch to another voice engine, or before
     * the pack is deleted. Runs after any chunk already synthesizing. A reply still playing finds its engine gone and
     * hands its unspoken rest to Inflect Micro.
     */
    fun unloadEngine() {
        playbackScope.launch(Dispatchers.IO) {
            synthesisLock.withLock {
                if (engine != null) Log.i(TAG, "Kokoro engine unloaded")
                releaseEngine()
            }
        }
    }

    /** A full load (not a cached engine, not a same-locale voice swap) is the only step that needs headroom. */
    private fun needsFullLoad(voice: KokoroVoice): Boolean = engine == null || engineVoice?.locale != voice.locale

    /**
     * Reply-time load (#2170): a full load needs [KOKORO_LOAD_MIN_AVAILABLE_BYTES] free. A refusal is Unavailable, so the
     * reply falls back to Inflect Micro, then Android TTS. Caller holds [synthesisLock].
     */
    private fun prepareForReply(voice: KokoroVoice, timing: KokoroReplyTiming): VoiceOutputResult {
        if (!needsFullLoad(voice)) return prepare(voice, timing)
        val snapshot = memoryGate.snapshot()
        if (kokoroHasHeadroom(snapshot, KOKORO_LOAD_MIN_AVAILABLE_BYTES)) return prepare(voice, timing)
        Log.w(TAG, "Kokoro reply load refused: ${snapshot.describe(KOKORO_LOAD_MIN_AVAILABLE_BYTES)}")
        return VoiceOutputResult.Unavailable(KOKORO_LOW_MEMORY_MESSAGE)
    }

    private fun isCurrent(generation: Long): Boolean = !stopped && playbackGeneration.get() == generation

    /** Returns null when the reply was superseded before or during synthesis. */
    private suspend fun synthesizeChunk(
        loaded: KokoroEngine,
        voice: KokoroVoice,
        chunk: String,
        generation: Long,
        timing: KokoroReplyTiming,
    ): FloatArray? {
        val requested = System.currentTimeMillis()
        return synthesisLock.withLock {
            // stop() bumps the generation before anything else, so a stop racing this check is caught by the next one.
            if (!isCurrent(generation)) return@withLock null
            // The engine was unloaded or swapped to another voice after this reply opened: fail the chunk so the rest
            // of the reply goes to Inflect Micro, rather than speak it with a closed engine or a different voice.
            check(engine === loaded && engineVoice == voice) { "Kokoro engine changed mid-reply" }
            val started = System.currentTimeMillis()
            loaded.synthesizeChunk(chunk).takeIf { isCurrent(generation) }?.also { audio ->
                val synthMs = System.currentTimeMillis() - started
                val audioMs = audio.size * 1000L / KokoroTts.SAMPLE_RATE_HZ
                val rtf = synthMs.toDouble() / audioMs.coerceAtLeast(1L)
                timing.recordRtf(rtf)
                timing.mark(
                    "synth",
                    "chars=${chunk.length} lockWaitMs=${started - requested} synthMs=$synthMs audioMs=$audioMs " +
                        "rtf=${"%.2f".format(Locale.ROOT, rtf)}",
                )
            }
        }
    }

    /**
     * Loads the engine for [voice] unless it is already loaded. A voice of the same locale swaps only its voice
     * embedding, so the ExecuTorch programs and phonemiser stay loaded (Emma and Daniel reloads cost 2.6-6.6 s).
     * A fresh load is primed with one throwaway chunk. Caller holds [synthesisLock].
     */
    private fun prepare(voice: KokoroVoice, timing: KokoroReplyTiming? = null): VoiceOutputResult {
        KokoroModelFiles.firstMissing(context, voice)?.let { missing ->
            return VoiceOutputResult.Unavailable(
                "Download ${missing.displayName} in Settings → Voice before selecting Kokoro.",
            )
        }
        val current = engine
        if (current != null && engineVoice == voice) return VoiceOutputResult.Spoken
        if (current != null && engineVoice?.locale == voice.locale) {
            return try {
                current.loadVoice(KokoroModelFiles.voiceFile(context, voice))
                engineVoice = voice
                VoiceOutputResult.Spoken
            } catch (error: Throwable) {
                releaseEngine()
                loadFailure("Kokoro voice failed to load", error)
            }
        }
        releaseEngine()
        // A native abort here cannot be caught; the marker keeps the next launch from repeating it (#1449).
        loadMarker.loading()
        try {
            val phonemizer = try {
                NativeKokoroPhonemizer(
                    locale = voice.locale,
                    lexiconFile = KokoroModelFiles.lexiconFile(context, voice),
                    taggerFile = KokoroModelFiles.tagsFile(context),
                )
            } catch (error: LinkageError) {
                return loadFailure("Kokoro native library is unavailable", error)
            } catch (error: Throwable) {
                return loadFailure("Kokoro phonemizer failed to start", error)
            }
            timing?.mark("phonemizerReady")
            val candidate = KokoroEngine(
                durationFile = KokoroModelFiles.durationFile(context),
                synthesizerFile = KokoroModelFiles.synthesizerFile(context),
                phonemizer = phonemizer,
            )
            try {
                candidate.initialize(KokoroModelFiles.voiceFile(context, voice))
            } catch (error: Throwable) {
                // Loading two ExecuTorch programs is the memory-heavy step (#1449); fall back instead of crashing.
                candidate.close()
                return loadFailure("Kokoro model initialization failed", error)
            }
            timing?.mark("modelsLoaded")
            engine = candidate
            engineVoice = voice
            primeInference(candidate, voice)
            return VoiceOutputResult.Spoken
        } finally {
            loadMarker.loaded()
        }
    }

    /** Closes the loaded engine and its phonemiser. Caller holds [synthesisLock]. */
    private fun releaseEngine() {
        engine?.close()
        engine = null
        engineVoice = null
    }

    /**
     * One throwaway chunk after a fresh load: the first inference on the S23U ran about 0.45 s slower than later ones
     * (round 4), so the cost moves out of the first reply. Caller holds [synthesisLock].
     */
    private fun primeInference(loaded: KokoroEngine, voice: KokoroVoice) {
        try {
            val chunks = loaded.splitText(KokoroTextFrontend.phonemizerInput(PRIME_TEXT, voice.locale))
            loaded.synthesizeChunk(chunks.first())
        } catch (error: Throwable) {
            Log.w(TAG, "Kokoro warm-up inference failed: ${error.message}")
        }
    }

    private fun loadFailure(message: String, error: Throwable): VoiceOutputResult.Unavailable {
        Log.e(TAG, "$message", error)
        return VoiceOutputResult.Unavailable("$message: ${error.message}")
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private inner class ControllerHooks : VoiceReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) {
            if (generation == latestGeneration) latestTiming?.playbackStarting()
            requestAudioFocus()
            VoiceOutputTrace.speakingStarted("kokoro", text)
            VoiceOutputTrace.logSpeakingStarted("kokoro", text.length)
            speakingStopGate.markStarted(generation)
            eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStarted(text))
        }

        override fun onPlaybackEnded(generation: Long) {
            // Once per reply, on every exit path including stop(). The gate pairs the stop with this reply's
            // SpeakingStarted, so a stale unwind cannot end a newer reply's speech.
            if (playbackGeneration.get() == generation) releaseAudioFocus()
            if (speakingStopGate.claimStopped(generation)) eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStopped)
        }
    }

    private companion object {
        const val TAG = "KernelAI"
        const val PRIME_TEXT = "Hello."
    }
}
