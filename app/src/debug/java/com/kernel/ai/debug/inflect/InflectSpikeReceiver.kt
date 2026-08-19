package com.kernel.ai.debug.inflect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only endpoint for proving the official Inflect Micro v2 ONNX graphs on Android.
 *
 * This intentionally accepts phoneme text, not user text. The existing Sherpa AAR does not expose
 * its bundled eSpeak-ng phoneme output, so this endpoint stops at graph isolation rather than
 * pretending that a frontend exists.
 */
class InflectSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isExplicitInvocation(appContext, intent)) {
                    finish(pendingResult, RESULT_REJECTED, "explicit_component_required")
                    return@launch
                }
                when (intent.action) {
                    ACTION_CANCEL -> {
                        val cancelled = InflectSpikeRuntime.cancel()
                        finish(
                            pendingResult,
                            if (cancelled) RESULT_OK else RESULT_REJECTED,
                            if (cancelled) "cancel_requested" else "no_active_probe",
                        )
                    }
                    ACTION_RUN_GRAPH_PROBE -> runProbe(appContext, intent, pendingResult)
                    else -> finish(pendingResult, RESULT_REJECTED, "unknown_action")
                }
            } catch (error: CancellationException) {
                finish(pendingResult, RESULT_CANCELLED, "cancelled")
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Inflect graph probe ran out of memory", error)
                finish(pendingResult, RESULT_FAILED, "probe_out_of_memory")
            } catch (error: Exception) {
                Log.e(TAG, "Inflect graph probe failed", error)
                finish(pendingResult, RESULT_FAILED, "probe_failed:${error.message}")
            }
        }
    }

    private fun runProbe(context: Context, intent: Intent, pendingResult: PendingResult) {
        val modelDirectory = File(context.filesDir, MODEL_DIRECTORY)
        if (!File(modelDirectory, InflectMicroOnnxRunner.DURATION_MODEL_FILE).isFile ||
            !File(modelDirectory, InflectMicroOnnxRunner.DECODE_MODEL_FILE).isFile
        ) {
            finish(pendingResult, RESULT_FAILED, "model_files_missing:${modelDirectory.path}")
            return
        }

        val phonemeText = intent.getStringExtra(EXTRA_PHONEME_TEXT) ?: DEFAULT_PHONEME_TEXT
        if (phonemeText.length > InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH) {
            finish(
                pendingResult,
                RESULT_REJECTED,
                "phoneme_text_too_long:max=${InflectMicroOnnxRunner.MAX_PHONEME_TEXT_LENGTH}",
            )
            return
        }
        val label = safeLabel(intent.getStringExtra(EXTRA_LABEL) ?: "graph-probe")
        val wavFile = File(context.filesDir, "$EVIDENCE_DIRECTORY/$label.wav")
        val summaryFile = File(context.filesDir, "$EVIDENCE_DIRECTORY/$label.json")
        wavFile.parentFile?.mkdirs()

        val initStarted = System.nanoTime()
        val runner = InflectMicroOnnxRunner(modelDirectory)
        try {
            InflectSpikeRuntime.install(runner)
            val initMs = (System.nanoTime() - initStarted) / 1_000_000L
            val synthesis = runner.synthesize(
                phonemeText = phonemeText,
                speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f),
                variation = intent.getFloatExtra(EXTRA_VARIATION, 0.667f),
                seed = intent.getLongExtra(EXTRA_SEED, 7L),
            )
            writeFloatWav(wavFile, synthesis.waveform)
            val summary = buildSummary(
                runner = runner,
                synthesis = synthesis,
                initMs = initMs,
                wavFile = wavFile,
                modelDirectory = modelDirectory,
            )
            summaryFile.writeText(summary)
            Log.i(TAG, summary)
            finish(pendingResult, RESULT_OK, summaryFile.path)
        } finally {
            InflectSpikeRuntime.clear(runner)
            runner.close()
        }
    }

    private fun buildSummary(
        runner: InflectMicroOnnxRunner,
        synthesis: com.kernel.ai.core.voice.InflectOnnxSynthesis,
        initMs: Long,
        wavFile: File,
        modelDirectory: File,
    ): String {
        val audioSeconds = synthesis.audioDurationMs / 1_000.0
        val rtf = if (audioSeconds > 0.0) synthesis.synthesisMs / 1_000.0 / audioSeconds else null
        return """
            {
              "model_directory": ${json(modelDirectory.path)},
              "duration_inputs": ${json(runner.durationInputInfo)},
              "duration_outputs": ${json(runner.durationOutputInfo)},
              "decode_inputs": ${json(runner.decodeInputInfo)},
              "decode_outputs": ${json(runner.decodeOutputInfo)},
              "phoneme_text": ${json(synthesis.phonemeText)},
              "token_count": ${synthesis.tokenCount},
              "duration_mean_shape": ${json(synthesis.durationMeanShape.toList())},
              "duration_mask_shape": ${json(synthesis.durationMaskShape.toList())},
              "waveform_shape": ${json(synthesis.waveformShape.toList())},
              "sample_rate_hz": ${InflectMicroOnnxRunner.SAMPLE_RATE_HZ},
              "waveform_samples": ${synthesis.waveform.size},
              "audio_duration_ms": ${synthesis.audioDurationMs},
              "initialization_ms": $initMs,
              "synthesis_ms": ${synthesis.synthesisMs},
              "real_time_factor": ${rtf ?: "null"},
              "peak_abs": ${synthesis.peakAbs},
              "finite": ${synthesis.finite},
              "gain": 1.0,
              "wav_file": ${json(wavFile.path)},
              "playback": "not_attempted_graph_isolation_only"
            }
        """.trimIndent()
    }

    private fun writeFloatWav(file: File, samples: FloatArray) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            val dataSize = samples.size * Float.SIZE_BYTES
            output.writeBytes("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(3) // IEEE float PCM
            output.writeLittleEndianShort(1) // mono
            output.writeLittleEndianInt(InflectMicroOnnxRunner.SAMPLE_RATE_HZ)
            output.writeLittleEndianInt(InflectMicroOnnxRunner.SAMPLE_RATE_HZ * Float.SIZE_BYTES)
            output.writeLittleEndianShort(Float.SIZE_BYTES)
            output.writeLittleEndianShort(Float.SIZE_BYTES * 8)
            output.writeBytes("data")
            output.writeLittleEndianInt(dataSize)
            samples.forEach { output.writeLittleEndianInt(it.toRawBits()) }
        }
    }

    private fun isExplicitInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == InflectSpikeReceiver::class.java.name

    private fun finish(result: PendingResult, code: Int, data: String) {
        result.setResultCode(code)
        result.setResultData(data)
        result.finish()
    }

    private fun safeLabel(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "graph-probe" }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
        is Number, is Boolean -> value.toString()
        else -> json(value.toString())
    }

    private fun DataOutputStream.writeLittleEndianShort(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeLittleEndianInt(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }

    companion object {
        const val ACTION_RUN_GRAPH_PROBE = "com.kernel.ai.debug.action.RUN_INFLECT_GRAPH_PROBE"
        const val ACTION_CANCEL = "com.kernel.ai.debug.action.CANCEL_INFLECT_GRAPH_PROBE"
        const val EXTRA_PHONEME_TEXT = "phoneme_text"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SEED = "seed"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_VARIATION = "variation"
        const val MODEL_DIRECTORY = "inflect-micro-v2-onnx"
        const val EVIDENCE_DIRECTORY = "inflect-spike"
        private const val DEFAULT_PHONEME_TEXT = "həlˈoʊ"
        private const val TAG = "InflectSpike"
        private const val RESULT_OK = 0
        private const val RESULT_REJECTED = 2
        private const val RESULT_FAILED = 3
        private const val RESULT_CANCELLED = 4
    }
}

private object InflectSpikeRuntime {
    private val active = AtomicReference<InflectMicroOnnxRunner?>(null)

    fun install(runner: InflectMicroOnnxRunner) {
        check(active.compareAndSet(null, runner)) { "An Inflect graph probe is already active" }
    }

    fun cancel(): Boolean = active.get()?.let { it.cancel(); true } ?: false

    fun clear(runner: InflectMicroOnnxRunner) {
        active.compareAndSet(runner, null)
    }
}
