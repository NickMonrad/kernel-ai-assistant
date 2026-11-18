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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** Opt-in private-fixture instrumentation. This never records audio or dispatches app commands. */
class Asr4allTierMeasurementDeviceTest {
    private data class Clip(
        val id: String,
        val reference: String,
        val bytes: ByteArray,
        val samples: FloatArray,
        val seconds: Double,
    )

    private data class FixtureSet(
        val role: String,
        val model: String?,
        val phraseDigest: String,
        val manifestDigest: String,
        val phraseIds: Set<String>,
        val manifestCount: Int,
        val phraseCount: Int,
        val clips: List<Clip>,
        val complete: Boolean,
    )

    private data class PssSnapshot(val medianMiB: Double, val noiseRangeMiB: Double)

    @Test
    fun measureOwnerAndSyntheticSets() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Measurement is opt-in", args.getString("measure_enabled") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ownerDir = privatePath(context.filesDir, arg(args, "owner_dir"))
        val ownerRefs = privatePath(context.filesDir, arg(args, "owner_phrases"))
        val ownerPhraseDigest = digestArg(args, "owner_phrases_sha256")
        val resultRoot = privatePath(context.filesDir, RESULT_ROOT)
        val syntheticDirArg = args.getString("synthetic_dir")
        val syntheticRefsArg = args.getString("synthetic_phrases")
        val syntheticDigestArg = args.getString("synthetic_phrases_sha256")
        require((syntheticDirArg == null) == (syntheticRefsArg == null)) { "Synthetic input arguments must be paired" }
        require((syntheticDirArg == null) == (syntheticDigestArg == null)) { "Synthetic phrase digest must accompany synthetic inputs" }
        val syntheticDir = syntheticDirArg?.let { privatePath(context.filesDir, it) }
        val syntheticRefs = syntheticRefsArg?.let { privatePath(context.filesDir, it) }
        validateNoOutputOverlap(resultRoot, listOf(ownerDir, ownerRefs, syntheticDir, syntheticRefs).filterNotNull())

        val owner = loadSet(ownerDir, ownerRefs, ownerPhraseDigest, OWNER_SET, OWNER_COUNT)
        require(owner.complete) { "Owner decision set is incomplete or mismatched" }
        val synthetic = if (syntheticDir == null) null else {
            require(syntheticDigestArg == BREADTH_PHRASE_SHA256) { "Breadth phrase digest is not the pinned trusted list" }
            loadSet(
                syntheticDir, syntheticRefs!!, digestArg(args, "synthetic_phrases_sha256"),
                BREADTH_SET, BREADTH_COUNT,
            )
        }
        val breadthComplete = synthetic?.complete == true &&
            synthetic.phraseIds == BREADTH_IDS && synthetic.clips.map { it.id }.toSet() == BREADTH_IDS
        val modelFiles = modelBundleIdentities(context)
        val lanes = buildList {
            for (size in Size.entries) for (tier in TIERS) add(Asr4allMeasurementMetrics.Lane(size.name, tier.removePrefix("stream_")))
            add(Asr4allMeasurementMetrics.Lane("zipformer", "shipping"))
        }
        val rows = JSONArray()
        val metricRows = ArrayList<Asr4allMeasurementMetrics.Row>()
        val sets = listOfNotNull(owner to OWNER_SET, synthetic?.takeIf { breadthComplete }?.let { it to BREADTH_SET })

        for ((fixtureSet, setRole) in sets) {
            for ((clipIndex, clip) in fixtureSet.clips.withIndex()) {
                val laneOrder = Asr4allMeasurementMetrics.counterbalanced(lanes, clipIndex)
                for ((laneIndex, lane) in laneOrder.withIndex()) {
                    val row = try {
                        if (lane.model == ZIPFORMER) {
                            measureZipformer(context, clip, setRole, lane, laneIndex, includeWer = setRole == OWNER_SET)
                        } else {
                            measureAsr4all(context, clip, setRole, lane, laneIndex, includeWer = setRole == OWNER_SET)
                        }
                    } catch (_: Exception) {
                        failedRow(clip.id, setRole, lane, laneIndex)
                    }
                    rows.put(row)
                    metricRows += metricRow(row)
                }
            }
        }

        val summaries = Asr4allMeasurementMetrics.summarize(metricRows)
        val ownerSummary = summaries.filter { it.set == OWNER_SET }
        val breadthSummary = summaries.filter { it.set == BREADTH_SET }
        val runId = UUID.randomUUID().toString()
        val result = JSONObject()
            .put("schema", "asr4all-device-measurement-v3")
            .put("run_id", runId)
            .put("device", JSONObject()
                .put("model", android.os.Build.MODEL)
                .put("build", android.os.Build.DISPLAY)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("package", context.packageName))
                .put("app_git_sha", BuildConfig.GIT_SHA)
            .put("measurement_semantics", JSONObject()
                .put("feed", "100ms chunks paced to scheduled chunk end; actual first-feed delay and per-chunk delivery lag are recorded")
                .put("partial", "first changed nonempty partial after a complete per-chunk drain and one result read; offset is delivered chunk end")
                .put("zipformer_endpoint", "decoder endpoint observed after chunk drain; not independently validated acoustic speech end")
                .put("forced_flush", "forced file-end flush; WAV boundary is not acoustic speech end")
                .put("compute_rtfx", "decode, drain, result rendering, PCEC and flush compute; scheduled pacing waits excluded")
                .put("paced_rtfx", "separate full wall-time throughput from scheduled clip start through final result")
                .put("model_open", "recognizer construction to ready; backend native-library setup is separate; no process restart between lanes")
                .put("wer", "raw-decoder WER: Unicode NFC, Locale.ROOT lowercase, apostrophes removed, other punctuation separates tokens; no production transcript correction"))
            .put("identity", JSONObject()
                .put("owner_role", owner.role)
                .put("owner_phrase_sha256", owner.phraseDigest)
                .put("owner_manifest_sha256", owner.manifestDigest)
                .put("owner_manifest_fixture_count", owner.manifestCount)
                .put("owner_phrase_count", owner.phraseCount)
                .put("breadth_status", when {
                    synthetic == null -> "not_run_inputs_absent"
                    !breadthComplete -> "not_run_incomplete_or_ids_mismatch"
                    else -> "measured_regression_latency_only"
                })
                .put("breadth_role", synthetic?.role ?: JSONObject.NULL)
                .put("breadth_model", synthetic?.model ?: JSONObject.NULL)
                .put("breadth_phrase_sha256", synthetic?.phraseDigest ?: JSONObject.NULL)
                .put("breadth_phrase_source_commit", if (synthetic == null) JSONObject.NULL else BREADTH_PHRASE_SOURCE_COMMIT)
                .put("breadth_manifest_sha256", synthetic?.manifestDigest ?: JSONObject.NULL)
                .put("breadth_manifest_fixture_count", synthetic?.manifestCount ?: JSONObject.NULL)
                .put("breadth_phrase_count", synthetic?.phraseCount ?: JSONObject.NULL)
                .put("model_file_sha256", modelFiles))
            .put("owner_decision", JSONObject()
                .put("status", "measured_decision_set_only")
                .put("expected_rows_per_lane", OWNER_COUNT)
                .put("coverage_by_lane", summariesJson(ownerSummary, OWNER_COUNT, lanes, includeWer = true)))
            .put("synthetic_breadth", JSONObject()
                .put("status", when {
                    synthetic == null -> "not_run_inputs_absent"
                    !breadthComplete -> "not_run_incomplete_or_ids_mismatch"
                    else -> "measured_regression_latency_only"
                })
                .put("expected_rows_per_lane", BREADTH_COUNT)
                .put("coverage_by_lane", summariesJson(breadthSummary, BREADTH_COUNT, lanes, includeWer = false)))
            .put("rows", rows)
        atomicReport(context.filesDir, resultRoot, runId, result.toString(2) + "\n")
        Log.i(TAG, "ASR4ALL_MEASUREMENT_COMPLETE owner_rows=${ownerSummary.sumOf { it.rows }} breadth_rows=${breadthSummary.sumOf { it.rows }}")
    }

    private fun measureAsr4all(
        context: android.content.Context,
        clip: Clip,
        setRole: String,
        lane: Asr4allMeasurementMetrics.Lane,
        laneOrder: Int,
        includeWer: Boolean,
    ): JSONObject {
        val size = Size.valueOf(lane.model)
        val modelDir = Asr4allModelFiles.releaseDir(context, size)
        val before = pssSnapshot()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        Asr4allEngine.open(modelDir, "stream_${lane.tier}").use { engine ->
            val openMs = elapsedMs(loadStart)
            val afterLoad = pssSnapshot()
            warmAsr4all(engine, clip.samples)
            val afterWarmup = pssSnapshot()
            val audioStart = SystemClock.elapsedRealtimeNanos()
            var firstFeedDelayMs: Double? = null
            var firstPartialMs: Double? = null
            var firstPartialOffset: Double? = null
            var computeNanos = 0L
            var pacingMaxMs = 0.0
            var pacingSumMs = 0.0
            var pacingCount = 0
            var lastCommittedFrames = 0
            var previousPartial = ""
            var offset = 0
            while (offset < clip.samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                val scheduledEnd = audioStart + end.toLong() * NANOS_PER_SECOND / RATE
                sleepUntil(scheduledEnd)
                val feedAt = SystemClock.elapsedRealtimeNanos()
                if (firstFeedDelayMs == null) firstFeedDelayMs = (feedAt - audioStart) / 1e6
                val lag = ((feedAt - scheduledEnd).coerceAtLeast(0)) / 1e6
                pacingMaxMs = maxOf(pacingMaxMs, lag)
                pacingSumMs += lag
                pacingCount++
                val start = SystemClock.elapsedRealtimeNanos()
                engine.decodeStream(clip.samples.copyOfRange(offset, end))
                val committed = engine.decodedFrameCount
                if (committed != lastCommittedFrames) {
                    lastCommittedFrames = committed
                    val partial = engine.decodePcec(decodeTail = true).trim()
                    if (partial.isNotEmpty() && partial != previousPartial) {
                        previousPartial = partial
                        if (firstPartialMs == null) {
                            firstPartialMs = (SystemClock.elapsedRealtimeNanos() - audioStart) / 1e6
                            firstPartialOffset = end.toDouble() / RATE
                        }
                    }
                }
                computeNanos += SystemClock.elapsedRealtimeNanos() - start
                offset = end
            }
            val scheduledClipEnd = audioStart + clip.samples.size.toLong() * NANOS_PER_SECOND / RATE
            val flushStart = SystemClock.elapsedRealtimeNanos()
            engine.finish()
            val transcript = engine.decodePcec(decodeTail = true).trim()
            computeNanos += SystemClock.elapsedRealtimeNanos() - flushStart
            val completedAt = SystemClock.elapsedRealtimeNanos()
            return measurementRow(
                clip, setRole, lane, laneOrder, transcript,
                outcome = if (transcript.isBlank()) OUTCOME_NO_FINAL else OUTCOME_FORCED_FINAL,
                openMs = openMs, pss = pssFields(before, afterLoad, afterWarmup),
                computeMs = computeNanos / 1e6,
                pacedWallMs = (completedAt - audioStart) / 1e6,
                firstFeedDelayMs = firstFeedDelayMs,
                pacingMaxMs = pacingMaxMs,
                pacingMeanMs = if (pacingCount == 0) null else pacingSumMs / pacingCount,
                firstPartialMs = firstPartialMs, firstPartialOffset = firstPartialOffset,
                finalLatencyMs = if (transcript.isBlank()) null else (completedAt - scheduledClipEnd) / 1e6,
                endpointLatencyMs = null, deliveredSeconds = clip.seconds,
                includeWer = includeWer,
            )
        }
    }

    /** Mirrors controller cadence: feed, drain all ready chunks, check endpoint, then read once. */
    private fun measureZipformer(
        context: android.content.Context,
        clip: Clip,
        setRole: String,
        lane: Asr4allMeasurementMetrics.Lane,
        laneOrder: Int,
        includeWer: Boolean,
    ): JSONObject {
        val modelDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val prefix = "com.k2fsa.sherpa.onnx."
        fun cls(name: String) = Class.forName(prefix + name)
        val nativeSetupStart = SystemClock.elapsedRealtimeNanos()
        try {
            System.loadLibrary("onnxruntime")
        } catch (_: UnsatisfiedLinkError) {
            // Match production: tolerate already-loaded ORT and let Sherpa class loading report failures.
        }
        val onlineRecognizerClass = cls("OnlineRecognizer")
        val nativeSetupMs = elapsedMs(nativeSetupStart)
        fun set(target: Any, name: String, value: Any) {
            val type = when (value) {
                is Int -> Int::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> value.javaClass
            }
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            runCatching { target.javaClass.getDeclaredMethod(setter, type).also { it.isAccessible = true }.invoke(target, value) }
                .recoverCatching { target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value) }
                .getOrThrow()
        }
        fun file(name: String) = File(modelDir, name).canonicalFile.absolutePath.also { require(File(it).isFile) { "Zipformer model bundle unavailable" } }
        val feat = cls("FeatureConfig").getDeclaredConstructor().newInstance().also {
            set(it, "sampleRate", RATE); set(it, "featureDim", 80)
        }
        val transducer = cls("OnlineTransducerModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "encoder", file("sherpa-stt-encoder.int8.onnx"))
            set(it, "decoder", file("sherpa-stt-decoder.int8.onnx"))
            set(it, "joiner", file("sherpa-stt-joiner.int8.onnx"))
        }
        val model = cls("OnlineModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "transducer", transducer); set(it, "tokens", file("sherpa-stt-tokens.txt"))
            set(it, "numThreads", 2); set(it, "debug", false); set(it, "provider", "cpu")
        }
        val config = cls("OnlineRecognizerConfig").getDeclaredConstructor().newInstance().also {
            set(it, "featConfig", feat); set(it, "modelConfig", model)
            set(it, "endpointConfig", cls("EndpointConfig").getDeclaredConstructor().newInstance())
            set(it, "enableEndpoint", true); set(it, "decodingMethod", "greedy_search"); set(it, "maxActivePaths", 4)
        }
        val recognizerClass = onlineRecognizerClass
        val before = pssSnapshot()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        val recognizer = recognizerClass.getConstructor(
            android.content.res.AssetManager::class.java, cls("OnlineRecognizerConfig"),
        ).newInstance(null, config)
        val openMs = elapsedMs(loadStart)
        try {
            val afterLoad = pssSnapshot()
        val createStream = recognizerClass.getDeclaredMethod("createStream", String::class.java)
        val warmStream = createStream.invoke(recognizer, "")!!
        val streamClass = warmStream.javaClass
        val accept = streamClass.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
        val finish = streamClass.getDeclaredMethod("inputFinished")
        val releaseStream = streamClass.getDeclaredMethod("release")
        val ready = recognizerClass.getDeclaredMethod("isReady", streamClass)
        val decode = recognizerClass.getDeclaredMethod("decode", streamClass)
        val endpoint = recognizerClass.getDeclaredMethod("isEndpoint", streamClass)
        val reset = recognizerClass.getDeclaredMethod("reset", streamClass)
        val result = recognizerClass.getDeclaredMethod("getResult", streamClass)
        val resultTextMethod = result.invoke(recognizer, warmStream).javaClass.getDeclaredMethod("getText")
        fun readText(stream: Any): String = resultTextMethod.invoke(result.invoke(recognizer, stream)).toString().trim()
        try {
            accept.invoke(warmStream, clip.samples, RATE)
            while (ready.invoke(recognizer, warmStream) as Boolean) decode.invoke(recognizer, warmStream)
            readText(warmStream)
            finish.invoke(warmStream)
            while (ready.invoke(recognizer, warmStream) as Boolean) decode.invoke(recognizer, warmStream)
            readText(warmStream)
        } finally {
            releaseStream.invoke(warmStream)
        }
        val afterWarmup = pssSnapshot()
        val stream = createStream.invoke(recognizer, "")!!
        try {
            val audioStart = SystemClock.elapsedRealtimeNanos()
            var firstFeedDelayMs: Double? = null
            var firstPartialMs: Double? = null
            var firstPartialOffset: Double? = null
            var computeNanos = 0L
            var pacingMaxMs = 0.0
            var pacingSumMs = 0.0
            var pacingCount = 0
            var previousPartial = ""
            var endpointObservedAt: Long? = null
            var firstFeed = true
            var offset = 0
            var transcript = ""
            var deliveredSamples = 0
            while (offset < clip.samples.size) {
                val end = minOf(offset + CHUNK_SAMPLES, clip.samples.size)
                val scheduledEnd = audioStart + end.toLong() * NANOS_PER_SECOND / RATE
                sleepUntil(scheduledEnd)
                val feedAt = SystemClock.elapsedRealtimeNanos()
                if (firstFeed) {
                    firstFeed = false
                    firstFeedDelayMs = (feedAt - audioStart) / 1e6
                }
                val lag = ((feedAt - scheduledEnd).coerceAtLeast(0)) / 1e6
                pacingMaxMs = maxOf(pacingMaxMs, lag)
                pacingSumMs += lag
                pacingCount++
                val start = SystemClock.elapsedRealtimeNanos()
                accept.invoke(stream, clip.samples.copyOfRange(offset, end), RATE)
                while (ready.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
                deliveredSamples = end
                if (endpoint.invoke(recognizer, stream) as Boolean) {
                    endpointObservedAt = SystemClock.elapsedRealtimeNanos()
                    finish.invoke(stream)
                    while (ready.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
                    transcript = readText(stream)
                    if (transcript.isNotBlank()) {
                        computeNanos += SystemClock.elapsedRealtimeNanos() - start
                        offset = end
                        break
                    }
                    reset.invoke(recognizer, stream)
                } else {
                    val partial = readText(stream)
                    if (partial.isNotBlank() && partial != previousPartial) {
                        previousPartial = partial
                        if (firstPartialMs == null) {
                            firstPartialMs = (SystemClock.elapsedRealtimeNanos() - audioStart) / 1e6
                            firstPartialOffset = end.toDouble() / RATE
                        }
                    }
                }
                computeNanos += SystemClock.elapsedRealtimeNanos() - start
                offset = end
            }
            val scheduledClipEnd = audioStart + clip.samples.size.toLong() * NANOS_PER_SECOND / RATE
            var forcedFlush = endpointObservedAt == null || transcript.isBlank()
            var endpointLatencyMs: Double? = null
            val flushStart = SystemClock.elapsedRealtimeNanos()
            if (forcedFlush) {
                finish.invoke(stream)
                while (ready.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
                transcript = readText(stream)
            }
            computeNanos += SystemClock.elapsedRealtimeNanos() - flushStart
            val completedAt = SystemClock.elapsedRealtimeNanos()
            val outcome = if (transcript.isBlank()) OUTCOME_NO_FINAL else if (forcedFlush) {
                OUTCOME_FORCED_FINAL
            } else {
                endpointLatencyMs = (completedAt - (endpointObservedAt ?: completedAt)) / 1e6
                OUTCOME_ENDPOINT_FINAL
            }
            return measurementRow(
                clip, setRole, lane, laneOrder, transcript, outcome,
                openMs = openMs, pss = pssFields(before, afterLoad, afterWarmup),
                computeMs = computeNanos / 1e6,
                pacedWallMs = (completedAt - audioStart) / 1e6,
                firstFeedDelayMs = firstFeedDelayMs,
                pacingMaxMs = pacingMaxMs,
                pacingMeanMs = if (pacingCount == 0) null else pacingSumMs / pacingCount,
                firstPartialMs = firstPartialMs, firstPartialOffset = firstPartialOffset,
                finalLatencyMs = if (outcome == OUTCOME_FORCED_FINAL) (completedAt - scheduledClipEnd) / 1e6 else null,
                endpointLatencyMs = endpointLatencyMs,
                deliveredSeconds = deliveredSamples.toDouble() / RATE,
                includeWer = includeWer,
                backendNativeSetupMs = nativeSetupMs,
            )
        } finally {
            releaseStream.invoke(stream)
        }
        } finally {
            recognizerClass.getDeclaredMethod("release").invoke(recognizer)
        }
    }

    private fun warmAsr4all(engine: Asr4allEngine, samples: FloatArray) {
        var offset = 0
        var lastCommittedFrames = 0
        while (offset < samples.size) {
            val end = minOf(offset + CHUNK_SAMPLES, samples.size)
            engine.decodeStream(samples.copyOfRange(offset, end))
            val committed = engine.decodedFrameCount
            if (committed != lastCommittedFrames) {
                lastCommittedFrames = committed
                engine.decodePcec(decodeTail = true)
            }
            offset = end
        }
        engine.finish()
        engine.decodePcec(decodeTail = true)
        engine.reset()
    }

    private fun measurementRow(
        clip: Clip,
        setRole: String,
        lane: Asr4allMeasurementMetrics.Lane,
        laneOrder: Int,
        transcript: String,
        outcome: String,
        openMs: Double,
        pss: JSONObject,
        computeMs: Double,
        pacedWallMs: Double,
        firstFeedDelayMs: Double?,
        pacingMaxMs: Double,
        pacingMeanMs: Double?,
        firstPartialMs: Double?,
        firstPartialOffset: Double?,
        finalLatencyMs: Double?,
        endpointLatencyMs: Double?,
        deliveredSeconds: Double,
        includeWer: Boolean,
        backendNativeSetupMs: Double? = null,
    ): JSONObject {
        val wer = if (includeWer) Asr4allMeasurementMetrics.wer(clip.reference, transcript) else null
        return JSONObject()
            .put("set", setRole).put("fixture_id", clip.id).put("fixture_sha256", sha(clip.bytes))
            .put("model", lane.model).put("tier", lane.tier).put("counterbalanced_lane_order", laneOrder)
            .put("outcome", outcome).put("final_present", transcript.isNotBlank())
            .put("recognizer_open_ms", openMs).put("pss", pss)
            .put("backend_native_setup_ms", backendNativeSetupMs ?: JSONObject.NULL)
            .put("audio_seconds_scheduled", clip.seconds)
            .put("audio_seconds_delivered", deliveredSeconds)
            .put("compute_ms", computeMs)
            .put("rtfx_compute_only", deliveredSeconds / (computeMs / 1000.0))
            .put("paced_wall_elapsed_ms_from_scheduled_clip_start", pacedWallMs)
            .put("rtfx_paced", deliveredSeconds / (pacedWallMs / 1000.0))
            .put("first_feed_elapsed_ms_from_scheduled_clip_start", firstFeedDelayMs ?: JSONObject.NULL)
            .put("maximum_chunk_pacing_lag_ms", pacingMaxMs)
            .put("mean_chunk_pacing_lag_ms", pacingMeanMs ?: JSONObject.NULL)
            .put("first_partial_latency_ms_from_scheduled_clip_start", firstPartialMs ?: JSONObject.NULL)
            .put("first_partial_audio_offset_seconds", firstPartialOffset ?: JSONObject.NULL)
            .put("forced_clip_flush_latency_ms", finalLatencyMs ?: JSONObject.NULL)
            .put("observed_decoder_endpoint_to_final_ms", endpointLatencyMs ?: JSONObject.NULL)
            .apply {
                if (includeWer) {
                    put("raw_decoder_transcript", transcript)
                    put("reference_text", clip.reference)
                    put("raw_decoder_wer_edits", wer!!.edits)
                    put("raw_decoder_wer_reference_words", wer.referenceWords)
                }
            }
    }

    private fun failedRow(
        id: String,
        set: String,
        lane: Asr4allMeasurementMetrics.Lane,
        order: Int,
    ): JSONObject = JSONObject()
        .put("set", set).put("fixture_id", id).put("model", lane.model).put("tier", lane.tier)
        .put("counterbalanced_lane_order", order).put("outcome", OUTCOME_ERROR)
        .put("final_present", false).put("error", "inference_error")
        .put("forced_clip_flush_latency_ms", JSONObject.NULL)
        .put("observed_decoder_endpoint_to_final_ms", JSONObject.NULL)

    private fun metricRow(row: JSONObject): Asr4allMeasurementMetrics.Row {
        val wer = if (row.has("raw_decoder_wer_edits")) {
            Asr4allMeasurementMetrics.Wer(
                row.getInt("raw_decoder_wer_edits"),
                row.getInt("raw_decoder_wer_reference_words"),
            )
        } else null
        val outcome = row.getString("outcome")
        val clipEndLatency = if (outcome == OUTCOME_FORCED_FINAL && row.opt("forced_clip_flush_latency_ms") is Number) {
            row.getDouble("forced_clip_flush_latency_ms")
        } else null
        val endpointLatency = if (outcome == OUTCOME_ENDPOINT_FINAL && row.opt("observed_decoder_endpoint_to_final_ms") is Number) {
            row.getDouble("observed_decoder_endpoint_to_final_ms")
        } else null
        return Asr4allMeasurementMetrics.Row(
            row.getString("set"), row.getString("model"), row.getString("tier"),
            outcome, wer, clipEndLatency, endpointLatency,
        )
    }

    private fun summariesJson(
        summaries: List<Asr4allMeasurementMetrics.Summary>,
        expectedRows: Int,
        lanes: List<Asr4allMeasurementMetrics.Lane>,
        includeWer: Boolean,
    ): JSONArray = JSONArray().apply {
        val byLane = summaries.associateBy { it.model to it.tier }
        for (lane in lanes) {
            val s = byLane[lane.model to lane.tier]
            val rows = s?.rows ?: 0
            val item = JSONObject()
                .put("model", lane.model).put("tier", lane.tier).put("rows", rows)
                .put("expected_rows", expectedRows)
                .put("coverage_complete", rows == expectedRows)
                .put("missing_rows", (expectedRows - rows).coerceAtLeast(0))
                .put("status", if (s == null) "not_run" else "measured")
                .put("finals", s?.finals ?: 0).put("no_final", s?.noFinal ?: 0)
                .put("errors", s?.errors ?: 0).put("wer_scored_rows", s?.werCoverage ?: 0)
                .put("forced_clip_end_latency_count", s?.clipEndLatencyCount ?: 0)
                .put("mean_valid_forced_clip_end_latency_ms", s?.meanClipEndLatencyMs ?: JSONObject.NULL)
                .put("decoder_endpoint_to_final_count", s?.endpointLatencyCount ?: 0)
                .put("mean_observed_decoder_endpoint_to_final_ms", s?.meanEndpointLatencyMs ?: JSONObject.NULL)
            if (includeWer && s?.wer != null) {
                item.put("raw_decoder_wer", JSONObject()
                    .put("edits", s.wer.edits)
                    .put("reference_words", s.wer.referenceWords)
                    .put("rate", s.wer.rate))
            }
            put(item)
        }
    }

    private fun loadSet(
        dir: File,
        refsFile: File,
        trustedPhraseDigest: String,
        expectedRole: String,
        expectedCount: Int,
    ): FixtureSet {
        val manifestFile = File(dir, "manifest.json").canonicalFile
        require(manifestFile.parentFile == dir.canonicalFile) { "Manifest must be under the staged set root" }
        val manifestBytes = readPrivate(manifestFile)
        val manifestRoot = parsePrivateJson(manifestBytes)
        val refsBytes = readPrivate(refsFile)
        val refsRoot = parsePrivateJson(refsBytes)
        validateSetEnvelope(manifestRoot, expectedRole)
        validateSetEnvelope(refsRoot, expectedRole)
        val phraseDigest = sha(refsBytes)
        require(phraseDigest == trustedPhraseDigest) { "Staged phrase file does not match the trusted digest" }
        val manifest = manifestRoot.getJSONArray("fixtures")
        val refs = linkedMapOf<String, String>()
        val phraseArray = refsRoot.getJSONArray("phrases")
        for (index in 0 until phraseArray.length()) {
            val phrase = phraseArray.getJSONObject(index)
            val id = phrase.getString("id")
            val text = phrase.getString("text")
            require(id.isNotBlank() && text.isNotBlank()) { "Phrase ID and reference must be nonempty" }
            require(refs.put(id, text) == null) { "Duplicate phrase ID" }
        }
        val manifestIds = linkedSetOf<String>()
        for (index in 0 until manifest.length()) {
            val fixture = manifest.getJSONObject(index)
            require(fixture.getBoolean("synthetic") == (expectedRole == BREADTH_SET)) { "Fixture role mismatch" }
            val id = fixture.getString("fixture_id")
            require(id.isNotBlank() && manifestIds.add(id)) { "Duplicate or empty fixture ID" }
        }
        val completeIds = when (expectedRole) {
            OWNER_SET -> manifestIds.size == expectedCount && refs.size == expectedCount && manifestIds == refs.keys
            else -> manifestIds == BREADTH_IDS && refs.keys == BREADTH_IDS
        }
        if (expectedRole == OWNER_SET) require(completeIds) { "Owner phrase and fixture IDs must be exactly one-to-one" }
        val manifestDigest = sha(manifestBytes)
        if (!completeIds) {
            return FixtureSet(
                expectedRole, manifestRoot.optString("model").takeIf { it.isNotEmpty() },
                phraseDigest, manifestDigest, refs.keys, manifest.length(), refs.size, emptyList(), false,
            )
        }
        val ids = linkedSetOf<String>()
        val paths = linkedSetOf<String>()
        val clips = ArrayList<Clip>(manifest.length())
        for (index in 0 until manifest.length()) {
            val fixture = manifest.getJSONObject(index)
            val id = fixture.getString("fixture_id")
            ids += id
            val pcmMeta = fixture.getJSONObject("formats").getJSONObject("16k")
            require(pcmMeta.getInt("sample_rate_hz") == RATE && pcmMeta.getInt("channels") == 1) { "Fixture PCM metadata mismatch" }
            require(pcmMeta.getString("encoding") == "PCM_16") { "Fixture encoding mismatch" }
            val file = File(dir, pcmMeta.getString("file_name")).canonicalFile
            require(file.toPath().startsWith(dir.canonicalFile.toPath()) && file != dir.canonicalFile) { "Fixture path escapes staged set" }
            require(paths.add(file.path)) { "Fixture paths must be unique" }
            val bytes = readPrivate(file)
            require(sha(bytes) == pcmMeta.getString("sha256")) { "Fixture hash mismatch" }
            val pcm = wav(bytes)
            require(pcm.rate == RATE && pcm.channels == 1 && pcm.samples.isNotEmpty()) { "Fixture must be nonempty mono 16kHz PCM" }
            val duration = (pcm.samples.size * 1000.0 / RATE).roundToInt()
            require(pcmMeta.getInt("duration_ms") == duration) { "Fixture duration mismatch" }
            clips += Clip(id, refs.getValue(id), bytes, pcm.samples, pcm.samples.size / RATE.toDouble())
        }
        require(ids == refs.keys) { "Phrase and fixture IDs differ" }
        return FixtureSet(
            expectedRole, manifestRoot.optString("model").takeIf { it.isNotEmpty() },
            phraseDigest, manifestDigest, refs.keys, manifest.length(), refs.size, clips,
            complete = clips.size == expectedCount,
        )
    }

    private fun validateSetEnvelope(root: JSONObject, expectedRole: String) {
        require(root.getInt("schema_version") == 1) { "Unsupported fixture schema" }
        val synthetic = expectedRole == BREADTH_SET
        require(root.getString("set") == expectedRole) { "Fixture set role mismatch" }
        require(root.getBoolean("synthetic") == synthetic) { "Fixture synthetic role mismatch" }
        require(root.getBoolean("decision_set") == !synthetic) { "Fixture decision-set role mismatch" }
    }

    private fun modelBundleIdentities(context: android.content.Context): JSONObject {
        val result = JSONObject()
        for (size in Size.entries) {
            val directory = Asr4allModelFiles.releaseDir(context, size)
            val names = listOf(
                "metadata.json", "stream_c16r4.onnx", "stream_c32r8.onnx",
                "pcec_c16.onnx", "pcec_c16_flush.onnx", "asr4all.onnx.data",
            )
            val sizeFiles = JSONObject()
            for (name in names) sizeFiles.put(name, shaFile(File(directory, name).canonicalFile))
            result.put("asr4all_${size.name.lowercase()}", sizeFiles)
        }
        val zipDir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val zipNames = listOf(
            "sherpa-stt-encoder.int8.onnx", "sherpa-stt-decoder.int8.onnx",
            "sherpa-stt-joiner.int8.onnx", "sherpa-stt-tokens.txt",
        )
        val zipFiles = JSONObject()
        for (name in zipNames) zipFiles.put(name, shaFile(File(zipDir, name).canonicalFile))
        result.put(ZIPFORMER, zipFiles)
        return result
    }

    private fun pssSnapshot(): PssSnapshot {
        val readings = DoubleArray(3) {
            val value = pssMiB()
            if (it < 2) SystemClock.sleep(40)
            value
        }.sorted()
        return PssSnapshot(readings[1], readings.last() - readings.first())
    }

    private fun pssFields(before: PssSnapshot, afterLoad: PssSnapshot, afterWarmup: PssSnapshot) = JSONObject()
        .put("before_load_absolute_mib", before.medianMiB)
        .put("before_load_background_noise_range_mib", before.noiseRangeMiB)
        .put("after_load_absolute_mib", afterLoad.medianMiB)
        .put("after_load_background_noise_range_mib", afterLoad.noiseRangeMiB)
        .put("after_load_delta_mib", afterLoad.medianMiB - before.medianMiB)
        .put("after_warmup_resident_absolute_mib", afterWarmup.medianMiB)
        .put("after_warmup_background_noise_range_mib", afterWarmup.noiseRangeMiB)
        .put("after_warmup_resident_delta_mib", afterWarmup.medianMiB - before.medianMiB)
        .put("after_warmup_incremental_delta_mib", afterWarmup.medianMiB - afterLoad.medianMiB)

    private fun validateNoOutputOverlap(resultRoot: File, inputs: List<File>) {
        val output = resultRoot.canonicalFile.toPath()
        for (input in inputs) {
            val path = input.canonicalFile.toPath()
            require(!output.startsWith(path) && !path.startsWith(output)) { "Staged inputs overlap the result root" }
        }
    }

    private fun atomicReport(filesDir: File, root: File, runId: String, contents: String) {
        require(root.toPath().startsWith(filesDir.canonicalFile.toPath())) { "Result root is not app-private" }
        require(root.isDirectory || root.mkdirs()) { "Cannot create private result root" }
        val runDir = File(root, runId)
        require(runDir.mkdir()) { "Cannot reserve unique report directory" }
        val target = File(runDir, "measurement.json")
        val temporary = File(runDir, "measurement.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        require(temporary.renameTo(target)) { "Cannot atomically publish private report" }
    }

    private fun privatePath(filesDir: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "Measurement paths must be relative" }
        val base = filesDir.canonicalFile.toPath()
        val candidate = File(filesDir, relative).canonicalFile
        require(candidate.toPath().startsWith(base) && candidate.toPath() != base) { "Measurement path must be app-private" }
        return candidate
    }

    private fun parsePrivateJson(bytes: ByteArray): JSONObject = try {
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Throwable) {
        error("Staged measurement metadata is invalid")
    }
    private fun readPrivate(file: File): ByteArray = try {
        file.readBytes()
    } catch (_: Throwable) {
        error("Unable to read staged measurement input")
    }

    private fun shaFile(file: File): String {
        require(file.isFile) { "Required model bundle file is unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun arg(bundle: android.os.Bundle, name: String) = bundle.getString(name) ?: error("Required measurement argument missing")
    private fun digestArg(bundle: android.os.Bundle, name: String): String =
        arg(bundle, name).also { require(Regex("[0-9a-f]{64}").matches(it)) { "Trusted phrase digest must be lowercase SHA-256" } }
    private fun pssMiB() = Debug.MemoryInfo().let { Debug.getMemoryInfo(it); it.totalPss / 1024.0 }
    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    private fun sleepUntil(target: Long) {
        while (true) {
            val nanos = target - SystemClock.elapsedRealtimeNanos()
            if (nanos <= 0) return
            SystemClock.sleep((nanos / 1_000_000).coerceAtLeast(1))
        }
    }

    private data class Pcm(val rate: Int, val channels: Int, val samples: FloatArray)

    private fun wav(bytes: ByteArray): Pcm {
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Invalid fixture WAV" }
        var offset = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var encoding = 0
        var dataStart = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val chunkSize = int(bytes, offset + 4)
            require(chunkSize >= 0 && offset.toLong() + 8L + chunkSize <= bytes.size) { "Invalid fixture WAV chunk" }
            when (String(bytes, offset, 4)) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Invalid fixture WAV format chunk" }
                    encoding = short(bytes, offset + 8)
                    channels = short(bytes, offset + 10)
                    rate = int(bytes, offset + 12)
                    bits = short(bytes, offset + 22)
                }
                "data" -> {
                    dataStart = offset + 8
                    dataLength = chunkSize
                }
            }
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        require(encoding == 1 && bits == 16 && dataStart >= 0 && dataLength > 0 && dataLength % 2 == 0) {
            "Expected nonempty aligned PCM16 WAV"
        }
        return Pcm(rate, channels, FloatArray(dataLength / 2) {
            short(bytes, dataStart + it * 2).toShort().toInt() / 32768f
        })
    }

    private fun short(bytes: ByteArray, index: Int) = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
    private fun int(bytes: ByteArray, index: Int) = short(bytes, index) or (short(bytes, index + 2) shl 16)

    private companion object {
        const val TAG = "Asr4allMeasure"
        const val RATE = 16_000
        const val CHUNK_SAMPLES = 1_600
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val RESULT_ROOT = "asr4all-measurement-results"
        const val OWNER_SET = "owner-decision"
        const val BREADTH_SET = "voice-cloned-breadth"
        const val OWNER_COUNT = 20
        const val BREADTH_COUNT = 100
        const val BREADTH_PHRASE_SHA256 = "72790822ff96cc1c4e0de6d4169ac7e2afda11deb11ee06d8716ecd44fd01271"
        const val BREADTH_PHRASE_SOURCE_COMMIT = "437de7ae8c2bcb60eb4d4fd77f7bc9af3caa63be"
        const val ZIPFORMER = "zipformer"
        const val OUTCOME_ENDPOINT_FINAL = "decoder_endpoint_final"
        const val OUTCOME_FORCED_FINAL = "forced_clip_flush_final"
        const val OUTCOME_NO_FINAL = "no_final"
        const val OUTCOME_ERROR = "error"
        val TIERS = listOf("stream_c16r4", "stream_c32r8")
        val BREADTH_IDS = (1..BREADTH_COUNT).map { "CL-%03d".format(it) }.toSet()
    }
}
