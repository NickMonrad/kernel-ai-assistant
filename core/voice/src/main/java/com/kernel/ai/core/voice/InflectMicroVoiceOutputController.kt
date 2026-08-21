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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Debug-only Inflect Micro runtime text -> IPA -> ONNX -> AudioTrack controller. */
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

    override suspend fun warmUp(): VoiceOutputResult = withContext(Dispatchers.IO) {
        prepare()
    }

    override suspend fun speak(request: VoiceSpeakRequest): VoiceOutputResult = withContext(Dispatchers.IO) {
        if (request.text.isBlank()) return@withContext VoiceOutputResult.Spoken
        synthesisLock.withLock {
            stop()
            stopped = false
            val currentRunner = when (val result = prepare()) {
                VoiceOutputResult.Spoken -> runner
                is VoiceOutputResult.Unavailable -> return@withContext result
            } ?: return@withContext VoiceOutputResult.Unavailable("Inflect runner is not initialized.")
            val dataDirectory = frontendDataDirectory
                ?: return@withContext VoiceOutputResult.Unavailable("Inflect eSpeak data is unavailable.")
            currentRunner.resetCancellation()
            val generation = playbackGeneration.incrementAndGet()
            val gain = voiceOutputPreferences.voiceGain.first()
            val pitch = voiceOutputPreferences.voicePitch.first()
            var started = false
            try {
                val normalized = InflectMicroTextFrontend.normalize(request.text)
                require(normalized.isNotBlank()) { "Inflect normalization produced empty text" }
                val chunks = InflectMicroTextFrontend.phonemizeChunks(normalized) { chunkText ->
                    InflectMicroTextFrontend.applyPhonemeOverrides(
                        InflectPhonemizer.phonemize(chunkText, dataDirectory),
                    )
                }
                require(chunks.isNotEmpty()) { "Inflect normalization produced no synthesis chunks." }
                runInflectChunks(
                    chunks = chunks,
                    shouldContinue = {
                        !stopped && playbackGeneration.get() == generation
                    },
                    synthesize = { chunk -> currentRunner.synthesize(chunk) },
                    play = { synthesis ->
                        if (!started) {
                            requestAudioFocus()
                            eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStarted(request.text))
                            started = true
                        }
                        playVoicePcmOnAudioTrack(
                            samples = synthesis.waveform,
                            sampleRate = InflectMicroOnnxRunner.SAMPLE_RATE_HZ,
                            gain = gain,
                            pitch = pitch,
                            shouldContinue = {
                                !stopped && playbackGeneration.get() == generation
                            },
                            onTrackCreated = { activeTrack.set(it) },
                            onTrackReleased = { activeTrack.compareAndSet(it, null) },
                        )
                    },
                )
                releaseAudioFocus()
                if (started && !stopped) eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Spoken
            } catch (error: kotlinx.coroutines.CancellationException) {
                releaseAudioFocus()
                if (started) eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStopped)
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Inflect synthesis failed", error)
                releaseAudioFocus()
                if (started) eventsFlow.tryEmit(VoiceOutputEvent.SpeakingStopped)
                VoiceOutputResult.Unavailable("Inflect synthesis failed: ${error.message}")
            }
        }
    }

    override fun stop() {
        stopped = true
        playbackGeneration.incrementAndGet()
        runner?.cancel()
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

    private companion object {
        const val TAG = "KernelAI"
        const val SHERPA_ESPEAK_DATA_DIR = "espeak-ng-data"
    }
}

internal fun <T> runInflectChunks(
    chunks: List<InflectMicroTextFrontend.PhonemizedChunk>,
    shouldContinue: () -> Boolean,
    synthesize: (String) -> T,
    play: (T) -> Unit,
) {
    for (chunk in chunks) {
        if (!shouldContinue()) return
        val synthesis = synthesize(chunk.phonemes)
        if (!shouldContinue()) return
        play(synthesis)
    }
}
