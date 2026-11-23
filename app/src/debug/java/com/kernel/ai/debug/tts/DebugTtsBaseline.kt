package com.kernel.ai.debug.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import com.kernel.ai.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Evidence JSON schema. `/3` moved lane-specific runtime, voice and model fields under `lane_metadata`. */
const val DEBUG_TTS_BASELINE_SCHEMA = "debug-tts-baseline/3"

/** One baseline request, parsed from broadcast extras. Debug-only. */
data class DebugTtsBaselineRequest(
    val lane: String,
    val corpusPath: File,
    val label: String,
    val repeats: Int,
) {
    companion object {
        const val EXTRA_LANE = "lane"
        const val EXTRA_CORPUS = "corpus_path"
        const val EXTRA_LABEL = "label"
        const val EXTRA_REPEATS = "repeats"
        const val DEFAULT_REPEATS = 3
        const val MAX_REPEATS = 10

        fun from(intent: Intent): DebugTtsBaselineRequest {
            val lane = intent.getStringExtra(EXTRA_LANE)?.takeIf { it.isNotBlank() } ?: DebugTtsLanes.INFLECT_MICRO_V2
            require(lane in DebugTtsLanes.ids) { "unknown lane $lane; known: ${DebugTtsLanes.ids.sorted()}" }
            val corpus = File(requireNotNull(intent.getStringExtra(EXTRA_CORPUS)) { "missing corpus_path" })
            require(corpus.isFile) { "corpus file missing: ${corpus.path}" }
            val label = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: "run"
            require(label.length <= 64 && label.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
                "label must be <=64 chars of [A-Za-z0-9._-]"
            }
            val repeats = intent.getIntExtra(EXTRA_REPEATS, DEFAULT_REPEATS)
            require(repeats in 1..MAX_REPEATS) { "repeats must be 1..$MAX_REPEATS" }
            return DebugTtsBaselineRequest(lane, corpus, label, repeats)
        }
    }
}

/** Corpus line format: `id<TAB>text`. Blank lines and `#` comments are skipped. */
data class DebugTtsSample(val id: String, val text: String)

object DebugTtsCorpus {
    fun parse(file: File): List<DebugTtsSample> = file.readLines().mapNotNull { line ->
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
        val tab = trimmed.indexOf('\t')
        require(tab > 0) { "corpus line lacks id<TAB>text: $trimmed" }
        DebugTtsSample(trimmed.substring(0, tab), trimmed.substring(tab + 1))
    }.also { require(it.isNotEmpty()) { "corpus has no samples" } }

    fun sha256(file: File): String = sha256Of(file)
}

/**
 * Runs one lane over a corpus and returns the evidence JSON. Lane-specific runtime, voice and model identity come
 * from [DebugTtsLane.metadata]; this owns the measurement and the lane-agnostic device and build fields.
 */
class DebugTtsBaselineRun(
    private val context: Context,
    private val lane: DebugTtsLane,
) {
    fun execute(request: DebugTtsBaselineRequest): JSONObject {
        val started = Instant.now()
        val samples = DebugTtsCorpus.parse(request.corpusPath)
        val memory = JSONObject().put("vmhwm_kb_start", vmKb("VmHWM")).put("vmrss_kb_start", vmKb("VmRSS"))
        val initMs = lane.initialize()
        memory.put("vmhwm_kb_after_init", vmKb("VmHWM"))

        val rows = JSONArray()
        for (pass in 1..request.repeats) {
            for (sample in samples) {
                val firstChunk = longArrayOf(-1L)
                val utterance = lane.synthesize(sample.text) { index, elapsedMs ->
                    if (index == 0) firstChunk[0] = elapsedMs
                }
                rows.put(
                    JSONObject()
                        .put("pass", pass)
                        .put("id", sample.id)
                        .put("chars", sample.text.length)
                        .put("chunks", utterance.chunks.size)
                        .put("frontend_ms", utterance.frontendMs)
                        .put("first_chunk_ms", firstChunk[0])
                        .put("synth_ms", utterance.synthMs)
                        .put("audio_ms", round3(utterance.audioMs))
                        .put("rtf", round4(utterance.synthMs / utterance.audioMs))
                        .put("peak_abs", utterance.chunks.maxOf { it.peakAbs.toDouble() })
                        .put("finite", utterance.finite)
                        .put("chunk_synth_ms", JSONArray(utterance.chunks.map { it.synthMs })),
                )
            }
        }
        val cancel = cancelProbe(samples)
        memory.put("vmhwm_kb_end", vmKb("VmHWM")).put("vmrss_kb_end", vmKb("VmRSS"))

        return JSONObject()
            .put("schema", DEBUG_TTS_BASELINE_SCHEMA)
            .put("status", "ok")
            .put("label", request.label)
            .put("lane", lane.laneId)
            .put("backend", lane.backend)
            .put("started_utc", started.toString())
            .put("finished_utc", Instant.now().toString())
            .put("device", deviceJson())
            .put("build", buildJson())
            .put("lane_metadata", lane.metadata())
            .put("corpus", JSONObject()
                .put("path", request.corpusPath.name)
                .put("sha256", DebugTtsCorpus.sha256(request.corpusPath))
                .put("samples", samples.size)
                .put("repeats", request.repeats))
            .put("timing", JSONObject().put("runner_init_ms", initMs))
            .put("memory", memory)
            .put("rows", rows)
            .put("summary", summary(rows))
            .put("cancel_probe", cancel)
    }

    /**
     * Cancels the longest sample [CANCEL_AFTER_MS] into its synthesis from another thread. The corpus is single-chunk
     * per sample, so cancellation must land inside a synthesis (the runner checks the flag at its stage boundaries).
     * Then resets cancellation and re-synthesises the shortest sample to check recovery.
     */
    private fun cancelProbe(samples: List<DebugTtsSample>): JSONObject {
        val longest = samples.maxBy { it.text.length }
        val shortest = samples.minBy { it.text.length }
        val outcome = arrayOfNulls<Throwable>(1)
        val finishedAt = LongArray(1)
        val worker = Thread({
            try {
                lane.synthesize(longest.text) { _, _ -> }
            } catch (error: Throwable) {
                outcome[0] = error
            } finally {
                finishedAt[0] = System.nanoTime()
            }
        }, "debug-tts-cancel-probe")
        worker.start()
        Thread.sleep(CANCEL_AFTER_MS)
        val cancelRequestedAt = System.nanoTime()
        lane.cancel()
        worker.join()
        val error = outcome[0]
        val probe = JSONObject()
            .put("utterance_id", longest.id)
            .put("cancel_after_ms", CANCEL_AFTER_MS)
            .put("cancel_observed", error is CancellationException)
            .put("error", error?.let { it::class.java.name })
        if (error is CancellationException) {
            probe.put("cancel_return_ms", round3((finishedAt[0] - cancelRequestedAt) / 1_000_000.0))
        }
        lane.resetCancellation()
        val recovery = lane.synthesize(shortest.text) { _, _ -> }
        probe.put("recovery_utterance_id", shortest.id)
            .put("recovery_ok", recovery.finite && recovery.chunks.isNotEmpty())
            .put("recovery_rtf", round4(recovery.synthMs / recovery.audioMs))
        return probe
    }

    private fun summary(rows: JSONArray): JSONObject {
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
        val rtfs = list.map { it.getDouble("rtf") }.sorted()
        val firstChunks = list.map { it.getLong("first_chunk_ms") }.sorted()
        return JSONObject()
            .put("rows", list.size)
            .put("all_finite", list.all { it.getBoolean("finite") })
            .put("rtf_min", rtfs.first())
            .put("rtf_median", rtfs[rtfs.size / 2])
            .put("rtf_max", rtfs.last())
            .put("rtf_mean", round4(rtfs.average()))
            .put("first_chunk_ms_median", firstChunks[firstChunks.size / 2])
            .put("first_chunk_ms_max", firstChunks.last())
    }

    private fun deviceJson() = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("android_release", Build.VERSION.RELEASE)
        .put("sdk_int", Build.VERSION.SDK_INT)
        .put("fingerprint", Build.FINGERPRINT)

    private fun buildJson() = JSONObject()
        .put("application_id", context.packageName)
        .put("version_name", BuildConfig.VERSION_NAME)
        .put("version_code", BuildConfig.VERSION_CODE)
        .put("git_sha", BuildConfig.GIT_SHA)
        .put("build_timestamp", BuildConfig.BUILD_TIMESTAMP)

    private companion object {
        const val CANCEL_AFTER_MS = 1000L
        fun vmKb(key: String): Long = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()
            ?: -1L

        fun round3(value: Double) = Math.round(value * 1000) / 1000.0
        fun round4(value: Double) = Math.round(value * 10000) / 10000.0
    }
}

internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Output path for a lane and label, under app-private storage: `files/tts_baseline/<lane>/<label>.json`. */
fun debugTtsBaselineOutput(context: Context, lane: String, label: String): File =
    File(context.filesDir, "tts_baseline/$lane/$label.json")
