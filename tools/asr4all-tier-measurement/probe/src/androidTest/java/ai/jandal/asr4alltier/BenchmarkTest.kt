package ai.jandal.asr4alltier

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.voice.TranscriptNormaliser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * One lane per instrumentation process (fresh process => load time and peak memory are per lane and repeat).
 *
 * Arguments: `runtime` (et | zipformer | native), `size`, `tier`, `variant` (ExecuTorch threads, e.g. `t2`),
 * `cpus` (optional hex affinity mask for the low-end proxy), `mode` (paced | compute), `set`, `clips`
 * (app-private dir), `repeat` (label only).
 *
 * `paced`: 100 ms (1,600-sample) chunks are released on the wall clock as a microphone would deliver them; each
 * is decoded as soon as it is released or the decoder is free. Per chunk: lag (decode end - release) and
 * processing time; per clip: first raw / corrected partial, number of partial updates, finalisation time.
 * After the last chunk the lane is flushed (EOF; used for WER only, never as endpoint latency).
 * `compute`: the same chunks back to back (compute RTFx and WER).
 *
 * Every final is stored as emitted and also through Jandal's shipping `TranscriptNormaliser`, after the
 * engine's shipping controller pre-processing. Results (with private transcripts) go to app-private storage;
 * scoring is on host.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkTest {
    @Test
    fun lane() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = args.getString("runtime") ?: error("runtime")
        val size = args.getString("size") ?: "-"
        val tier = args.getString("tier") ?: "-"
        val variant = args.getString("variant") ?: "default"
        val cpus = args.getString("cpus")
        val mode = args.getString("mode") ?: "paced"
        val setName = args.getString("set") ?: error("set")
        val repeat = args.getString("repeat") ?: "1"
        val clipDir = File(context.filesDir, args.getString("clips") ?: error("clips"))
        val warmup = File(context.filesDir, args.getString("warmup") ?: "tier/public/2.wav")
        val modelRoot = File(context.filesDir, "tier")
        val clips = clipDir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }
        check(clips.isNotEmpty()) { "no clips in $clipDir" }

        if (cpus != null) CpuAffinity.apply(cpus)
        val sampler = MemorySampler().start()
        val pssBaselineKb = sampler.sample()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lane = StreamingLane.open(context, runtime, modelRoot, size, tier, variant)
        if (lane is NativeLane && setName == "public") lane.debug = true
        val loadMs = ms(SystemClock.elapsedRealtimeNanos() - t0)
        val pssAfterLoadKb = sampler.sample()

        val report = JSONObject()
            .put("schema", 2).put("runtime", runtime).put("size", size).put("tier", tier).put("variant", variant)
            .put("cpus", cpus ?: JSONObject.NULL).put("mode", mode).put("set", setName).put("repeat", repeat)
            .put("lane", lane.description).put("in_process", lane.inProcess)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} ${Build.SOC_MODEL}")
            .put("cpu_count", Runtime.getRuntime().availableProcessors())
            .put("normaliser_commit", BuildConfig.NORMALISER_COMMIT)
            .put("private_et_driver_sha256", BuildConfig.PRIVATE_ET_DRIVER_SHA256)
            .put("app_apk_sha256", sha256(File(context.applicationInfo.sourceDir)))
            .put("test_apk_sha256", sha256(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
            .put("load_ms", loadMs)
            .put("pss_baseline_kb", pssBaselineKb).put("pss_after_load_kb", pssAfterLoadKb)

        val affinity = sortedSetOf<String>()
        lane.use {
            // Warm-up on a public clip: the first decode pays one-off kernel/arena setup.
            val warm = Wav.readPcm16Mono16k(warmup)
            lane.reset()
            val w0 = SystemClock.elapsedRealtimeNanos()
            lane.feed(warm.copyOfRange(0, minOf(warm.size, CHUNK * 20)))
            report.put("first_feed_ms", ms(SystemClock.elapsedRealtimeNanos() - w0))
            lane.finish()
            report.put("pss_after_warmup_kb", sampler.sample())

            val rows = JSONArray()
            for (clip in clips) {
                val pcm = Wav.readPcm16Mono16k(clip)
                if (cpus != null) CpuAffinity.apply(cpus)
                lane.reset()
                val row = if (mode == "paced") paced(lane, pcm) else compute(lane, pcm)
                val raw = row.getString("raw_final")
                val final = row.getString("pcec_final")
                row.put("raw_final_normalised", TranscriptNormaliser.normalise(lane.shippingPreNormalise(raw)))
                    .put("pcec_final_normalised", TranscriptNormaliser.normalise(lane.shippingPreNormalise(final)))
                when (lane) {
                    is ZipformerLane -> row.put("endpoints_before_eof", lane.endpointsBeforeEof)
                    is NativeLane -> row.put("native_error", lane.error ?: JSONObject.NULL)
                        .put("native_ended_before_eof", lane.endedBeforeEof)
                    else -> Unit
                }
                if (cpus != null) affinity += CpuAffinity.allowedLists()
                rows.put(row.put("id", clip.name.removeSuffix(".wav")))
            }
            report.put("clips", rows)
        }
        sampler.close()
        report.put("pss_peak_sampled_kb", sampler.peakPssKb)
            .put("vm_hwm_kb", MemorySampler.status("VmHWM"))
            .put("observed_cpus_allowed", JSONArray(affinity.toList()))
        val out = File(context.filesDir, "tier-out").apply { mkdirs() }
        val name = "$setName-$mode-$runtime-$size-$tier-$variant-${cpus ?: "all"}-r$repeat.json"
        File(out, name).writeText(report.toString())
        Log.i(TAG, "wrote $name load_ms=$loadMs peak_pss_kb=${sampler.peakPssKb} cpus=$affinity")
    }

    private fun paced(lane: StreamingLane, pcm: FloatArray): JSONObject {
        val n = (pcm.size + CHUNK - 1) / CHUNK
        val lag = DoubleArray(n)
        val proc = DoubleArray(n)
        var firstRaw = -1
        var firstCorr = -1
        var firstRawWall = -1.0
        var firstCorrWall = -1.0
        var lastRawText = ""
        var lastCorrText = ""
        var rawUpdates = 0
        var corrUpdates = 0
        var computeNs = 0L
        val start = SystemClock.elapsedRealtimeNanos()
        for (i in 0 until n) {
            val endSample = minOf(pcm.size, (i + 1) * CHUNK)
            val release = start + endSample * 1_000_000_000L / SR
            while (true) {
                val wait = release - SystemClock.elapsedRealtimeNanos()
                if (wait <= 0) break
                LockSupport.parkNanos(wait)
            }
            val a = SystemClock.elapsedRealtimeNanos()
            val raw = lane.feed(pcm.copyOfRange(i * CHUNK, endSample))
            val corr = lane.correctedPartial()
            val b = SystemClock.elapsedRealtimeNanos()
            computeNs += b - a
            proc[i] = ms(b - a)
            lag[i] = ms(b - release)
            if (raw.isNotEmpty() && raw != lastRawText) { rawUpdates++; lastRawText = raw }
            if (corr.isNotEmpty() && corr != lastCorrText) { corrUpdates++; lastCorrText = corr }
            if (firstRaw < 0 && raw.isNotEmpty()) { firstRaw = endSample; firstRawWall = ms(b - start) }
            if (firstCorr < 0 && corr.isNotEmpty()) { firstCorr = endSample; firstCorrWall = ms(b - start) }
        }
        val f0 = SystemClock.elapsedRealtimeNanos()
        val (rawFinal, pcecFinal) = lane.finish()
        val f1 = SystemClock.elapsedRealtimeNanos()
        computeNs += f1 - f0
        return JSONObject()
            .put("samples", pcm.size)
            .put("raw_final", rawFinal).put("pcec_final", pcecFinal)
            .put("first_raw_partial_audio_ms", if (firstRaw < 0) JSONObject.NULL else firstRaw * 1000.0 / SR)
            .put("first_raw_partial_wall_ms", if (firstRaw < 0) JSONObject.NULL else firstRawWall)
            .put("first_corrected_partial_audio_ms", if (firstCorr < 0) JSONObject.NULL else firstCorr * 1000.0 / SR)
            .put("first_corrected_partial_wall_ms", if (firstCorr < 0) JSONObject.NULL else firstCorrWall)
            .put("raw_partial_updates", rawUpdates).put("corrected_partial_updates", corrUpdates)
            .put("chunk_lag_ms", JSONArray(lag.toList()))
            .put("chunk_proc_ms", JSONArray(proc.toList()))
            .put("finalize_ms", ms(f1 - f0))
            .put("final_wall_after_audio_end_ms", ms(f1 - (start + pcm.size * 1_000_000_000L / SR)))
            .put("compute_ms", ms(computeNs))
    }

    private fun compute(lane: StreamingLane, pcm: FloatArray): JSONObject {
        val a = SystemClock.elapsedRealtimeNanos()
        var i = 0
        while (i < pcm.size) {
            lane.feed(pcm.copyOfRange(i, minOf(pcm.size, i + CHUNK)))
            i += CHUNK
        }
        val (rawFinal, pcecFinal) = lane.finish()
        val b = SystemClock.elapsedRealtimeNanos()
        return JSONObject().put("samples", pcm.size).put("raw_final", rawFinal).put("pcec_final", pcecFinal)
            .put("compute_ms", ms(b - a))
    }

    private fun ms(ns: Long) = ns / 1e6

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ASR4ALL_TIER"
        const val SR = 16_000
        const val CHUNK = 1_600
    }
}
