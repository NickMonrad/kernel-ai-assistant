package ai.jandal.asr4alltier

import android.content.Context
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
                // EtLane is compiled only when the private ExecuTorch driver is present (see build.gradle.kts).
                "et" -> Class.forName("ai.jandal.asr4alltier.EtLane")
                    .getConstructor(File::class.java, String::class.java, String::class.java, String::class.java)
                    .newInstance(File(modelRoot, size), size, tier, variant) as StreamingLane
                "zipformer" -> ZipformerLane(File(modelRoot, "zipformer"))
                "native" -> NativeLane(context, variant)
                else -> error("runtime must be et, zipformer or native")
            }
    }
}
