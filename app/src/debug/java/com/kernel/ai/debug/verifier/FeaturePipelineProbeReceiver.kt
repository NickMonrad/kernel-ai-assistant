package com.kernel.ai.debug.verifier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.EnumSet
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only feature-pipeline provider probe (#1432 Outcome B).
 *
 * Executes the production Stage 1/2/3 feature logic against a fixed PCM
 * fixture on-device and records BOTH execution paths for Stage 2:
 *
 *   - the production embedding session (NNAPI, CPU_DISABLED — exactly the
 *     options OnnxWakeWordDetector uses, so the embedding model runs on the
 *     device's NNAPI backend); and
 *   - a pure-CPU embedding session (the training/reference execution).
 *
 * Stage 1 (mel) and Stage 3 (classifier) always run on CPU, exactly as in
 * production.  The probe writes a JSON dump of both embedding streams and
 * both confidence streams to `files/feature_probe/results.json` and returns
 * per-path summaries (embedding SHA-256, max confidence, per-chunk max abs
 * diff between the paths) in the broadcast result.
 *
 * This lets the #1432 parity evidence answer whether the device's Stage-2
 * provider assignment (NNAPI) introduces a numerical divergence from the
 * CPU reference distribution the classifier was trained on — without any
 * production change.
 *
 * Invocation (explicit component only, never implicit):
 *
 *     adb shell am broadcast \
 *       -a com.kernel.ai.debug.action.FEATURE_PIPELINE_PROBE \
 *       -n com.kernel.ai.debug/com.kernel.ai.debug.verifier.FeaturePipelineProbeReceiver \
 *       --es fixture_file natural_wake_16k.pcm
 *
 * `fixture_file` is a 16 kHz mono int16 raw PCM file (or .wav with that
 * format) in app-private `files/acoustic-fixtures/`.  Debug builds only —
 * the receiver exists in `app/src/debug` and is never part of a release APK.
 */
class FeaturePipelineProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "feature-probe") {
            try {
                if (!isExplicitReceiverInvocation(context, intent)) {
                    finishProbe(pending, "error:receiver_must_be_explicit")
                    return@thread
                }
                val fixtureName = intent.getStringExtra("fixture_file") ?: ""
                val fixture = File(context.filesDir, "acoustic-fixtures/$fixtureName")
                if (!fixture.isFile) {
                    finishProbe(pending, "error:fixture_missing:$fixtureName")
                    return@thread
                }
                val pcm = readPcm(fixture) ?: run {
                    finishProbe(pending, "error:fixture_unreadable:$fixtureName")
                    return@thread
                }
                val result = runProbe(context, pcm, fixtureName)
                finishProbe(pending, result)
            } catch (e: Exception) {
                Log.e(TAG, "FeaturePipelineProbe failed", e)
                finishProbe(pending, "error:${e::class.java.simpleName}:${e.message}")
            }
        }
    }

    private fun finishProbe(pending: PendingResult, data: String) {
        Log.i(TAG, "feature pipeline probe result: $data")
        pending.setResultCode(android.app.Activity.RESULT_OK)
        pending.setResultData(data)
        pending.finish()
    }

    private fun isExplicitReceiverInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == FeaturePipelineProbeReceiver::class.java.name

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun runProbe(context: Context, pcm: ShortArray, fixtureName: String): String {
        val env = OrtEnvironment.getEnvironment()
        val assetBytes = { name: String ->
            context.assets.open("models/wakeword/$name").use { it.readBytes() }
        }
        val cpuOptions = OrtSession.SessionOptions()
        val melSession = env.createSession(assetBytes("melspectrogram.onnx"), cpuOptions)
        val classSession = env.createSession(assetBytes("hey_jandal.onnx"), cpuOptions)
        val nnapiOptions = OrtSession.SessionOptions()
        nnapiOptions.addNnapi(EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
        val embedNnapiSession = env.createSession(assetBytes("embedding_model.onnx"), nnapiOptions)
        val embedCpuSession = env.createSession(assetBytes("embedding_model.onnx"), cpuOptions)

        val melsInput = melSession.inputNames.first()
        val melsOutput = melSession.outputNames.first()
        val embedInput = embedNnapiSession.inputNames.first()
        val embedOutput = embedNnapiSession.outputNames.first()
        val classInput = classSession.inputNames.first()
        val classOutput = classSession.outputNames.first()

        // Production Stage-1 framing (melState + tail) — same as the detector.
        val melRing = FloatArray(76 * 32)
        val tail = FloatArray(480)
        var tailFilled = 0
        var filled = 0
        val inputBuf = FloatArray(1280 + 480)
        val framePcm = FloatArray(1280)
        val embedInput4D = FloatArray(76 * 32)
        val windowFlat = FloatArray(16 * 96)

        val nnapiEmbeddings = ArrayList<FloatArray>()
        val cpuEmbeddings = ArrayList<FloatArray>()
        val nnapiConfidences = ArrayList<Float>()
        val cpuConfidences = ArrayList<Float>()

        val chunks = pcm.size / 1280
        for (c in 0 until chunks) {
            for (i in 0 until 1280) framePcm[i] = pcm[c * 1280 + i].toFloat()
            val inputSamples: Int
            if (tailFilled >= 480) {
                tail.copyInto(inputBuf, 0)
                framePcm.copyInto(inputBuf, 480)
                inputSamples = 1280 + 480
            } else {
                framePcm.copyInto(inputBuf, 0, 0, 1280)
                inputSamples = 1280
            }
            val melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuf, 0, inputSamples), longArrayOf(1L, inputSamples.toLong()))
            val melRows = melTensor.use { melIn ->
                melSession.run(mapOf(melsInput to melIn)).use { melOut ->
                    val t = melOut[melsOutput].get() as OnnxTensor
                    val rows = (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)
                    val flat = FloatArray(rows.size * 32)
                    for (r in 0 until rows.size) {
                        val row = rows[r] as FloatArray
                        for (b in 0 until 32) flat[r * 32 + b] = row[b] / 10.0f + 2.0f
                    }
                    flat
                }
            }
            framePcm.copyInto(tail, 0, 1280 - 480, 1280)
            tailFilled = 480
            val rowsToAppend = melRows.size / 32
            val overflow = filled + rowsToAppend - 76
            if (overflow > 0) {
                melRing.copyInto(melRing, 0, overflow * 32, filled * 32)
                melRows.copyInto(melRing, (filled - overflow) * 32)
                filled = 76
            } else {
                melRows.copyInto(melRing, filled * 32, 0, rowsToAppend * 32)
                filled += rowsToAppend
            }
            if (filled < 76) continue

            melRing.copyInto(embedInput4D)
            val nnapiEmb = runEmbedding(env, embedNnapiSession, embedInput, embedOutput, embedInput4D)
            val cpuEmb = runEmbedding(env, embedCpuSession, embedInput, embedOutput, embedInput4D)
            nnapiEmbeddings.add(nnapiEmb)
            cpuEmbeddings.add(cpuEmb)
            if (nnapiEmbeddings.size >= 16) {
                val nnWindow = buildWindow(nnapiEmbeddings)
                val cpuWindow = buildWindow(cpuEmbeddings)
                nnapiConfidences.add(runClassifier(env, classSession, classInput, classOutput, nnWindow))
                cpuConfidences.add(runClassifier(env, classSession, classInput, classOutput, cpuWindow))
            }
        }

        // Per-chunk embedding max abs diff (NNAPI vs CPU).
        var maxEmbDiff = 0.0
        var maxEmbDiffChunk = -1
        for (i in nnapiEmbeddings.indices) {
            var d = 0.0
            for (k in 0 until 96) {
                val a = kotlin.math.abs(nnapiEmbeddings[i][k] - cpuEmbeddings[i][k]).toDouble()
                if (a > d) d = a
            }
            if (d > maxEmbDiff) { maxEmbDiff = d; maxEmbDiffChunk = i + 1 }
        }
        var maxConfDiff = 0.0
        for (i in nnapiConfidences.indices) {
            val d = kotlin.math.abs(nnapiConfidences[i] - cpuConfidences[i]).toDouble()
            if (d > maxConfDiff) maxConfDiff = d
        }

        val out = JSONObject()
        out.put("fixture", fixtureName)
        out.put("chunks", chunks)
        out.put("embedding_chunks", nnapiEmbeddings.size)
        out.put("max_embedding_abs_diff_nnapi_vs_cpu", maxEmbDiff)
        out.put("max_embedding_abs_diff_chunk", maxEmbDiffChunk)
        out.put("max_confidence_abs_diff_nnapi_vs_cpu", maxConfDiff)
        out.put("nnapi_max_confidence", if (nnapiConfidences.isEmpty()) -1.0 else nnapiConfidences.max().toDouble())
        out.put("cpu_max_confidence", if (cpuConfidences.isEmpty()) -1.0 else cpuConfidences.max().toDouble())
        out.put("nnapi_embeddings", JSONArray().apply {
            nnapiEmbeddings.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
        })
        out.put("cpu_embeddings", JSONArray().apply {
            cpuEmbeddings.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
        })
        out.put("nnapi_confidences", JSONArray().apply { nnapiConfidences.forEach { put(it.toDouble()) } })
        out.put("cpu_confidences", JSONArray().apply { cpuConfidences.forEach { put(it.toDouble()) } })

        val outDir = File(context.filesDir, "feature_probe").apply { mkdirs() }
        val outFile = File(outDir, "results.json")
        outFile.writeText(out.toString())
        val bytes = outFile.readBytes()

        var summary = "ok:chunks=$chunks embs=${nnapiEmbeddings.size} " +
            "maxEmbDiffNnapiVsCpu=$maxEmbDiff chunk=$maxEmbDiffChunk " +
            "maxConfDiff=$maxConfDiff nnapiMaxConf=${out.getDouble("nnapi_max_confidence")} " +
            "cpuMaxConf=${out.getDouble("cpu_max_confidence")} " +
            "dump_sha256=${sha256(bytes)}"
        // Compact per-chunk confidence comparison for the phrase band.
        val band = ArrayList<String>()
        for (i in nnapiConfidences.indices) {
            band.add("%.3f/%.3f".format(cpuConfidences[i], nnapiConfidences[i]))
        }
        summary += " confidences(cpu/nnapi)=${band.joinToString(",")}"
        return summary
    }

    private fun runEmbedding(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        outputName: String,
        melRing: FloatArray,
    ): FloatArray {
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(melRing), longArrayOf(1L, 76L, 32L, 1L),
        )
        return tensor.use { embedIn ->
            session.run(mapOf(inputName to embedIn)).use { embedOut ->
                val t = embedOut[outputName].get() as OnnxTensor
                (((t.value as Array<*>)[0] as Array<*>)[0] as Array<*>)[0] as FloatArray
            }
        }
    }

    private fun buildWindow(embeddings: List<FloatArray>): FloatArray {
        val out = FloatArray(16 * 96)
        for (f in 0 until 16) {
            embeddings[embeddings.size - 16 + f].copyInto(out, f * 96)
        }
        return out
    }

    private fun runClassifier(
        env: OrtEnvironment,
        session: OrtSession,
        inputName: String,
        outputName: String,
        windowFlat: FloatArray,
    ): Float {
        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(windowFlat), longArrayOf(1L, 16L, 96L),
        )
        return tensor.use { classIn ->
            session.run(mapOf(inputName to classIn)).use { classOut ->
                val t = classOut[outputName].get() as OnnxTensor
                ((t.value as Array<*>)[0] as FloatArray)[0]
            }
        }
    }

    /** Reads 16 kHz mono int16 raw PCM or a WAV with that format. */
    private fun readPcm(file: File): ShortArray? {
        val raf = RandomAccessFile(file, "r")
        return try {
            val isWav = file.name.endsWith(".wav")
            var offset = 0L
            var length = raf.length()
            if (isWav) {
                val bytes = ByteArray(44)
                raf.readFully(bytes)
                val chunkSize = readLeInt(bytes, 4)
                val sampleRate = readLeInt(bytes, 24)
                val bitsPerSample = readLeInt(bytes, 34)
                if (sampleRate != 16000 || bitsPerSample != 16) return null
                offset = 44L
                length = chunkSize - 8L
            }
            val sampleBytes = ByteArray((length - offset).toInt())
            raf.seek(offset)
            raf.readFully(sampleBytes)
            ShortArray(sampleBytes.size / 2) { i ->
                ((sampleBytes[2 * i].toInt() and 0xFF) or (sampleBytes[2 * i + 1].toInt() shl 8)).toShort()
            }
        } finally {
            raf.close()
        }
    }

    private fun readLeInt(header: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((header[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private companion object {
        const val TAG = "FeaturePipelineProbe"
    }
}
