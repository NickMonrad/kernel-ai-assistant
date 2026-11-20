package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.voice.asr4all.runtime.EtDType
import com.kernel.ai.core.voice.asr4all.runtime.EtExecutionException
import com.kernel.ai.core.voice.asr4all.runtime.EtOutputCheck
import com.kernel.ai.core.voice.asr4all.runtime.EtOutputShapeMismatchException
import com.kernel.ai.core.voice.asr4all.runtime.EtOutputSpec
import com.kernel.ai.core.voice.asr4all.runtime.EtProgram
import com.kernel.ai.core.voice.asr4all.runtime.EtProgramClosedException
import com.kernel.ai.core.voice.asr4all.runtime.EtTensor

internal class EncoderOutput(val logits: FloatArray, val filterCache: EtTensor, val attnCache: EtTensor, val hidden: FloatArray)

internal class PcecStreamOutput(val slots: IntArray, val state: EtTensor, val seen: Long)

/**
 * Typed calls into the asr4all `.pte` methods. Input shapes and every output expectation come
 * from [Asr4AllModelConfig]; each output is checked with [EtOutputCheck] before it is read.
 */
internal class Asr4AllProgram(private val program: EtProgram, private val config: Asr4AllModelConfig) {

    fun requireMethods(tier: Asr4AllTier) {
        val names = guard("listing methods") { program.methodNames }
        val missing = listOf(tier.name, tier.pcecMethod, tier.pcecFlushMethod).filterNot { it in names }
        if (missing.isNotEmpty()) {
            throw Asr4AllConfigException("program has no method(s) $missing required by tier ${tier.name}; it has ${names.sorted()}")
        }
    }

    fun initialFilterCache(): EtTensor = EtTensor.zeros(config.filterCacheNumel.toLong())
    fun initialAttnCache(): EtTensor = EtTensor.zeros(config.attnCacheNumel.toLong())
    fun initialPcecState(): EtTensor = EtTensor.zeros(1, PCEC_STATE_NUMEL)

    fun encoder(tier: Asr4AllTier, window: FloatArray, filterCache: EtTensor, attnCache: EtTensor): EncoderOutput {
        val commit = tier.commitFrames.toLong()
        val outputs = run(
            tier.name,
            listOf(EtTensor.of(window, 1, tier.windowSamples.toLong()), filterCache, attnCache),
            listOf(
                EtOutputSpec(longArrayOf(1, commit, config.vocabSize.toLong()), EtDType.FLOAT32),
                EtOutputSpec(longArrayOf(config.filterCacheNumel.toLong()), EtDType.FLOAT32),
                EtOutputSpec(longArrayOf(config.attnCacheNumel.toLong()), EtDType.FLOAT32),
                EtOutputSpec(longArrayOf(1, commit, config.hiddenDim.toLong()), EtDType.FLOAT32),
            ),
        )
        return EncoderOutput(outputs[0].floats(), outputs[1], outputs[2], outputs[3].floats())
    }

    fun pcecStream(tier: Asr4AllTier, inputs: PcecInputs, state: EtTensor, seen: Long): PcecStreamOutput {
        val rung = tier.pcecRung.toLong()
        val outputs = run(
            tier.pcecMethod,
            pcecInputs(rung, inputs) + listOf(state, EtTensor.of(longArrayOf(seen), 1)),
            listOf(
                EtOutputSpec(longArrayOf(1, config.pcec.slotsPerToken * rung), EtDType.INT64),
                EtOutputSpec(longArrayOf(1, PCEC_STATE_NUMEL), EtDType.FLOAT32),
                EtOutputSpec(longArrayOf(1), EtDType.INT64),
            ),
        )
        return PcecStreamOutput(toSlots(outputs[0].longs()), outputs[1], outputs[2].longs()[0])
    }

    fun pcecFlush(tier: Asr4AllTier, inputs: PcecInputs, n: Int, state: EtTensor, seen: Long): IntArray {
        val rung = tier.pcecRung.toLong()
        val outputs = run(
            tier.pcecFlushMethod,
            pcecInputs(rung, inputs) + listOf(EtTensor.of(longArrayOf(n.toLong())), state, EtTensor.of(longArrayOf(seen), 1)),
            listOf(
                EtOutputSpec(
                    longArrayOf(1, config.pcec.slotsPerToken * (rung + config.pcec.lagTokens) + 1),
                    EtDType.INT64,
                ),
            ),
        )
        return toSlots(outputs[0].longs())
    }

    fun close() = program.close()

    private fun pcecInputs(rung: Long, inputs: PcecInputs): List<EtTensor> = listOf(
        EtTensor.of(inputs.ids, 1, rung),
        EtTensor.of(inputs.pauses, 1, rung),
        EtTensor.of(inputs.hidden, 1, rung, config.hiddenDim.toLong()),
        EtTensor.of(inputs.floats, 1, rung),
    )

    private fun toSlots(raw: LongArray): IntArray = IntArray(raw.size) { i ->
        val v = raw[i]
        if (v < 0 || v > config.pcec.blankId) {
            throw Asr4AllShapeException("pcec", i, "slot id in [0, ${config.pcec.blankId}]", v.toString())
        }
        v.toInt()
    }

    private fun run(method: String, inputs: List<EtTensor>, expected: List<EtOutputSpec>): List<EtTensor> {
        val outputs = guard(method) { program.execute(method, inputs) }
        try {
            EtOutputCheck.verify(method, outputs, expected)
        } catch (e: EtOutputShapeMismatchException) {
            throw Asr4AllShapeException(e.method, e.outputIndex, e.expected, e.actual, e)
        }
        return outputs
    }

    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: EtProgramClosedException) {
        throw Asr4AllClosedException()
    } catch (e: EtExecutionException) {
        throw Asr4AllRuntimeException("$what failed: ${e.message}", e)
    }

    companion object {
        /**
         * Element count of the PCEC head's recurrent state. The config files do not state it;
         * it is the observed input/output shape `[1, 166976]` of `pcec_c16`/`pcec_c64` and their
         * flush methods, identical in the S, M and L exports at the pinned revisions. The engine
         * never indexes the state; a different size fails at execution with a typed error.
         */
        const val PCEC_STATE_NUMEL = 166_976L
    }
}
