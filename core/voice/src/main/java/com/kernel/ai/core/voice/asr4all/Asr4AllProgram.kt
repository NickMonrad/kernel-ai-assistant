package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.executorch.ExecuTorchException
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.InputShapeMismatchException
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.executorch.OutputShapeMismatchException
import com.kernel.ai.core.executorch.TensorDType
import com.kernel.ai.core.executorch.TensorSpec
import com.kernel.ai.core.executorch.assertOutputShapes
import com.kernel.ai.core.executorch.requireInputShapes

internal class EncoderOutput(val logits: FloatArray, val filterCache: FloatArray, val attnCache: FloatArray, val hidden: FloatArray)

internal class PcecStreamOutput(val slots: IntArray, val state: FloatArray, val seen: Long)

/**
 * The method signatures one tier uses, every dimension derived from [Asr4AllModelConfig]
 * (plus the observed PCEC state size, [Asr4AllProgram.PCEC_STATE_NUMEL]).
 */
internal class Asr4AllSignatures(config: Asr4AllModelConfig, tier: Asr4AllTier) {
    private val f32 = TensorDType.FLOAT32
    private val i64 = TensorDType.INT64
    private val nf = config.filterCacheNumel.toLong()
    private val na = config.attnCacheNumel.toLong()
    private val commit = tier.commitFrames.toLong()
    private val dim = config.hiddenDim.toLong()
    private val rung = tier.pcecRung.toLong()
    private val state = Asr4AllProgram.PCEC_STATE_NUMEL

    val encoder = MethodSignature(
        tier.name,
        inputs = listOf(
            TensorSpec("audio", f32, 1, tier.windowSamples.toLong()),
            TensorSpec("filter_cache", f32, nf),
            TensorSpec("attn_cache", f32, na),
        ),
        outputs = listOf(
            TensorSpec("logits", f32, 1, commit, config.vocabSize.toLong()),
            TensorSpec("filter_cache", f32, nf),
            TensorSpec("attn_cache", f32, na),
            TensorSpec("hidden", f32, 1, commit, dim),
        ),
    )

    private fun tokenInputs() = listOf(
        TensorSpec("token_ids", i64, 1, rung),
        TensorSpec("pause_buckets", i64, 1, rung),
        TensorSpec("hidden", f32, 1, rung, dim),
        TensorSpec("token_float", f32, 1, rung),
    )

    val pcec = MethodSignature(
        tier.pcecMethod,
        inputs = tokenInputs() + listOf(TensorSpec("state", f32, 1, state), TensorSpec("seen", i64, 1)),
        outputs = listOf(
            TensorSpec("slots", i64, 1, config.pcec.slotsPerToken * rung),
            TensorSpec("state", f32, 1, state),
            TensorSpec("seen", i64, 1),
        ),
    )

    val pcecFlush = MethodSignature(
        tier.pcecFlushMethod,
        inputs = tokenInputs() + listOf(TensorSpec("n", i64), TensorSpec("state", f32, 1, state), TensorSpec("seen", i64, 1)),
        outputs = listOf(TensorSpec("tail_slots", i64, 1, config.pcec.slotsPerToken * (rung + config.pcec.lagTokens) + 1)),
    )

    val all: List<MethodSignature> get() = listOf(encoder, pcec, pcecFlush)

    /**
     * Compares these signatures with the ones the `.pte` file declares, so that a program from
     * another size or revision fails before the runtime runs anything.
     */
    fun checkDeclared(declared: Map<String, PteMethodInfo>) {
        for (signature in all) {
            val info = declared[signature.method]
                ?: throw Asr4AllConfigException("program declares no method ${signature.method}; it has ${declared.keys.sorted()}")
            compare(signature.method, "input", signature.inputs, info.inputs)
            compare(signature.method, "output", signature.outputs, info.outputs)
        }
    }

    private fun compare(method: String, kind: String, expected: List<TensorSpec>, declared: List<PteTensorInfo?>) {
        if (expected.size != declared.size) {
            throw Asr4AllConfigException("$method declares ${declared.size} ${kind}s $declared; the config files imply ${expected.size}: $expected")
        }
        expected.forEachIndexed { i, spec ->
            val d = declared[i]
            val ok = d != null && d.dtype == spec.dtype && d.sizes.size == spec.dims.size &&
                spec.dims.indices.all { j -> if (d.dynamic) spec.dims[j] <= d.sizes[j] else spec.dims[j] == d.sizes[j] }
            if (!ok) {
                throw Asr4AllConfigException(
                    "$method $kind $i is $d in the program but the config files imply $spec (files from different sizes or revisions?)",
                )
            }
        }
    }
}

/**
 * Typed calls into the asr4all `.pte` methods through an [Asr4AllBackend]. Every call is
 * checked against its [MethodSignature] with `:core:executorch`'s `requireInputShapes` before
 * the runtime and `assertOutputShapes` before any output is read; PCEC ids are range-checked
 * too, because the 1.5.1 runtime aborts the process on an out-of-range embedding index.
 */
internal class Asr4AllProgram(
    private val backend: Asr4AllBackend,
    private val config: Asr4AllModelConfig,
    val signatures: Asr4AllSignatures,
) {
    private val pcecConfig = config.pcec
    private val maxPauseBucket = maxOf(pcecConfig.pauseBlackout, pcecConfig.pauseBuckets - 1)

    /** Checks that the loaded program exports the methods of [signatures]. */
    fun requireMethods() {
        val names = guard("listing methods") { backend.methodNames }
        val missing = signatures.all.map { it.method }.filterNot { it in names }
        if (missing.isNotEmpty()) {
            throw Asr4AllConfigException("program has no method(s) $missing required by the tier; it has ${names.sorted()}")
        }
    }

    fun initialFilterCache() = FloatArray(config.filterCacheNumel)
    fun initialAttnCache() = FloatArray(config.attnCacheNumel)
    fun initialPcecState() = FloatArray(PCEC_STATE_NUMEL.toInt())

    fun encoder(window: FloatArray, filterCache: FloatArray, attnCache: FloatArray): EncoderOutput {
        val s = signatures.encoder
        val out = run(s, listOf(f32(window, s.inputs[0]), f32(filterCache, s.inputs[1]), f32(attnCache, s.inputs[2])))
        return EncoderOutput(out[0].floats(), out[1].floats(), out[2].floats(), out[3].floats())
    }

    fun pcecStream(inputs: PcecInputs, state: FloatArray, seen: Long): PcecStreamOutput {
        val s = signatures.pcec
        val out = run(s, tokenTensors(s, inputs) + listOf(f32(state, s.inputs[4]), i64(longArrayOf(seen), s.inputs[5])))
        return PcecStreamOutput(toSlots(s.method, out[0].longs()), out[1].floats(), out[2].longs()[0])
    }

    fun pcecFlush(inputs: PcecInputs, n: Int, state: FloatArray, seen: Long): IntArray {
        val s = signatures.pcecFlush
        require(n in 0..inputs.ids.size) { "flush count $n outside 0..${inputs.ids.size}" }
        val out = run(
            s,
            tokenTensors(s, inputs) + listOf(i64(longArrayOf(n.toLong()), s.inputs[4]), f32(state, s.inputs[5]), i64(longArrayOf(seen), s.inputs[6])),
        )
        return toSlots(s.method, out[0].longs())
    }

    fun close() = backend.close()

    private fun tokenTensors(s: MethodSignature, inputs: PcecInputs): List<ExecuTorchTensor> {
        for (id in inputs.ids) {
            if (id < 0 || id >= pcecConfig.inputVocabSize) throw Asr4AllRuntimeException("PCEC token id $id outside the head's ${pcecConfig.inputVocabSize} ids")
        }
        for (p in inputs.pauses) {
            if (p < 0 || p > maxPauseBucket) throw Asr4AllRuntimeException("PCEC pause bucket $p outside 0..$maxPauseBucket")
        }
        return listOf(
            i64(inputs.ids, s.inputs[0]),
            i64(inputs.pauses, s.inputs[1]),
            f32(inputs.hidden, s.inputs[2]),
            f32(inputs.floats, s.inputs[3]),
        )
    }

    private fun f32(data: FloatArray, spec: TensorSpec) = ExecuTorchTensor.Float32(data, spec.dims.toLongArray())
    private fun i64(data: LongArray, spec: TensorSpec) = ExecuTorchTensor.Int64(data, spec.dims.toLongArray())

    private fun ExecuTorchTensor.floats(): FloatArray = (this as ExecuTorchTensor.Float32).data
    private fun ExecuTorchTensor.longs(): LongArray = (this as ExecuTorchTensor.Int64).data

    private fun toSlots(method: String, raw: LongArray): IntArray = IntArray(raw.size) { i ->
        val v = raw[i]
        if (v < 0 || v > pcecConfig.blankId) {
            throw Asr4AllShapeException(method, "$method slot $i is $v, outside the PCEC ids 0..${pcecConfig.blankId}")
        }
        v.toInt()
    }

    private fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> {
        val method = signature.method
        try {
            requireInputShapes(method, inputs, signature.inputs)
        } catch (e: InputShapeMismatchException) {
            throw Asr4AllRuntimeException("engine built inputs that differ from its signature: ${e.message}", e)
        }
        val outputs = guard(method) { backend.run(signature, inputs) }
        try {
            assertOutputShapes(method, outputs, signature.outputs)
        } catch (e: OutputShapeMismatchException) {
            throw Asr4AllShapeException(method, e.message ?: method, e)
        }
        return outputs
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: OutputShapeMismatchException) {
        throw Asr4AllShapeException(what, e.message ?: what, e)
    } catch (e: InputShapeMismatchException) {
        throw Asr4AllRuntimeException("$what rejected its inputs: ${e.message}", e)
    } catch (e: ExecuTorchException) {
        throw Asr4AllRuntimeException("$what failed: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw Asr4AllRuntimeException("$what failed: ${e.message}", e)
    }

    companion object {
        /**
         * Element count of the PCEC head's recurrent state. The config files do not state it;
         * it is the observed input/output shape `[1, 166976]` of `pcec_c16`/`pcec_c64` and their
         * flush methods, identical in the S, M and L exports at the pinned revisions. The engine
         * never indexes the state; [Asr4AllSignatures.checkDeclared] compares it with the program.
         */
        const val PCEC_STATE_NUMEL = 166_976L
    }
}
