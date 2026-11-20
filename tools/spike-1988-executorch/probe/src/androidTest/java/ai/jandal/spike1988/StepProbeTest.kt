package ai.jandal.spike1988

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.pytorch.executorch.EValue
import org.pytorch.executorch.ExecuTorchRuntime
import org.pytorch.executorch.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root-cause probe: run the first acoustic steps of one stream method exactly as the reference
 * (window starts at the commit boundary, zero caches, carried caches) under several runtime
 * configurations, and dump raw logits/hidden for host comparison.
 *
 * Variants are [EtModules.load] tokens (weight cache, workspace sharing, threads, load mode).
 */
@RunWith(AndroidJUnit4::class)
class StepProbeTest {
    @Test
    fun firstStepsUnderRuntimeVariants() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val size = args.getString("size") ?: "s"
        val dir = File(context.filesDir, "spike1988/$size")
        val pte = File(dir, "asr_encoder.pte")
        val wav = File(context.filesDir, args.getString("wav") ?: "spike1988/public/1.wav")
        val tier = args.getString("tier") ?: "stream_c16r4"
        val steps = (args.getString("steps") ?: "2").toInt()
        val variants = (args.getString("variants") ?: "default").split(',')
        val out = File(context.filesDir, "spike1988-out/step-probe").apply { deleteRecursively(); mkdirs() }

        Log.i(TAG, JSONObject()
            .put("backends", JSONArray(ExecuTorchRuntime.getRegisteredBackends().toList()))
            .put("ops", ExecuTorchRuntime.getRegisteredOps().size).toString())
        val pcm = Wav.readPcm16Mono16k(wav)
        for (variant in variants) {
            val module = EtModules.load(pte.absolutePath, variant)
            val vout = File(out, variant).apply { mkdirs() }
            module.use { m ->
                val meta = m.getMethodMetadata(tier)
                Log.i(TAG, JSONObject().put("variant", variant).put("tier", tier)
                    .put("backends", JSONArray(meta.backends.toList())).toString())
                val geo = Asr4allEtGeometry.of(size, tier)
                val window = geo.window
                val commit = geo.commit
                var filter = FloatArray(geo.filter)
                var attn = FloatArray(geo.attn)
                for (step in 0 until steps) {
                    val start = step * commit * SPF
                    val win = FloatArray(window)
                    val hi = minOf(pcm.size, start + window)
                    if (hi > start) pcm.copyInto(win, 0, start, hi)
                    val t0 = System.nanoTime()
                    val outs = m.execute(
                        tier,
                        EValue.from(Tensor.fromBlob(win, longArrayOf(1, window.toLong()))),
                        EValue.from(Tensor.fromBlob(filter, longArrayOf(geo.filter.toLong()))),
                        EValue.from(Tensor.fromBlob(attn, longArrayOf(geo.attn.toLong()))),
                    )
                    val ms = (System.nanoTime() - t0) / 1e6
                    val logits = outs[0].toTensor().dataAsFloatArray
                    filter = outs[1].toTensor().dataAsFloatArray
                    attn = outs[2].toTensor().dataAsFloatArray
                    val hidden = outs[3].toTensor().dataAsFloatArray
                    writeF32(File(vout, "logits$step.f32"), logits)
                    writeF32(File(vout, "hidden$step.f32"), hidden)
                    writeF32(File(vout, "filter$step.f32"), filter)
                    writeF32(File(vout, "attn$step.f32"), attn)
                    val vocab = logits.size / commit
                    val argmax = IntArray(commit) { f ->
                        var best = 0
                        for (v in 1 until vocab) if (logits[f * vocab + v] > logits[f * vocab + best]) best = v
                        best
                    }
                    Log.i(TAG, JSONObject().put("variant", variant).put("step", step)
                        .put("ms", ms).put("argmax", JSONArray(argmax.toList()))
                        .put("logits_shape", JSONArray(outs[0].toTensor().shape().toList())).toString())
                }
            }
        }
    }

    private fun writeF32(file: File, values: FloatArray) {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(values)
        file.writeBytes(buf.array())
    }

    private companion object {
        const val TAG = "ET1988"
        const val SPF = 640
    }
}
