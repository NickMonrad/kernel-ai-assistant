package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Inflect Micro runtime text -> IPA -> ONNX -> AudioTrack controller. */
@Singleton
class InflectMicroVoiceOutputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceOutputPreferences: VoiceOutputPreferences,
) : VoiceOutputController {
    private val eventsFlow = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    override val events: Flow<VoiceOutputEvent> = eventsFlow.asSharedFlow()

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val playbackGeneration = AtomicLong(0L)
    private val synthesisLock = Mutex()
    /** Serialises reply opens so stop-capture-publish happens as one step; the newest open always wins. */
    private val openLock = Mutex()

    @Volatile private var stopped = false
    @Volatile private var runner: InflectMicroOnnxRunner? = null
    @Volatile private var runnerDirectory: File? = null
    @Volatile private var frontendDataDirectory: File? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null
    private val speakingStopGate = SpeakingStopGate()
    private val activeReply = AtomicReference<InflectReplyStream?>(null)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sink = AudioTrackInflectSink()
    private val hooks = ControllerHooks()

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        synthesisLock.withLock { prepare() }
    }

    override suspend fun openStreamingSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession =
        openReply(request)

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult = withContext(Dispatchers.IO) {
        if (request.text.isBlank()) return@withContext VoiceOutputResult.Spoken
        val reply = openReply(request)
        try {
            val appended = reply.append(request.text, isFinal = true)
            reply.awaitPlayback()
            if (appended is VoiceOutputResult.Unavailable) appended else reply.playbackResult()
        } catch (error: CancellationException) {
            // Caller cancellation finishes queued audio, like Sherpa; only stop() silences a reply.
            reply.cancel()
            throw error
        }
    }

    /** Stops any reply still playing, then prepares a new one. Only the newest reply may emit audio. */
    private suspend fun openReply(request: VoiceSpeakRequest): InflectReplyStream = withContext(Dispatchers.IO) {
        openLock.withLock {
            stop()
            stopped = false
            val generation = playbackGeneration.get()
            val gain = voiceOutputPreferences.voiceGain.first()
            val pitch = voiceOutputPreferences.voicePitch.first()
            val preparation = synthesisLock.withLock { prepare() }
            val currentRunner = runner
            val dataDirectory = frontendDataDirectory
            val unavailable = when {
                preparation is VoiceOutputResult.Unavailable -> preparation
                currentRunner == null -> VoiceOutputResult.Unavailable("Inflect runner is not initialized.")
                dataDirectory == null -> VoiceOutputResult.Unavailable("Inflect eSpeak data is unavailable.")
                else -> null
            }
            val prepared = if (unavailable == null && currentRunner != null && dataDirectory != null) {
                PreparedSynthesis(currentRunner, dataDirectory)
            } else {
                null
            }
            val reply = InflectReplyStream(
                spokenText = request.text,
                generation = generation,
                isCurrent = { isCurrent(generation) },
                unavailable = unavailable,
                synthesize = { phonemes -> prepared?.let { synthesizeChunk(it, phonemes, generation) } },
                phonemizeChunk = { chunkText ->
                    InflectMicroTextFrontend.applyPhonemeOverrides(
                        InflectPhonemizer.phonemize(chunkText, checkNotNull(prepared).dataDirectory),
                    )
                },
                sink = sink,
                hooks = hooks,
                gain = gain,
                pitch = pitch,
                scope = playbackScope,
            )
            activeReply.getAndSet(reply)?.abort()
            reply
        }
    }

    override fun stop() {
        stopped = true
        playbackGeneration.incrementAndGet()
        runner?.cancel()
        activeReply.getAndSet(null)?.abort()
        sink.silenceActive()
        releaseAudioFocus()
    }

    private fun isCurrent(generation: Long): Boolean = !stopped && playbackGeneration.get() == generation

    /** Returns null when the reply was superseded before or during synthesis. */
    private suspend fun synthesizeChunk(prepared: PreparedSynthesis, phonemes: String, generation: Long): FloatArray? =
        synthesisLock.withLock {
            if (!isCurrent(generation)) {
                null
            } else {
                prepared.runner.resetCancellation()
                // stop() bumps the generation before cancelling, so a stop racing the reset is caught here.
                if (!isCurrent(generation)) null else prepared.runner.synthesize(phonemes).waveform
            }
        }

    private suspend fun prepare(): VoiceOutputResult {
        val models = InflectMicroModelSpec.requiredModels
        val missingModel = models.firstOrNull { model ->
            val file = InflectMicroModelSpec.modelFile(context, model)
            !file.isFile || file.length() == 0L
        }
        if (missingModel != null) {
            return VoiceOutputResult.Unavailable(
                "Download ${missingModel.displayName} in Settings → Voice before selecting Inflect Micro.",
            )
        }
        val voice = voiceOutputPreferences.selectedSherpaVoice.first()
        if (!voice.isDownloaded(context)) {
            return VoiceOutputResult.Unavailable(
                "Download the selected Sherpa voice in Settings → Voice for Inflect eSpeak data.",
            )
        }
        val dataDirectory = File(voice.voiceDir(context), SHERPA_ESPEAK_DATA_DIR)
        if (!dataDirectory.isDirectory) {
            return VoiceOutputResult.Unavailable("Missing Sherpa eSpeak data directory.")
        }
        val modelDirectory = InflectMicroModelSpec.modelDirectory(context)
        if (runner == null || runnerDirectory?.absolutePath != modelDirectory.absolutePath) {
            runner?.close()
            runner = try {
                InflectMicroOnnxRunner(modelDirectory)
            } catch (error: Exception) {
                Log.e(TAG, "Inflect model initialization failed", error)
                return VoiceOutputResult.Unavailable("Inflect model initialization failed: ${error.message}")
            }
            runnerDirectory = modelDirectory
        }
        if (frontendDataDirectory?.absolutePath != dataDirectory.absolutePath) {
            try {
                // This call verifies that the installed AAR contains the narrow JNI symbol before
                // the user selects the engine; it does not synthesize or allocate model tensors.
                InflectPhonemizer.phonemize("test", dataDirectory)
            } catch (error: Exception) {
                return VoiceOutputResult.Unavailable(error.message ?: "Inflect phonemizer unavailable.")
            }
            frontendDataDirectory = dataDirectory
        }
        return VoiceOutputResult.Spoken
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

    private inner class ControllerHooks : InflectReplyHooks {
        override fun onPlaybackStarting(generation: Long, text: String) {
            requestAudioFocus()
            VoiceOutputTrace.speakingStarted("inflect", text)
            VoiceOutputTrace.logSpeakingStarted("inflect", text.length)
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

    private inner class AudioTrackInflectSink : InflectPcmSink {
        private val activeTrack = AtomicReference<AudioTrack?>(null)

        override fun open(pitch: Float): InflectPcmTrack {
            val created = createVoicePcmTrack(InflectMicroOnnxRunner.SAMPLE_RATE_HZ)
            try {
                startVoicePcmTrack(created, pitch)
            } catch (error: Throwable) {
                created.release()
                throw error
            }
            activeTrack.set(created)
            return AudioPcmTrack(created)
        }

        override fun silenceActive() {
            activeTrack.getAndSet(null)?.let { track ->
                try {
                    track.pause()
                    track.flush()
                } catch (_: Exception) {
                    // AudioTrack may already have completed on the playback thread.
                }
            }
        }

        private inner class AudioPcmTrack(private val track: AudioTrack) : InflectPcmTrack {
            override fun write(samples: FloatArray, gain: Float, isCurrent: () -> Boolean) =
                writeVoicePcm(track, samples, gain, isCurrent)

            override fun drain(isCurrent: () -> Boolean) = drainVoicePcmTrack(track, isCurrent)

            override fun discard() = fadeOutAndDiscard(track)

            override fun release() {
                activeTrack.compareAndSet(track, null)
                track.release()
            }
        }
    }

    private companion object {
        const val TAG = "KernelAI"
        const val SHERPA_ESPEAK_DATA_DIR = "espeak-ng-data"
    }
}

/** Pairs a reply's runner with its eSpeak data directory; exists only once prepare() has succeeded. */
private class PreparedSynthesis(val runner: InflectMicroOnnxRunner, val dataDirectory: File)

private const val CHUNK_EDGE_FADE_MS = 5

/**
 * Fades the first and last [CHUNK_EDGE_FADE_MS] of a synthesized chunk so adjacent chunks join without a click.
 * Mutates and returns [samples]; the runner's own output stays unprocessed.
 */
internal fun fadeChunkEdges(samples: FloatArray, sampleRate: Int = InflectMicroOnnxRunner.SAMPLE_RATE_HZ): FloatArray {
    val fadeFrames = minOf(samples.size / 2, sampleRate * CHUNK_EDGE_FADE_MS / 1000)
    for (index in 0 until fadeFrames) {
        val gain = index.toFloat() / fadeFrames
        samples[index] *= gain
        samples[samples.size - 1 - index] *= gain
    }
    return samples
}
