package com.kernel.ai.debug.tts

import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import com.kernel.ai.core.voice.InflectMicroTextFrontend
import com.kernel.ai.core.voice.InflectPhonemizer
import com.kernel.ai.core.voice.SpeechCardText
import java.io.File

/**
 * Inflect Micro v2 lane. Runs the production sequence used by `InflectMicroVoiceOutputController.speak()`:
 * `SpeechCardText.speakable` → `InflectMicroTextFrontend.normalize` → `phonemizeChunks` (eSpeak via
 * `InflectPhonemizer` plus `applyPhonemeOverrides`) → `InflectMicroOnnxRunner.synthesize` per chunk.
 * It stops before AudioTrack, so playback latency is not included.
 */
class InflectMicroDebugLane(
    private val modelDirectory: File,
    private val espeakDataDirectory: File,
) : DebugTtsLane {
    private var runner: InflectMicroOnnxRunner? = null

    override val laneId: String = "inflect-micro-v2"

    override val backend: String =
        "onnxruntime ${onnxRuntimeVersion()} CPU execution provider (default SessionOptions, no NNAPI/XNNPACK)"

    override fun initialize(): Long {
        check(runner == null) { "Inflect lane already initialized" }
        val startedAt = System.nanoTime()
        runner = InflectMicroOnnxRunner(modelDirectory)
        return elapsedMs(startedAt)
    }

    override fun synthesize(text: String, onChunkReady: (index: Int, elapsedMs: Long) -> Unit): LaneUtterance {
        val activeRunner = checkNotNull(runner) { "Inflect lane not initialized" }
        val startedAt = System.nanoTime()
        val normalized = InflectMicroTextFrontend.normalize(SpeechCardText.speakable(text))
        require(normalized.isNotBlank()) { "Inflect normalization produced empty text" }
        val chunks = InflectMicroTextFrontend.phonemizeChunks(normalized) { chunkText ->
            InflectMicroTextFrontend.applyPhonemeOverrides(
                InflectPhonemizer.phonemize(chunkText, espeakDataDirectory),
            )
        }
        require(chunks.isNotEmpty()) { "Inflect normalization produced no synthesis chunks." }
        val frontendMs = elapsedMs(startedAt)
        val laneChunks = chunks.mapIndexed { index, chunk ->
            val synthesis = activeRunner.synthesize(chunk.phonemes)
            val sample = synthesis.waveform
            val laneChunk = LaneChunk(
                index = index,
                phonemeChars = chunk.phonemes.length,
                synthMs = synthesis.synthesisMs,
                audioMs = sample.size * 1000.0 / InflectMicroOnnxRunner.SAMPLE_RATE_HZ,
                peakAbs = sample.maxOfOrNull { kotlin.math.abs(it) } ?: 0f,
                finite = sample.all { it.isFinite() },
            )
            onChunkReady(index, elapsedMs(startedAt))
            laneChunk
        }
        return LaneUtterance(frontendMs = frontendMs, chunks = laneChunks)
    }

    override fun cancel() {
        runner?.cancel()
    }

    override fun resetCancellation() {
        runner?.resetCancellation()
    }

    override fun close() {
        runner?.close()
        runner = null
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
