package com.kernel.ai.debug.tts

import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.TtsStream
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.CancellationException

/**
 * #1557: Kokoro through MediaPipe tasks-audio 1.2.3 with the LiteRT `kokoro_int8_gpu_fp16.litertlm` package.
 * Debug-only. The model path is passed explicitly because the library's default URL does not exist.
 *
 * Stops before AudioTrack, like [InflectMicroDebugLane]. `frontendMs` is 0: the library does not expose the
 * phonemisation step separately, so frontend cost is inside `synthMs`. `phonemeChars` is null for the same
 * reason. [backend] is the requested delegate only; the actual delegate must be read from logcat.
 */
class KokoroTasksAudioDebugLane(
    private val modelFile: File,
    private val voiceId: String = DEFAULT_VOICE,
    private val cacheDirectory: File,
) : DebugTtsLane {
    private var tts: TextToSpeech? = null
    private var activeStream: TtsStream? = null

    override val laneId: String = "kokoro-tasks-audio-1.2.3"

    override val backend: String =
        "mediapipe tasks-audio 1.2.3 (LiteRT Kokoro ${modelFile.name}), requested delegate GPU; actual delegate unverified"

    override fun initialize(): Long {
        check(tts == null) { "Kokoro lane already initialized" }
        check(modelFile.isFile) { "Kokoro model missing: ${modelFile.path}" }
        val startedAt = System.nanoTime()
        val base = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(Delegate.GPU)
            .build()
        tts = TextToSpeech.create(
            TextToSpeechOptions(base, voiceId, DEFAULT_LOCALE, 1.0f, 0, cacheDirectory.absolutePath),
        )
        return elapsedMs(startedAt)
    }

    override fun synthesize(text: String, onChunkReady: (index: Int, elapsedMs: Long) -> Unit): LaneUtterance {
        val engine = checkNotNull(tts) { "Kokoro lane not initialized" }
        val startedAt = System.nanoTime()
        val stream = engine.openStream(text, SynthesisOptions(voiceId, DEFAULT_LOCALE))
        activeStream = stream
        val chunks = mutableListOf<LaneChunk>()
        try {
            var index = 0
            while (true) {
                val chunk: AudioChunk = stream.readNext() ?: break
                val samples = chunk.samples
                chunks += LaneChunk(
                    index = index,
                    phonemeChars = null,
                    synthMs = elapsedMs(startedAt) - (chunks.sumOf { it.synthMs }),
                    audioMs = samples.size * 1000.0 / chunk.sampleRateHz,
                    peakAbs = samples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f,
                    finite = samples.all { it.isFinite() },
                )
                onChunkReady(index, elapsedMs(startedAt))
                index++
                if (chunk.isFinal) break
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (cancelled) throw CancellationException("kokoro synthesis cancelled: ${error.message}")
            throw error
        } finally {
            activeStream = null
            stream.close()
        }
        check(chunks.isNotEmpty()) { "Kokoro produced no audio chunks" }
        return LaneUtterance(frontendMs = 0, chunks = chunks)
    }

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        activeStream?.cancel()
        tts?.cancel()
    }

    override fun resetCancellation() {
        cancelled = false
    }

    override fun close() {
        activeStream?.cancel()
        tts?.close()
        tts = null
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        const val DEFAULT_VOICE = "af_heart"
        const val DEFAULT_LOCALE = "en-US"
    }
}
