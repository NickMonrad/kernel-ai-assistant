package ai.jandal.spike1988

import com.kernel.ai.core.voice.asr4all.Asr4allEngine
import org.json.JSONObject
import org.pytorch.executorch.Module
import java.io.File

/** One runtime/size/tier under test. Both lanes run the same FUTO streaming algorithm with PCEC on. */
interface StreamingLane : AutoCloseable {
    val description: String
    fun reset()
    /** Feeds audio and returns the raw (uncorrected) transcript so far. */
    fun feed(chunk: FloatArray): String
    /** PCEC-corrected partial: committed slots only, no tail decode (reference `decode_pcec()`). */
    fun correctedPartial(): String
    /** Flushes the tail and returns (raw final, PCEC-corrected final with tail decode). */
    fun finish(): Pair<String, String>

    companion object {
        /** Opens a lane; the returned lane has loaded every method/session it will use. */
        fun open(runtime: String, modelRoot: File, size: String, tier: String, variant: String): StreamingLane =
            when (runtime) {
                "et" -> EtLane(File(modelRoot, size), size, tier, variant)
                "onnx" -> OnnxLane(File(modelRoot, "onnx-$size"), tier)
                else -> error("runtime must be et or onnx")
            }
    }
}

/** FUTO ExecuTorch/XNNPACK INT8 `.pte`, driven by [EtAsr4allDecoder]. */
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

/** Jandal's ONNX Runtime FP32 engine from PR #1987, unmodified (see build.gradle.kts). */
class OnnxLane(dir: File, tier: String) : StreamingLane {
    private val engine = Asr4allEngine.open(dir, tier)
    override val description = "onnxruntime-android/fp32 (#1987 Asr4allEngine) ${dir.name} $tier"

    override fun reset() = engine.reset()
    override fun feed(chunk: FloatArray): String = engine.decodeStream(chunk)
    override fun correctedPartial(): String = engine.decodePcec(decodeTail = false)
    override fun finish(): Pair<String, String> {
        val raw = engine.finish()
        return raw to engine.decodePcec(decodeTail = true)
    }
    override fun close() = engine.close()
}
