package com.kernel.ai

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.voice.asr4all.Asr4allEngine
import com.kernel.ai.core.voice.asr4all.Asr4allModelFiles
import com.kernel.ai.core.voice.asr4all.Asr4allModelFiles.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Device-only evaluation. Explicit staged inputs; creates no commands, timers, or alarms. */
class Asr4allTierMeasurementDeviceTest {
    private data class Clip(val id: String, val ref: String, val bytes: ByteArray, val samples: FloatArray, val seconds: Double)

    @Test fun measureOwnerAndSyntheticSets() {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("measure_enabled") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ownerDir = privatePath(context.filesDir, arg(args, "owner_dir"))
        val ownerRefs = privatePath(context.filesDir, arg(args, "owner_phrases"))
        val output = privatePath(context.filesDir, arg(args, "output_file"))
        val owner = loadSet(ownerDir, ownerRefs, 20)
        val syntheticDirArg = args.getString("synthetic_dir")
        val syntheticRefsArg = args.getString("synthetic_phrases")
        require((syntheticDirArg == null) == (syntheticRefsArg == null)) { "synthetic_dir and synthetic_phrases must be provided together" }
        val syntheticDir = syntheticDirArg?.let { privatePath(context.filesDir, it) }
        val syntheticRefs = syntheticRefsArg?.let { privatePath(context.filesDir, it) }
        if (syntheticDir != null) {
            require(syntheticDir != ownerDir && syntheticRefs != ownerRefs) { "owner and synthetic inputs must be distinct" }
            require(output != syntheticDir && output != syntheticRefs && output != ownerDir && output != ownerRefs) { "output must be distinct from all inputs" }
        } else {
            require(output != ownerDir && output != ownerRefs) { "output must be distinct from all inputs" }
        }
        val synthetic = if (syntheticDir == null) emptyList() else loadSet(syntheticDir, syntheticRefs!!, null)
        val ownerRows = JSONArray()
        val syntheticRows = JSONArray()
        for (clip in owner) {
            for (size in Size.entries) for (tier in TIERS) ownerRows.put(measureAsr4all(context, clip, size.name, tier))
            ownerRows.put(measureZipformer(context, clip))
        }
        for (clip in synthetic) {
            for (size in Size.entries) for (tier in TIERS) syntheticRows.put(measureAsr4all(context, clip, size.name, tier, includeWer = false))
            syntheticRows.put(measureZipformer(context, clip, includeWer = false))
        }
        assertEquals(20, owner.size)
        assertEquals(100, ownerRows.length())
        assertEquals(synthetic.size * 5, syntheticRows.length())
        val result = JSONObject()
            .put("schema", "asr4all-device-measurement-v2")
            .put("device", android.os.Build.MODEL).put("build", android.os.Build.DISPLAY).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("timing_semantics", "100ms chunks paced to scheduled chunk end. First partial begins at first injected frame; clip end is not acoustic endpoint. Final latency begins at scheduled injected clip end and includes signalEndAndDrain/finish plus final PCEC.")
            .put("rtfx_semantics", "Compute-only runtime includes decode, ready/drain, PCEC/result rendering, and final flush; paced delivery wait excluded.")
            .put("owner_decision", JSONObject().put("rows", ownerRows).put("aggregate_wer_by_model_tier", aggregateBySystem(ownerRows)))
            .put("synthetic_breadth", JSONObject().put("status", if (syntheticDirArg == null) "not_run" else "measured_regression_latency_only").put("rows", syntheticRows))
        output.parentFile?.mkdirs()
        output.writeText(result.toString(2) + "\n")
        Log.i(TAG, "ASR4ALL_MEASUREMENT_RESULT owner=${ownerRows.length()} synthetic=${syntheticRows.length()} output=${output.name}")
    }
    private fun measureAsr4all(context: android.content.Context, clip: Clip, model: String, tier: String, includeWer: Boolean = true): JSONObject {
        val modelDir = Asr4allModelFiles.releaseDir(context, Size.valueOf(model))
        val baseline = pssMiB()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        Asr4allEngine.open(modelDir, tier).use { engine ->
            val loadMs = elapsedMs(loadStart)
            val pss = pssMiB() - baseline
            val deliveryStart = SystemClock.elapsedRealtimeNanos()
            var firstPartial: Double? = null
            var firstAudioSec: Double? = null
            var computeNanos = 0L
            var lastFrames = 0
            var lastText = ""
            var offset = 0
            while (offset < clip.samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                val scheduledEnd = deliveryStart + end.toLong() * 1_000_000_000L / RATE
                sleepUntil(scheduledEnd)
                val start = SystemClock.elapsedRealtimeNanos()
                engine.decodeStream(clip.samples.copyOfRange(offset, end))
                if (committedFrames(engine) != lastFrames) {
                    lastFrames = committedFrames(engine)
                    val partial = engine.decodePcec(decodeTail = true).trim()
                    if (partial.isNotEmpty() && partial != lastText) {
                        lastText = partial
                        if (firstPartial == null) {
                            firstPartial = (SystemClock.elapsedRealtimeNanos() - deliveryStart) / 1e6
                            firstAudioSec = end.toDouble() / RATE
                        }
                    }
                }
                computeNanos += SystemClock.elapsedRealtimeNanos() - start
                offset = end
            }
            val scheduledClipEnd = deliveryStart + clip.samples.size.toLong() * 1_000_000_000L / RATE
            val flushStart = SystemClock.elapsedRealtimeNanos()
            engine.finish()
            val transcript = engine.decodePcec(decodeTail = true).trim()
            computeNanos += SystemClock.elapsedRealtimeNanos() - flushStart
            val computeMs = computeNanos / 1e6
            return row(clip, model, tier.removePrefix("stream_"), transcript, loadMs, pss, computeMs,
                clip.seconds / (computeMs / 1000.0), firstPartial, firstAudioSec,
                (SystemClock.elapsedRealtimeNanos() - scheduledClipEnd) / 1e6,
                (scheduledClipEnd - deliveryStart) / 1e6, includeWer)
        }
    }

    /** Mirrors shipping initOnlineRecognizer Zipformer values: 2 CPU threads, greedy, 4 paths. */
    private fun measureZipformer(context: android.content.Context, clip: Clip, includeWer: Boolean = true): JSONObject {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-jni")
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val prefix = "com.k2fsa.sherpa.onnx."
        fun cls(name: String) = Class.forName(prefix + name)
        fun set(target: Any, name: String, value: Any) {
            val type = when (value) { is Int -> Int::class.javaPrimitiveType; is Boolean -> Boolean::class.javaPrimitiveType; else -> value.javaClass }
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            runCatching { target.javaClass.getDeclaredMethod(setter, type).also { it.isAccessible = true }.invoke(target, value) }
                .recoverCatching { target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value) }.getOrThrow()
        }
        fun file(name: String) = File(dir, name).canonicalFile.absolutePath.also { require(File(it).isFile) { "Missing Zipformer model file: $name" } }
        val feat = cls("FeatureConfig").getDeclaredConstructor().newInstance().also { set(it, "sampleRate", RATE); set(it, "featureDim", 80) }
        val transducer = cls("OnlineTransducerModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "encoder", file("sherpa-stt-encoder.int8.onnx")); set(it, "decoder", file("sherpa-stt-decoder.int8.onnx")); set(it, "joiner", file("sherpa-stt-joiner.int8.onnx"))
        }
        val model = cls("OnlineModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "transducer", transducer); set(it, "tokens", file("sherpa-stt-tokens.txt")); set(it, "numThreads", 2); set(it, "debug", false); set(it, "provider", "cpu")
        }
        val config = cls("OnlineRecognizerConfig").getDeclaredConstructor().newInstance().also {
            set(it, "featConfig", feat); set(it, "modelConfig", model); set(it, "endpointConfig", cls("EndpointConfig").getDeclaredConstructor().newInstance())
            set(it, "enableEndpoint", true); set(it, "decodingMethod", "greedy_search"); set(it, "maxActivePaths", 4)
        }
        val rc = cls("OnlineRecognizer")
        val baseline = pssMiB()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        val recognizer = rc.getConstructor(android.content.res.AssetManager::class.java, cls("OnlineRecognizerConfig")).newInstance(null, config)
        val loadMs = elapsedMs(loadStart)
        val stream = try {
            rc.getDeclaredMethod("createStream", String::class.java).invoke(recognizer, "")!!
        } catch (failure: Throwable) {
            try {
                rc.getDeclaredMethod("release").invoke(recognizer)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        try {
            val sc = stream.javaClass
            val accept = sc.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            val finish = sc.getDeclaredMethod("inputFinished")
            val ready = rc.getDeclaredMethod("isReady", sc)
            val decode = rc.getDeclaredMethod("decode", sc)
            val result = rc.getDeclaredMethod("getResult", sc)
            val getText = result.invoke(recognizer, stream).javaClass.getDeclaredMethod("getText")
            val residentPssDelta = pssMiB() - baseline
            val deliveryStart = SystemClock.elapsedRealtimeNanos()
            var firstPartial: Double? = null
            var firstAudioSec: Double? = null
            var compute = 0L
            var offset = 0
            while (offset < clip.samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                sleepUntil(deliveryStart + end.toLong() * 1_000_000_000L / RATE)
                val start = SystemClock.elapsedRealtimeNanos()
                accept.invoke(stream, clip.samples.copyOfRange(offset, end), RATE)
                while (ready.invoke(recognizer, stream) as Boolean) {
                    decode.invoke(recognizer, stream)
                    val text = getText.invoke(result.invoke(recognizer, stream)).toString()
                    if (firstPartial == null && text.isNotBlank()) {
                        firstPartial = (SystemClock.elapsedRealtimeNanos() - deliveryStart) / 1e6
                        firstAudioSec = end.toDouble() / RATE
                    }
                }
                compute += SystemClock.elapsedRealtimeNanos() - start
                offset = end
            }
            val scheduledClipEnd = deliveryStart + clip.samples.size.toLong() * 1_000_000_000L / RATE
            val flushStart = SystemClock.elapsedRealtimeNanos()
            finish.invoke(stream)
            while (ready.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
            val transcript = getText.invoke(result.invoke(recognizer, stream)).toString()
            compute += SystemClock.elapsedRealtimeNanos() - flushStart
            val computeMs = compute / 1e6
            return row(clip, "zipformer", "shipping", transcript, loadMs, residentPssDelta, computeMs,
                clip.seconds / (computeMs / 1000.0), firstPartial, firstAudioSec,
                (SystemClock.elapsedRealtimeNanos() - scheduledClipEnd) / 1e6,
                clip.samples.size * 1000.0 / RATE, includeWer)
        } finally {
            try {
                stream.javaClass.getDeclaredMethod("release").invoke(stream)
            } finally {
                rc.getDeclaredMethod("release").invoke(recognizer)
            }
        }
    }


    private fun row(c: Clip, model: String, tier: String, text: String, load: Double, pss: Double, compute: Double, rtfx: Double, partial: Double?, offset: Double?, finalMs: Double, paced: Double, includeWer: Boolean) = JSONObject()
        .put("fixture_id", c.id).put("fixture_sha256", sha(c.bytes)).put("model", model).put("tier", tier)
        .put("model_load_ms", load).put("resident_pss_delta_mib", pss).put("audio_seconds", c.seconds)
        .put("compute_ms", compute).put("rtfx_compute_only", rtfx).put("paced_delivery_ms", paced)
        .put("first_partial_ms_from_first_sample", partial ?: JSONObject.NULL).put("first_partial_audio_offset_seconds", offset ?: JSONObject.NULL)
        .put("final_after_injected_clip_end_ms_including_flush", finalMs)
        .apply {
            if (includeWer) {
                val wer = Asr4allMeasurementMetrics.wer(c.ref, text)
                put("transcript", text).put("reference_text", c.ref)
                put("wer_edits", wer.edits).put("wer_reference_words", wer.referenceWords)
            }
        }

    private fun loadSet(dir: File, refsFile: File, expected: Int?): List<Clip> {
        val manifestRoot = JSONObject(File(dir, "manifest.json").readText())
        require(manifestRoot.getInt("schema_version") == 1)
        val manifest = manifestRoot.getJSONArray("fixtures")
        val refsRoot = JSONObject(refsFile.readText())
        require(refsRoot.getInt("schema_version") == 1)
        val refs = linkedMapOf<String, String>()
        for (index in 0 until refsRoot.getJSONArray("phrases").length()) {
            val p = refsRoot.getJSONArray("phrases").getJSONObject(index)
            require(refs.put(p.getString("id"), p.getString("text")) == null) { "duplicate reference id" }
        }
        val ids = linkedSetOf<String>()
        val clips = ArrayList<Clip>()
        for (index in 0 until manifest.length()) {
            val e = manifest.getJSONObject(index)
            val id = e.getString("fixture_id")
            require(ids.add(id)) { "duplicate fixture id" }
            val file = File(dir, e.getString("file_name"))
            require(file.canonicalFile.parentFile == dir.canonicalFile) { "fixture path escapes directory" }
            val bytes = file.readBytes()
            require(sha(bytes) == e.getString("sha256")) { "fixture hash mismatch: $id" }
            val pcm = wav(bytes)
            require(pcm.rate == RATE) { "fixture must be 16kHz: $id" }
            require(e.getInt("duration_ms") == pcm.samples.size * 1000 / RATE) { "duration mismatch: $id" }
            clips += Clip(id, refs[id] ?: error("missing phrase reference: $id"), bytes, pcm.samples, pcm.samples.size / RATE.toDouble())
        }
        require(ids == refs.keys) { "phrase and fixture IDs differ" }
        expected?.let { require(ids.size == it) { "expected exactly $it fixture IDs" } }
        return clips
    }

    private fun aggregateBySystem(rows: JSONArray): JSONObject {
        val buckets = linkedMapOf<String, MutableList<Asr4allMeasurementMetrics.Wer>>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val key = "${row.getString("model")}/${row.getString("tier")}"
            buckets.getOrPut(key) { ArrayList() } += Asr4allMeasurementMetrics.Wer(row.getInt("wer_edits"), row.getInt("wer_reference_words"))
        }
        val result = JSONObject()
        for ((key, values) in buckets) {
            val total = Asr4allMeasurementMetrics.aggregate(values)
            result.put(key, JSONObject().put("edits", total.edits).put("reference_words", total.referenceWords).put("wer", total.rate))
        }
        return result
    }

    private fun privatePath(filesDir: File, relative: String): File {
        val base = filesDir.canonicalFile
        val candidate = File(base, relative).canonicalFile
        require(candidate.toPath().startsWith(base.toPath()) && candidate != base) { "measurement path must stay under app-private filesDir" }
        return candidate
    }

    private fun committedFrames(engine: Asr4allEngine): Int =
        engine.javaClass.getDeclaredMethod("getDecodedFrameCount").invoke(engine) as Int
    private fun arg(bundle: android.os.Bundle, name: String) = bundle.getString(name) ?: error("instrumentation argument $name required")
    private fun pssMiB() = Debug.MemoryInfo().let { Debug.getMemoryInfo(it); it.totalPss / 1024.0 }
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    private fun sleepUntil(target: Long) { while (true) { val n = target - SystemClock.elapsedRealtimeNanos(); if (n <= 0) return; SystemClock.sleep((n / 1_000_000).coerceAtLeast(1)) } }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private data class Pcm(val rate: Int, val samples: FloatArray)
    private fun wav(b: ByteArray): Pcm {
        require(b.size >= 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE")
        var at = 12; var rate = 0; var channels = 0; var bits = 0; var fmt = 0; var start = -1; var length = 0
        while (at + 8 <= b.size) { val id = String(b, at, 4); val n = int(b, at + 4); require(n >= 0 && at + 8L + n <= b.size)
            if (id == "fmt ") { fmt = short(b, at + 8); channels = short(b, at + 10); rate = int(b, at + 12); bits = short(b, at + 22) }
            if (id == "data") { start = at + 8; length = n }; at += 8 + n + (n and 1) }
        require(fmt == 1 && channels == 1 && bits == 16 && start >= 0 && length > 0) { "Expected mono PCM16 WAV" }
        return Pcm(rate, FloatArray(length / 2) { short(b, start + it * 2).toShort().toInt() / 32768f })
    }
    private fun short(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
    private fun int(b: ByteArray, i: Int) = short(b, i) or (short(b, i + 2) shl 16)
    private companion object { const val TAG = "Asr4allMeasure"; const val RATE = 16_000; const val CHUNK_SAMPLES = 1_600; val TIERS = listOf("stream_c16r4", "stream_c32r8") }
}
