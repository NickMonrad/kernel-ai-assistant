package com.kernel.ai.debug.inflect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import com.kernel.ai.core.voice.InflectMicroModelSpec
import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import com.kernel.ai.core.voice.InflectMicroTextFrontend
import com.kernel.ai.core.voice.InflectPhonemizer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only TTS baseline (#2088): runs a fixed text corpus through the production Inflect Micro
 * frontend (normalize, eSpeak phonemize, overrides, chunking) and the ONNX runner, once cold and
 * then for repeated warm passes, and writes one JSON summary to `files/inflect_baseline/<label>.json`.
 *
 * It does not play audio, so it measures synthesis cost, not AudioTrack latency. Explicit component
 * only; the DUMP permission restricts senders to adb shell. The corpus file and data directory are
 * supplied by the caller; nothing personal is read.
 */
class InflectBaselineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.component?.className != InflectBaselineReceiver::class.java.name) {
            Log.w(TAG, "inflect baseline: explicit component required")
            return
        }
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = runBaseline(appContext, intent)
                Log.i(TAG, "inflect baseline done path=${summary.path}")
                pendingResult.setResult(RESULT_OK, summary.path, null)
            } catch (error: Exception) {
                Log.e(TAG, "inflect baseline failed", error)
                pendingResult.setResult(RESULT_FAILED, "baseline_failed:${error.message}", null)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun runBaseline(context: Context, intent: Intent): File {
        val modelDirectory = InflectMicroModelSpec.modelDirectory(context)
        require(File(modelDirectory, InflectMicroOnnxRunner.DURATION_MODEL_FILE).isFile) { "duration model missing" }
        require(File(modelDirectory, InflectMicroOnnxRunner.DECODE_MODEL_FILE).isFile) { "decode model missing" }
        val corpusFile = File(requireNotNull(intent.getStringExtra(EXTRA_CORPUS)) { "corpus extra required" })
        val dataDirectory = File(requireNotNull(intent.getStringExtra(EXTRA_DATA_DIRECTORY)) { "data_directory extra required" })
            .canonicalFile
        require(dataDirectory.path.startsWith("${context.filesDir.canonicalPath}${File.separator}")) {
            "eSpeak data directory must be app-private"
        }
        val repeats = intent.getIntExtra(EXTRA_REPEATS, 3).coerceIn(1, 10)
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val variation = intent.getFloatExtra(EXTRA_VARIATION, 0.667f)
        val seed = intent.getLongExtra(EXTRA_SEED, 7L)
        val label = safeLabel(intent.getStringExtra(EXTRA_LABEL) ?: "baseline")

        val corpusBytes = corpusFile.readBytes()
        val corpus = String(corpusBytes, Charsets.UTF_8).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        require(corpus.isNotEmpty()) { "corpus is empty" }
        val corpusSha = MessageDigest.getInstance("SHA-256").digest(corpusBytes)
            .joinToString("") { "%02x".format(it) }

        var peakPssKb = pssKb()
        val pssBeforeKb = peakPssKb
        val initStart = System.nanoTime()
        val runner = InflectMicroOnnxRunner(modelDirectory)
        val initMs = elapsedMs(initStart)
        val rows = JSONArray()
        try {
            peakPssKb = maxOf(peakPssKb, pssKb())
            for (pass in 1..repeats) {
                corpus.forEachIndexed { index, text ->
                    val normalizeStart = System.nanoTime()
                    val normalized = InflectMicroTextFrontend.normalize(text)
                    val normalizeMs = elapsedMs(normalizeStart)
                    var phonemizeMs = 0L
                    var synthMs = 0L
                    var audioMs = 0L
                    var chunkCount = 0
                    val chunks = InflectMicroTextFrontend.phonemizeChunks(normalized) { chunkText ->
                        val phonemizeStart = System.nanoTime()
                        val phonemes = InflectMicroTextFrontend.applyPhonemeOverrides(
                            InflectPhonemizer.phonemize(chunkText, dataDirectory),
                        )
                        phonemizeMs += elapsedMs(phonemizeStart)
                        phonemes
                    }
                    var finite = true
                    var peakAbs = 0.0f
                    chunks.forEach { chunk ->
                        val synthesis = runner.synthesize(
                            phonemeText = chunk.phonemes,
                            speed = speed,
                            variation = variation,
                            seed = seed,
                        )
                        synthMs += synthesis.synthesisMs
                        audioMs += synthesis.audioDurationMs
                        finite = finite && synthesis.finite
                        peakAbs = maxOf(peakAbs, synthesis.peakAbs)
                        chunkCount++
                        peakPssKb = maxOf(peakPssKb, pssKb())
                    }
                    rows.put(
                        JSONObject()
                            .put("pass", pass)
                            .put("index", index)
                            .put("chars", text.length)
                            .put("chunks", chunkCount)
                            .put("normalize_ms", normalizeMs)
                            .put("phonemize_ms", phonemizeMs)
                            .put("synth_ms", synthMs)
                            .put("audio_ms", audioMs)
                            .put("rtf", if (audioMs > 0) synthMs.toDouble() / audioMs else JSONObject.NULL)
                            .put("finite", finite)
                            .put("peak_abs", peakAbs.toDouble()),
                    )
                }
            }
        } finally {
            runner.close()
        }

        val summary = JSONObject()
            .put("label", label)
            .put("corpus_sha256", corpusSha)
            .put("corpus_lines", corpus.size)
            .put("repeats", repeats)
            .put("speed", speed.toDouble())
            .put("variation", variation.toDouble())
            .put("seed", seed)
            .put("init_ms", initMs)
            .put("pss_before_kb", pssBeforeKb)
            .put("pss_peak_kb", peakPssKb)
            .put("model_directory", modelDirectory.path)
            .put("rows", rows)
        val outDir = File(context.filesDir, "inflect_baseline").apply { mkdirs() }
        val out = File(outDir, "$label.json")
        out.writeText(summary.toString(2))
        return out
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun safeLabel(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "baseline" }

    companion object {
        const val EXTRA_CORPUS = "corpus_path"
        const val EXTRA_DATA_DIRECTORY = "data_directory"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_VARIATION = "variation"
        const val EXTRA_SEED = "seed"
        const val EXTRA_LABEL = "label"
        private const val TAG = "KernelAI"
        private const val RESULT_OK = 0
        private const val RESULT_FAILED = 2
    }
}
