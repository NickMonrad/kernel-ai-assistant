package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val activeTrack = AtomicReference<AudioTrack?>(null)
    private val playbackGeneration = AtomicLong(0L)
    private val synthesisLock = kotlinx.coroutines.sync.Mutex()

    @Volatile private var stopped = false
    @Volatile private var runner: InflectMicroOnnxRunner? = null
    @Volatile private var runnerDirectory: File? = null
    @Volatile private var frontendDataDirectory: File? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null
    private val speakingStopGate = SpeakingStopGate()
    private val activeUtterance = AtomicReference<InflectUtterance?>(null)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        prepare()
    }

    override suspend fun openStreamingSession(request: VoiceSpeakRequest): VoiceOutputStreamingSession =
        openUtterance(request)

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult = withContext(Dispatchers.IO) {
        if (request.text.isBlank()) return@withContext VoiceOutputResult.Spoken
        val utterance = openUtterance(request)
        try {
            val appended = utterance.append(request.text, isFinal = true)
            utterance.playback.join()
            if (appended is VoiceOutputResult.Unavailable) appended else utterance.playbackResult()
        } catch (error: CancellationException) {
            utterance.cancel()
            throw error
        }
    }

    /** Stops any reply still playing, then prepares a new one. Only the newest reply may emit audio. */
    private suspend fun openUtterance(request: VoiceSpeakRequest): InflectUtterance = withContext(Dispatchers.IO) {
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
        val synthesis = if (unavailable == null && currentRunner != null && dataDirectory != null) {
            PreparedSynthesis(currentRunner, dataDirectory)
        } else {
            null
        }
        InflectUtterance(request, generation, synthesis, unavailable, gain, pitch).also { activeUtterance.set(it) }
    }

    override fun stop() {
        stopped = true
        playbackGeneration.incrementAndGet()
        runner?.cancel()
        activeUtterance.getAndSet(null)?.abort()
        activeTrack.getAndSet(null)?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // AudioTrack may already have completed on the playback thread.
            }
        }
        releaseAudioFocus()
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

    /** One reply's audio: chunks are synthesized in order as they arrive and played on a single track. */
    private inner class InflectUtterance(
        private val request: VoiceSpeakRequest,
        private val generation: Long,
        private val synthesis: PreparedSynthesis?,
        private val unavailable: VoiceOutputResult.Unavailable?,
        private val gain: Float,
        private val pitch: Float,
    ) : VoiceOutputStreamingSession {
        private val audio = Channel<FloatArray>(capacity = 1)
        private val audioClosed = AtomicBoolean(false)
        private var queuedChunks = 0
        @Volatile private var failure: String? = null

        /** Plays queued waveforms in order until the channel closes or the reply is superseded. */
        val playback: Job = playbackScope.launch { playAudio() }

        override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult = withContext(Dispatchers.IO) {
            val result = appendText(text)
            if (isFinal) {
                closeAudio()
                if (result == VoiceOutputResult.Spoken && queuedChunks == 0 && isCurrent()) {
                    return@withContext fail("Inflect normalization produced no synthesis chunks.")
                }
            }
            result
        }

        override suspend fun cancel() {
            if (activeUtterance.get() === this) this@InflectMicroVoiceOutputController.stop() else closeAudio()
        }

        fun playbackResult(): VoiceOutputResult =
            failure?.let { VoiceOutputResult.Unavailable(it) } ?: VoiceOutputResult.Spoken

        /** Cancels the waveform channel so a blocked [append] or the playback loop unwinds at once. */
        fun abort() {
            audioClosed.set(true)
            audio.cancel()
        }

        private fun isCurrent(): Boolean = !stopped && playbackGeneration.get() == generation

        private fun closeAudio() {
            if (audioClosed.compareAndSet(false, true)) audio.close()
        }

        private fun fail(message: String): VoiceOutputResult.Unavailable {
            failure = message
            closeAudio()
            return VoiceOutputResult.Unavailable(message)
        }

        private suspend fun appendText(text: String): VoiceOutputResult {
            unavailable?.let {
                closeAudio()
                return it
            }
            failure?.let { return VoiceOutputResult.Unavailable(it) }
            if (audioClosed.get()) return VoiceOutputResult.Spoken
            if (!isCurrent()) {
                closeAudio()
                return VoiceOutputResult.Spoken
            }
            val prepared = synthesis ?: return fail("Inflect runner is not initialized.")
            try {
                val normalized = InflectMicroTextFrontend.normalize(SpeechCardText.speakable(text))
                if (normalized.isBlank()) return VoiceOutputResult.Spoken
                for (chunk in InflectMicroTextFrontend.phonemizeSpeechChunks(normalized) { chunkText ->
                    InflectMicroTextFrontend.applyPhonemeOverrides(
                        InflectPhonemizer.phonemize(chunkText, prepared.dataDirectory),
                    )
                }) {
                    val waveform = synthesizeChunk(prepared, chunk.phonemes) ?: return VoiceOutputResult.Spoken
                    if (!enqueue(waveform)) {
                        return failure?.let { VoiceOutputResult.Unavailable(it) } ?: VoiceOutputResult.Spoken
                    }
                    queuedChunks++
                }
                return VoiceOutputResult.Spoken
            } catch (error: CancellationException) {
                if (isCurrent()) throw error
                return VoiceOutputResult.Spoken
            } catch (error: Exception) {
                Log.e(TAG, "Inflect synthesis failed", error)
                return fail("Inflect synthesis failed: ${error.message}")
            }
        }

        /** Returns null when this reply was superseded before or during the synthesis. */
        private suspend fun synthesizeChunk(prepared: PreparedSynthesis, phonemes: String): FloatArray? {
            return synthesisLock.withLock {
                if (!isCurrent()) return null
                prepared.runner.resetCancellation()
                // stop() bumps the generation before cancelling, so a stop racing the reset is caught here.
                if (!isCurrent()) return null
                fadeChunkEdges(prepared.runner.synthesize(phonemes).waveform)
            }
        }

        private suspend fun enqueue(waveform: FloatArray): Boolean = try {
            audio.send(waveform)
            true
        } catch (error: ClosedSendChannelException) {
            false
        } catch (error: CancellationException) {
            if (isCurrent() && failure == null) throw error
            false
        }

        private suspend fun playAudio() {
            var track: AudioTrack? = null
            try {
                for (waveform in audio) {
                    if (!isCurrent()) break
                    if (track == null) track = startTrack()
                    writeVoicePcm(track, waveform, gain) { isCurrent() }
                }
                val finished = track
                if (finished != null) {
                    if (isCurrent()) drainVoicePcmTrack(finished) { isCurrent() } else fadeOutAndDiscard(finished)
                }
            } catch (error: CancellationException) {
                // stop() cancels the channel; stop() has already silenced the track.
            } catch (error: Exception) {
                Log.e(TAG, "Inflect playback failed", error)
                failure = "Inflect playback failed: ${error.message}"
                audio.cancel()
            } finally {
                track?.let {
                    activeTrack.compareAndSet(it, null)
                    it.release()
                }
                // Once per reply, on every exit path including stop(). The gate pairs the stop with this reply's
                // SpeakingStarted, so a stale unwind cannot end a newer reply's speech.
                if (playbackGeneration.get() == generation) releaseAudioFocus()
                if (speakingStopGate.claimStopped(generation)) eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStopped)
            }
        }

        private fun startTrack(): AudioTrack {
            requestAudioFocus()
            VoiceOutputTrace.speakingStarted("inflect", request.text)
            VoiceOutputTrace.logSpeakingStarted("inflect", request.text.length)
            speakingStopGate.markStarted(generation)
            eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStarted(request.text))
            return createVoicePcmTrack(InflectMicroOnnxRunner.SAMPLE_RATE_HZ).also { created ->
                activeTrack.set(created)
                startVoicePcmTrack(created, pitch)
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
