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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val owner = loadSet(File(arg(args, "owner_dir")), File(arg(args, "owner_phrases")), 20)
        val synthetic = loadSet(File(arg(args, "synthetic_dir")), File(arg(args, "synthetic_phrases")), null)
        val output = File(arg(args, "output_file"))
        val ownerRows = JSONArray()
        val syntheticRows = JSONArray()
        for (clip in owner) {
            for (size in Size.entries) for (tier in TIERS) ownerRows.put(measureAsr4all(context, clip, size.name, tier))
            ownerRows.put(measureZipformer(context, clip))
        }
        for (clip in synthetic) {
            for (size in Size.entries) for (tier in TIERS) syntheticRows.put(measureAsr4all(context, clip, size.name, tier))
            syntheticRows.put(measureZipformer(context, clip))
        }
        val result = JSONObject()
            .put("schema", "asr4all-device-measurement-v2")
            .put("device", android.os.Build.MODEL).put("build", android.os.Build.DISPLAY).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("timing_semantics", "100ms chunks paced against elapsedRealtimeNanos. First partial starts at first injected frame; clip end is not acoustic endpoint. Final latency includes finish and PCEC flush.")
            .put("rtfx_semantics", "Compute-only inference RTFx divided by audio duration; paced delivery wait excluded.")
            .put("owner_decision", JSONObject().put("rows", ownerRows).put("aggregate_wer", aggregate(ownerRows)))
            .put("synthetic_breadth", JSONObject().put("rows", syntheticRows).put("aggregate_wer", aggregate(syntheticRows)))
        output.parentFile?.mkdirs()
        output.writeText(result.toString(2) + "\n")
        Log.i(TAG, "ASR4ALL_MEASUREMENT_RESULT owner=${ownerRows.length()} synthetic=${syntheticRows.length()} output=${output.name}")
        assertEquals(20, owner.size)
        assertEquals(100, ownerRows.length())
        assertEquals(synthetic.size * 5, syntheticRows.length())
    }

    private fun measureAsr4all(context: android.content.Context, clip: Clip, model: String, tier: String): JSONObject {
        val baseline = pssMiB()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        Asr4allEngine.open(Asr4allModelFiles.releaseDir(context, Size.valueOf(model)), tier).use { engine ->
            val loadMs = elapsedMs(loadStart)
            val pss = pssMiB() - baseline
            val firstInput = SystemClock.elapsedRealtimeNanos()
            val deliveryStart = firstInput
            var firstPartial: Double? = null
            var firstAudioSec: Double? = null
            var computeNanos = 0L
            var offset = 0
            while (offset < clip.samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                sleepUntil(deliveryStart + offset.toLong() * 1_000_000_000L / RATE)
                val start = SystemClock.elapsedRealtimeNanos()
                engine.decodeStream(clip.samples.copyOfRange(offset, end))
                computeNanos += SystemClock.elapsedRealtimeNanos() - start
                val partial = engine.decodePcec(decodeTail = true)
                val observed = SystemClock.elapsedRealtimeNanos()
                if (firstPartial == null && partial.isNotBlank()) {
                    firstPartial = (observed - firstInput) / 1e6
                    firstAudioSec = end.toDouble() / RATE
                }
                offset = end
            }
            val clipEnd = SystemClock.elapsedRealtimeNanos()
            val flushStart = clipEnd
            engine.finish()
            val transcript = engine.decodePcec(decodeTail = true)
            computeNanos += SystemClock.elapsedRealtimeNanos() - flushStart
            val ms = computeNanos / 1e6
            return row(clip, model, tier.removePrefix("stream_"), transcript, loadMs, pss, ms, clip.seconds / (ms / 1000), firstPartial, firstAudioSec, (SystemClock.elapsedRealtimeNanos() - clipEnd) / 1e6, (clipEnd - firstInput) / 1e6)
        }
    }

    /** Mirrors shipping initOnlineRecognizer Zipformer values: 2 CPU threads, greedy, 4 paths. */
    private fun measureZipformer(context: android.content.Context, clip: Clip): JSONObject {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val prefix = "com.k2fsa.sherpa.onnx."
        fun cls(name: String) = Class.forName(prefix + name)
        fun set(target: Any, name: String, value: Any) {
            val type = when (value) { is Int -> Int::class.javaPrimitiveType; is Boolean -> Boolean::class.javaPrimitiveType; else -> value.javaClass }
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            runCatching { target.javaClass.getDeclaredMethod(setter, type).also { it.isAccessible = true }.invoke(target, value) }
                .recoverCatching { target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value) }.getOrThrow()
        }
        fun file(name: String) = File(dir, name).absolutePath.also { require(File(it).isFile) { "Missing Zipformer model file: $name" } }
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
        val load = SystemClock.elapsedRealtimeNanos()
        val recognizer = rc.getConstructor(android.content.res.AssetManager::class.java, cls("OnlineRecognizerConfig")).newInstance(null, config)
        val loadMs = elapsedMs(load)
        try {
            val stream = rc.getDeclaredMethod("createStream", String::class.java).invoke(recognizer, "")!!
            val sc = stream.javaClass
            val accept = sc.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            val finish = sc.getDeclaredMethod("inputFinished")
            val release = sc.getDeclaredMethod("release")
            val ready = rc.getDeclaredMethod("isReady", sc)
            val decode = rc.getDeclaredMethod("decode", sc)
            val result = rc.getDeclaredMethod("getResult", sc)
            val getText = result.invoke(recognizer, stream).javaClass.getDeclaredMethod("getText")
            val baseline = pssMiB()
            val firstInput = SystemClock.elapsedRealtimeNanos()
            var firstPartial: Double? = null
            var compute = 0L
            var offset = 0
            try {
                while (offset < clip.samples.size) {
                    val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                    sleepUntil(firstInput + offset.toLong() * 1_000_000_000L / RATE)
                    var start = SystemClock.elapsedRealtimeNanos()
                    accept.invoke(stream, clip.samples.copyOfRange(offset, end), RATE)
                    compute += SystemClock.elapsedRealtimeNanos() - start
                    while (ready.invoke(recognizer, stream) as Boolean) {
                        start = SystemClock.elapsedRealtimeNanos(); decode.invoke(recognizer, stream); compute += SystemClock.elapsedRealtimeNanos() - start
                        if (firstPartial == null && getText.invoke(result.invoke(recognizer, stream)).toString().isNotBlank()) firstPartial = (SystemClock.elapsedRealtimeNanos() - firstInput) / 1e6
                    }
                    offset = end
                }
                val clipEnd = SystemClock.elapsedRealtimeNanos()
                finish.invoke(stream)
                while (ready.invoke(recognizer, stream) as Boolean) { val start = SystemClock.elapsedRealtimeNanos(); decode.invoke(recognizer, stream); compute += SystemClock.elapsedRealtimeNanos() - start }
                val transcript = getText.invoke(result.invoke(recognizer, stream)).toString()
                val ms = compute / 1e6
                return row(clip, "zipformer", "shipping", transcript, loadMs, pssMiB() - baseline, ms, clip.seconds / (ms / 1000), firstPartial, null, (SystemClock.elapsedRealtimeNanos() - clipEnd) / 1e6, (clipEnd - firstInput) / 1e6)
            } finally { release.invoke(stream) }
        } finally { runCatching { rc.getDeclaredMethod("release").invoke(recognizer) } }
    }

    private fun row(c: Clip, model: String, tier: String, text: String, load: Double, pss: Double, compute: Double, rtfx: Double, partial: Double?, offset: Double?, finalMs: Double, paced: Double) = JSONObject()
        .put("fixture_id", c.id).put("fixture_sha256", sha(c.bytes)).put("model", model).put("tier", tier)
        .put("model_load_ms", load).put("resident_pss_delta_mib", pss).put("audio_seconds", c.seconds)
        .put("compute_ms", compute).put("rtfx_compute_only", rtfx).put("paced_delivery_ms", paced)
        .put("first_partial_ms_from_first_sample", partial ?: JSONObject.NULL).put("first_partial_audio_offset_seconds", offset ?: JSONObject.NULL)
        .put("final_after_injected_clip_end_ms_including_flush", finalMs).put("transcript", text).put("reference_text", c.ref)
        .put("wer_edits", Asr4allMeasurementMetrics.wer(c.ref, text).edits).put("wer_reference_words", Asr4allMeasurementMetrics.wer(c.ref, text).referenceWords)

    private fun loadSet(dir: File, refsFile: File, expected: Int?): List<Clip> {
        val manifest = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("fixtures")
        val refsRoot = JSONObject(refsFile.readText())
        require(refsRoot.getInt("schema_version") == 1)
        val refs = linkedMapOf<String, String>()
        for (index in 0 until refsRoot.getJSONArray("phrases").length()) { val p = refsRoot.getJSONArray("phrases").getJSONObject(index); require(refs.put(p.getString("id"), p.getString("text")) == null) { "duplicate reference id" } }
        val ids = linkedSetOf<String>(); val clips = ArrayList<Clip>()
        for (index in 0 until manifest.length()) {
            val e = manifest.getJSONObject(index); val id = e.getString("fixture_id"); require(ids.add(id)) { "duplicate fixture id" }
            val file = File(dir, e.getString("file_name")); require(file.canonicalFile.parentFile == dir.canonicalFile) { "fixture path escapes directory" }
            val bytes = file.readBytes(); require(sha(bytes) == e.getString("sha256")) { "fixture hash mismatch: $id" }
            val pcm = wav(bytes); require(pcm.rate == RATE) { "fixture must be 16kHz: $id" }
            require(e.getInt("duration_ms") == pcm.samples.size * 1000 / RATE) { "duration mismatch: $id" }
            clips += Clip(id, refs[id] ?: error("missing phrase reference: $id"), bytes, pcm.samples, pcm.samples.size / RATE.toDouble())
        }
        require(ids == refs.keys) { "phrase and fixture IDs differ" }
        expected?.let { require(ids.size == it) { "expected exactly $it fixture IDs" } }
        return clips
    }

    private fun aggregate(rows: JSONArray): JSONObject {
        val sum = Asr4allMeasurementMetrics.aggregate((0 until rows.length()).map { i -> val r = rows.getJSONObject(i); Asr4allMeasurementMetrics.Wer(r.getInt("wer_edits"), r.getInt("wer_reference_words")) })
        return JSONObject().put("edits", sum.edits).put("reference_words", sum.referenceWords).put("wer", sum.rate)
    }
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
