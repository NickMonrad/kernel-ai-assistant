package ai.jandal.spike1988

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * One lane per instrumentation process (fresh process => load time and peak memory are per lane).
 *
 * `mode=paced`: 100 ms (1,600-sample) chunks are released on the wall clock as a microphone would deliver
 * them; each chunk is decoded (raw + PCEC partial) as soon as it is released or as soon as the decoder is
 * free. Per chunk we keep lag (decode end - release) and backlog (released - decoded samples at decode end).
 * After the last chunk the lane is flushed (EOF/forced finalisation, used only for WER).
 * `mode=compute`: the same chunks back to back with no pacing (compute-only RTFx and WER).
 *
 * Results (including private transcripts) go to app-private `files/spike1988-out/bench/`; scoring is on host.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkTest {
    @Test
    fun lane() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = args.getString("runtime") ?: error("runtime")
        val size = args.getString("size") ?: error("size")
        val tier = args.getString("tier") ?: error("tier")
        val variant = args.getString("variant") ?: "default"
        val mode = args.getString("mode") ?: "paced"
        val setName = args.getString("set") ?: error("set")
        val clipDir = File(context.filesDir, args.getString("clips") ?: error("clips"))
        val warmup = File(context.filesDir, args.getString("warmup") ?: "spike1988/public/2.wav")
        val modelRoot = File(context.filesDir, "spike1988")
        val clips = clipDir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }
        check(clips.isNotEmpty()) { "no clips in $clipDir" }

        val sampler = MemorySampler().start()
        val pssBaselineKb = sampler.sample()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lane = StreamingLane.open(runtime, modelRoot, size, tier, variant)
        val loadMs = ms(SystemClock.elapsedRealtimeNanos() - t0)
        val pssAfterLoadKb = sampler.sample()

        val report = JSONObject()
            .put("schema", 1).put("runtime", runtime).put("size", size).put("tier", tier).put("variant", variant)
            .put("mode", mode).put("set", setName).put("lane", lane.description)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} ${Build.SOC_MODEL}")
            .put("cpus", Runtime.getRuntime().availableProcessors())
            .put("onnx_engine_commit", BuildConfig.ONNX_ENGINE_COMMIT)
            .put("load_ms", loadMs)
            .put("pss_baseline_kb", pssBaselineKb).put("pss_after_load_kb", pssAfterLoadKb)

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
                lane.reset()
                val row = if (mode == "paced") paced(lane, pcm) else compute(lane, pcm)
                rows.put(row.put("id", clip.name.removeSuffix(".wav")))
            }
            report.put("clips", rows)
        }
        sampler.close()
        report.put("pss_peak_sampled_kb", sampler.peakPssKb)
            .put("vm_hwm_kb", MemorySampler.status("VmHWM"))
        val out = File(context.filesDir, "spike1988-out/bench").apply { mkdirs() }
        val name = "$setName-$mode-$runtime-$size-$tier-$variant.json"
        File(out, name).writeText(report.toString())
        Log.i(TAG, "wrote $name load_ms=$loadMs peak_pss_kb=${sampler.peakPssKb}")
    }

    private fun paced(lane: StreamingLane, pcm: FloatArray): JSONObject {
        val n = (pcm.size + CHUNK - 1) / CHUNK
        val lag = DoubleArray(n)
        val proc = DoubleArray(n)
        val backlog = IntArray(n)
        var firstRaw = -1
        var firstCorr = -1
        var firstRawWall = -1.0
        var firstCorrWall = -1.0
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
            val released = minOf(pcm.size.toLong(), (b - start) * SR / 1_000_000_000L).toInt()
            backlog[i] = maxOf(0, released - endSample)
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
            .put("chunk_lag_ms", JSONArray(lag.toList()))
            .put("chunk_proc_ms", JSONArray(proc.toList()))
            .put("chunk_backlog_samples", JSONArray(backlog.toList()))
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

    private companion object {
        const val TAG = "ET1988"
        const val SR = 16_000
        const val CHUNK = 1_600
    }
}
