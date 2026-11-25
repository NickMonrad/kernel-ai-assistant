package com.kernel.ai.debug.tts

import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import com.kernel.ai.core.voice.InflectMicroTextFrontend
import com.kernel.ai.core.voice.InflectPhonemizer
import com.kernel.ai.core.voice.SpeechCardText
import java.io.File
import org.json.JSONObject

/**
 * Inflect Micro v2 lane. Runs the production sequence used by `InflectMicroVoiceOutputController`'s reply
 * streaming: `SpeechCardText.speakable` → `InflectMicroTextFrontend.normalize` → `phonemizeSpeechChunks`, which
 * phonemizes lazily per chunk (eSpeak via `InflectPhonemizer` plus `applyPhonemeOverrides`) → `InflectMicroOnnxRunner.synthesize`
 * per chunk. Edge fades are applied in the controller and are not timed here.
 * It stops before AudioTrack, so playback latency is not included.
 */
class InflectMicroDebugLane(
    private val modelDirectory: File,
    private val voiceName: String,
    private val espeakDataDirectory: File,
) : DebugTtsLane {
    private var runner: InflectMicroOnnxRunner? = null

    override val laneId: String = DebugTtsLanes.INFLECT_MICRO_V2

    override val backend: String
        get() = "onnxruntime ${onnxRuntimeVersion()} CPU execution provider (default SessionOptions, no NNAPI/XNNPACK)"

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
        var frontendMs = elapsedMs(startedAt)
        // Phonemization is lazy, as in the controller; each chunk's eSpeak time is charged to frontendMs as it runs.
        val chunks = InflectMicroTextFrontend.phonemizeSpeechChunks(normalized) { chunkText ->
            val phonemizeStartedAt = System.nanoTime()
            InflectMicroTextFrontend.applyPhonemeOverrides(
                InflectPhonemizer.phonemize(chunkText, espeakDataDirectory),
            ).also { frontendMs += elapsedMs(phonemizeStartedAt) }
        }
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
        }.toList()
        require(laneChunks.isNotEmpty()) { "Inflect normalization produced no synthesis chunks." }
        return LaneUtterance(frontendMs = frontendMs, chunks = laneChunks)
    }

    override fun cancel() {
        runner?.cancel()
    }

    override fun resetCancellation() {
        runner?.resetCancellation()
    }

    override fun metadata(): JSONObject {
        val models = JSONObject()
            .put("directory", modelDirectory.absolutePath)
            .put("duration_onnx_sha256", sha256Of(File(modelDirectory, "duration.onnx")))
            .put("decode_onnx_sha256", sha256Of(File(modelDirectory, "decode.onnx")))
        val voice = JSONObject()
            .put("sherpa_voice", voiceName)
            .put("espeak_data_dir", espeakDataDirectory.absolutePath)
            .put("espeak_data_files", espeakDataDirectory.walkTopDown().count { it.isFile })
        return JSONObject()
            .put("onnxruntime_version", onnxRuntimeVersion())
            .put("delegate", "CPU")
            .put("intra_op_threads", "ORT default (runner does not set it)")
            .put("available_processors", Runtime.getRuntime().availableProcessors())
            .put("voice", voice)
            .put("models", models)
    }

    override fun close() {
        runner?.close()
        runner = null
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}

/**
 * ONNX Runtime version read reflectively: `core/voice` consumes onnxruntime as `implementation`, so the app
 * module cannot compile against it, but the AAR is packaged in the APK.
 */
private fun onnxRuntimeVersion(): String =
    Class.forName("ai.onnxruntime.OrtEnvironment").let { env ->
        env.getMethod("getVersion").invoke(env.getMethod("getEnvironment").invoke(null)) as String
    }
