package ai.jandal.spike1988

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Public-clip parity: full offline decode (reference `decode_offline`: stream every window, finish,
 * PCEC with tail) per tier, written to app-private `files/spike1988-out/parity-<variant>.json`.
 * Comparison against the host reference output is done on host (`scripts/check_parity.py`).
 */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    @Test
    fun publicClipsDecode() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val size = args.getString("size") ?: "s"
        val dir = File(context.filesDir, "spike1988/$size")
        val clipDir = File(context.filesDir, "spike1988/public")
        val clips = (args.getString("clips") ?: "1.wav").split(',')
        val tiers = (args.getString("tiers") ?: "stream_c16r4,stream_c32r8").split(',')
        val variant = args.getString("variant") ?: "default"
        val metadata = JSONObject(File(dir, "metadata.json").readText())
        val results = JSONArray()
        EtModules.load(File(dir, "asr_encoder.pte").absolutePath, variant).use { module ->
            for (tier in tiers) {
                val decoder = EtAsr4allDecoder(module, metadata, size, tier)
                for (clip in clips) {
                    val pcm = Wav.readPcm16Mono16k(File(clipDir, clip))
                    val margins = ArrayList<Float>()
                    decoder.frameMargins = margins
                    decoder.reset()
                    decoder.decodeStream(pcm)
                    val raw = decoder.finish()
                    val pcec = decoder.decodePcec(decodeTail = true)
                    val row = JSONObject().put("size", size).put("tier", tier).put("clip", clip)
                        .put("variant", variant).put("raw", raw).put("pcec", pcec)
                        .put("stream_calls", decoder.streamCalls).put("pcec_calls", decoder.pcecCalls)
                    Log.i(TAG, row.toString())
                    row.put("frame_ids", JSONArray(decoder.frameIds))
                        .put("frame_margins", JSONArray(margins.map { it.toDouble() }))
                    results.put(row)
                }
            }
        }
        val out = File(context.filesDir, "spike1988-out").apply { mkdirs() }
        File(out, "parity-$size-$variant.json").writeText(results.toString(1))
    }

    private companion object { const val TAG = "ET1988" }
}
