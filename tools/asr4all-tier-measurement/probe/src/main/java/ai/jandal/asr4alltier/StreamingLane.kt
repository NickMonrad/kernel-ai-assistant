package ai.jandal.asr4alltier

import android.content.Context
import org.json.JSONObject
import org.pytorch.executorch.Module
import java.io.File

/**
 * One recogniser under test, fed 16 kHz mono float PCM in chunks.
 *
 * Every lane reports its text *as emitted* by the engine; [BenchmarkTest] additionally scores every final
 * through Jandal's shipping `TranscriptNormaliser` exactly as the shipping controller for that engine would.
 */
interface StreamingLane : AutoCloseable {
    val description: String
    /** True when the recogniser runs inside this process (load time and memory are attributable). */
    val inProcess: Boolean get() = true
    fun reset()
    /** Feeds audio; returns the newest partial text (raw for asr4all, streaming hypothesis for others). */
    fun feed(chunk: FloatArray): String
    /** Corrected partial where the engine has one (asr4all PCEC without tail); else same as last partial. */
    fun correctedPartial(): String
    /** Ends the utterance; returns (raw final, final). For asr4all: (raw CTC, flushed PCEC). */
    fun finish(): Pair<String, String>

    /** Text exactly as the shipping controller for this engine would pass it to [TranscriptNormaliser]. */
    fun shippingPreNormalise(final: String): String = final

    companion object {
        fun open(context: Context, runtime: String, modelRoot: File, size: String, tier: String, variant: String): StreamingLane =
            when (runtime) {
                "et" -> EtLane(File(modelRoot, size), size, tier, variant)
                "zipformer" -> ZipformerLane(File(modelRoot, "zipformer"))
                "native" -> NativeLane(context, variant)
                else -> error("runtime must be et, zipformer or native")
            }
    }
}

/** FUTO ExecuTorch/XNNPACK INT8 `.pte`, driven by the private [EtAsr4allDecoder]. */
class EtLane(dir: File, size: String, tier: String, variant: String) : StreamingLane {
    private val module: Module = EtModules.load(File(dir, "asr_encoder.pte").absolutePath, variant)
    private val decoder: EtAsr4allDecoder
    override val description = "executorch-1.5.1/xnnpack-int8 $size $tier variant=$variant"

    init {
        val metadata = JSONObject(File(dir, "metadata.json").readText())
        val tiers = metadata.getJSONArray("tiers")
        val pcec = (0 until tiers.length()).map(tiers::getJSONObject).single { it.getString("name") == tier }
            .getJSONObject("pcec")
        // Load every method now so load time is attributed to load, not to the first utterance.
        for (method in listOf(tier, pcec.getString("name"), pcec.getString("flush"))) module.loadMethod(method)
        decoder = EtAsr4allDecoder(module, metadata, size, tier)
    }

    override fun reset() = decoder.reset()
    override fun feed(chunk: FloatArray): String = decoder.decodeStream(chunk)
    override fun correctedPartial(): String = decoder.decodePcec(decodeTail = false)
    override fun finish(): Pair<String, String> {
        val raw = decoder.finish()
        return raw to decoder.decodePcec(decodeTail = true)
    }
    override fun close() = module.close()
}
