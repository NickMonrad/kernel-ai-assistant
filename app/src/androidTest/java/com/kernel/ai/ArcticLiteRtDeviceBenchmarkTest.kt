package com.kernel.ai

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.kernel.ai.core.inference.SentencePieceTokenizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/** Phase-A-only candidate model benchmark. Does not instantiate or alter production RAG. */
class ArcticLiteRtDeviceBenchmarkTest {
    @Test
    fun benchmarkPinnedCandidateAgainstReferenceInputs() {
        val modelFile = File("/data/local/tmp/arctic_phase_a.tflite")
        val fixturesFile = File("/data/local/tmp/arctic_phase_a_inputs.json")
        assertTrue("Push arctic_phase_a.tflite to /data/local/tmp", modelFile.isFile)
        assertTrue("Push arctic_phase_a_inputs.json to /data/local/tmp", fixturesFile.isFile)
        val cases = JSONObject(fixturesFile.readText()).getJSONArray("cases")
        assertTrue("Expected device parity and latency cases", cases.length() >= 5)

        val baselinePssMiB = pssMiB()
        val baselineRssMiB = rssMiB()
        val initStart = SystemClock.elapsedRealtimeNanos()
        val interpreter = Interpreter(mapModelFile(modelFile), Interpreter.Options().apply { numThreads = 4 })
        interpreter.allocateTensors()
        val loadAndAllocateMs = elapsedMs(initStart)
        val loadedPssMiB = pssMiB()
        val loadedRssMiB = rssMiB()

        assertEquals("Two BERT inputs", 2, interpreter.inputTensorCount)
        assertEquals("One pooled embedding output", 1, interpreter.outputTensorCount)
        val inputShape = interpreter.getInputTensor(0).shape()
        assertEquals(1, inputShape[0])
        assertEquals(512, inputShape[1])
        val outputShape = interpreter.getOutputTensor(0).shape()
        assertEquals(1, outputShape[0])
        assertEquals(768, outputShape[1])

        val inputIds = Array(1) { IntArray(512) }
        val attentionMask = Array(1) { IntArray(512) }
        val output = Array(1) { FloatArray(768) }
        val latenciesMs = mutableListOf<Double>()
        val parity = mutableListOf<Double>()
        val outputNorms = mutableListOf<Double>()
        var peakPssMiB = loadedPssMiB
        var peakRssMiB = loadedRssMiB
        var firstEmbeddingMs: Double? = null

        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex)
            val ids = case.getJSONArray("input_ids")
            val mask = case.getJSONArray("attention_mask")
            for (i in 0 until 512) {
                inputIds[0][i] = ids.getInt(i)
                attentionMask[0][i] = mask.getInt(i)
            }
            val expected = case.getJSONArray("reference")
            val repeats = if (caseIndex == 0) 9 else 3
            repeat(repeats) { repeatIndex ->
                val start = SystemClock.elapsedRealtimeNanos()
                interpreter.runForMultipleInputsOutputs(
                    arrayOf<Any>(inputIds, attentionMask),
                    mapOf(0 to output),
                )
                val duration = elapsedMs(start)
                if (caseIndex == 0 && repeatIndex == 0) firstEmbeddingMs = duration
                if (repeatIndex > 0 || caseIndex > 0) latenciesMs += duration

                var dot = 0.0
                var outputNorm = 0.0
                var expectedNorm = 0.0
                for (i in 0 until 768) {
                    val value = output[0][i].toDouble()
                    assertTrue("Non-finite candidate output at dimension $i", value.isFinite())
                    val ref = expected.getDouble(i)
                    dot += value * ref
                    outputNorm += value * value
                    expectedNorm += ref * ref
                }
                outputNorms += sqrt(outputNorm)
                parity += dot / sqrt(outputNorm * expectedNorm)
                peakPssMiB = maxOf(peakPssMiB, pssMiB())
                peakRssMiB = maxOf(peakRssMiB, rssMiB())
            }
        }
        interpreter.close()

        val sorted = latenciesMs.sorted()
        val evidence = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("build", android.os.Build.DISPLAY)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("runtime", "org.tensorflow.lite.Interpreter 2.17.0; 4 threads; CPU")
            .put("model_bytes", modelFile.length())
            .put("load_and_allocate_ms", loadAndAllocateMs)
            .put("first_embedding_ms", firstEmbeddingMs)
            .put("steady_embedding_count", sorted.size)
            .put("steady_median_ms", percentile(sorted, 0.5))
            .put("steady_p90_ms", percentile(sorted, 0.9))
            .put("baseline_pss_mib", baselinePssMiB)
            .put("loaded_pss_mib", loadedPssMiB)
            .put("peak_pss_mib", peakPssMiB)
            .put("baseline_rss_mib", baselineRssMiB)
            .put("loaded_rss_mib", loadedRssMiB)
            .put("peak_rss_mib", peakRssMiB)
            .put("reference_case_cosine_min", parity.minOrNull())
            .put("output_norm_min", outputNorms.minOrNull())
            .put("output_norm_max", outputNorms.maxOrNull())
            .put("reference_case_cosine_mean", parity.average())
            .put("no_crash", true)
        Log.i(TAG, "PHASE_A_RESULT ${evidence}")
        assertTrue("All reference comparisons must be finite", parity.all { it.isFinite() })
    }

    @Test
    fun benchmarkGenericProductionEmbeddingGemmaAgainstSameTextInputs() {
        val modelFile = File("/data/local/tmp/embeddinggemma-300M_seq512_mixed-precision.tflite")
        val tokenizerFile = File("/data/local/tmp/sentencepiece.model")
        val fixturesFile = File("/data/local/tmp/arctic_phase_a_inputs.json")
        assertTrue("Push the generic production EmbeddingGemma model", modelFile.isFile)
        assertTrue("Push the matching production SentencePiece model", tokenizerFile.isFile)
        assertTrue("Push the shared Arctic text fixtures", fixturesFile.isFile)

        val cases = JSONObject(fixturesFile.readText()).getJSONArray("cases")
        assertTrue("Expected the same five text cases used for Arctic", cases.length() >= 5)
        val tokenizer = SentencePieceTokenizer(tokenizerFile)
        data class PreparedInput(val ids: Array<IntArray>, val tokenCount: Int)
        val inputs = (0 until cases.length()).map { caseIndex ->
            val text = cases.getJSONObject(caseIndex).getString("text")
            val tokenIds = tokenizer.encode(text, maxLen = 512)
            PreparedInput(
                ids = Array(1) { IntArray(512) { i -> tokenIds.getOrElse(i) { 0 } } },
                tokenCount = tokenIds.size,
            )
        }

        val baselinePssMiB = pssMiB()
        val baselineRssMiB = rssMiB()
        val initStart = SystemClock.elapsedRealtimeNanos()
        val interpreter = Interpreter(mapModelFile(modelFile), Interpreter.Options().apply { numThreads = 4 })
        interpreter.allocateTensors()
        val loadAndAllocateMs = elapsedMs(initStart)
        val loadedPssMiB = pssMiB()
        val loadedRssMiB = rssMiB()

        assertEquals("One token-id input in the pinned generic model", 1, interpreter.inputTensorCount)
        assertEquals("One pooled embedding output", 1, interpreter.outputTensorCount)
        val inputShape = interpreter.getInputTensor(0).shape()
        assertEquals(1, inputShape[0])
        assertEquals(512, inputShape[1])
        val outputShape = interpreter.getOutputTensor(0).shape()
        assertEquals(1, outputShape[0])
        assertEquals(768, outputShape[1])

        val output = Array(1) { FloatArray(768) }
        val latenciesMs = mutableListOf<Double>()
        var peakPssMiB = loadedPssMiB
        var peakRssMiB = loadedRssMiB
        var firstEmbeddingMs: Double? = null

        inputs.forEachIndexed { caseIndex, input ->
            val repeats = if (caseIndex == 0) 9 else 3
            repeat(repeats) { repeatIndex ->
                val start = SystemClock.elapsedRealtimeNanos()
                interpreter.run(input.ids, output)
                val outputNorm = sqrt(output[0].sumOf { (it * it).toDouble() })
                assertTrue("Non-finite or zero EmbeddingGemma output", outputNorm.isFinite() && outputNorm > 0.0)
                for (i in output[0].indices) output[0][i] = (output[0][i] / outputNorm).toFloat()
                val duration = elapsedMs(start)
                if (caseIndex == 0 && repeatIndex == 0) firstEmbeddingMs = duration
                if (repeatIndex > 0 || caseIndex > 0) latenciesMs += duration
                assertTrue("Non-finite normalized output", output[0].all { it.isFinite() })
                peakPssMiB = maxOf(peakPssMiB, pssMiB())
                peakRssMiB = maxOf(peakRssMiB, rssMiB())
            }
        }
        interpreter.close()

        val sorted = latenciesMs.sorted()
        val evidence = JSONObject()
            .put("device", android.os.Build.MODEL)
            .put("build", android.os.Build.DISPLAY)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("runtime", "org.tensorflow.lite.Interpreter 2.17.0; 4 threads; CPU")
            .put("model", "EmbeddingGemma 300M generic mixed-precision")
            .put("model_bytes", modelFile.length())
            .put("tokenizer", "production SentencePieceTokenizer; input preparation excluded from timing")
            .put("fixture_count", inputs.size)
            .put("token_count_min", inputs.minOf { it.tokenCount })
            .put("token_count_max", inputs.maxOf { it.tokenCount })
            .put("load_and_allocate_ms", loadAndAllocateMs)
            .put("first_embedding_ms", firstEmbeddingMs)
            .put("steady_embedding_count", sorted.size)
            .put("steady_median_ms", percentile(sorted, 0.5))
            .put("steady_p90_ms", percentile(sorted, 0.9))
            .put("baseline_pss_mib", baselinePssMiB)
            .put("loaded_pss_mib", loadedPssMiB)
            .put("peak_pss_mib", peakPssMiB)
            .put("loaded_model_pss_delta_mib", loadedPssMiB - baselinePssMiB)
            .put("peak_model_pss_delta_mib", peakPssMiB - baselinePssMiB)
            .put("baseline_rss_mib", baselineRssMiB)
            .put("loaded_rss_mib", loadedRssMiB)
            .put("peak_rss_mib", peakRssMiB)
            .put("loaded_model_rss_delta_mib", loadedRssMiB - baselineRssMiB)
            .put("peak_model_rss_delta_mib", peakRssMiB - baselineRssMiB)
            .put("no_crash", true)
        Log.i(TAG, "EMBEDDING_GEMMA_BASELINE_RESULT ${evidence}")
    }

    private fun mapModelFile(file: File): MappedByteBuffer = FileInputStream(file).use { input ->
        input.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    private fun elapsedMs(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private fun pssMiB(): Double {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss / 1024.0
    }

    private fun rssMiB(): Double {
        val kb = File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("VmHWM:") }.trim().split(Regex("\\s+"))[1].toLong()
        }
        return kb / 1024.0
    }

    private fun percentile(values: List<Double>, q: Double): Double {
        val index = ((values.size - 1) * q).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }

    private companion object {
        const val TAG = "ArcticPhaseA"
    }
}
