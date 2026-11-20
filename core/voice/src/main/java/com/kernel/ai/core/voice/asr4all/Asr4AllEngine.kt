package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.voice.asr4all.runtime.EtProgramLoader
import com.kernel.ai.core.voice.asr4all.runtime.EtTensor
import java.io.File

/**
 * Streaming asr4all speech recogniser on FUTO's ExecuTorch INT8 (XNNPACK) export (#2004).
 *
 * Input is mono float PCM at the export's sample rate (16 kHz), unnormalised, as
 * `preprocessor_config.json` defines; [acceptPcm16] converts 16-bit PCM. Audio arrives in
 * chunks of any length. Each time a full encoder window is buffered the engine runs the tier's
 * encoder method, greedy-decodes the CTC frames and feeds the tokens to the PCEC head, so
 * [partialText] is punctuated and cased: settled text plus a provisional tail. [finish] encodes
 * the rest of the audio (zero-padded to whole windows), flushes PCEC and returns the final text.
 *
 * Threading: all methods are serialised on one lock, so the engine may be shared, but calls
 * block while a method runs. Inference must run off the main thread.
 *
 * Lifecycle: [open] loads and validates; [reset] starts a new utterance without reloading;
 * [finish] also leaves the engine ready for a new utterance; [close] releases the native
 * program, after which every call throws [Asr4AllClosedException].
 */
class Asr4AllEngine private constructor(
    val config: Asr4AllModelConfig,
    val tier: Asr4AllTier,
    private val program: Asr4AllProgram,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    private val decoder = CtcStreamDecoder(config)
    private val pcec = PcecStream(program, config, tier)
    private var filterCache: EtTensor = program.initialFilterCache()
    private var attnCache: EtTensor = program.initialAttnCache()

    /** Audio not yet consumed; `buffer[0]` is the first sample of the next encoder window. */
    private var buffer = FloatArray(tier.windowSamples * 2)
    private var buffered = 0
    private var partial = ""

    /** The latest live text for the current utterance; empty before the first token. */
    val partialText: String get() = synchronized(lock) { ensureOpen(); partial }

    /** Appends [length] samples of float PCM from [samples] and returns the updated [partialText]. */
    fun acceptAudio(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): String = synchronized(lock) {
        ensureOpen()
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "bad range $offset+$length of ${samples.size}" }
        for (i in offset until offset + length) {
            if (!samples[i].isFinite()) throw Asr4AllInputException("non-finite sample at index $i")
        }
        failSafe {
            append(samples, offset, length)
            var changed = false
            while (buffered >= tier.windowSamples) {
                changed = encodeWindow() or changed
                consume(tier.hopSamples)
            }
            if (changed) partial = pcec.text(decoder.openToken, final = false)
            partial
        }
    }

    /** 16-bit PCM convenience: scales by 1/32768 and calls [acceptAudio]. */
    fun acceptPcm16(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): String {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "bad range $offset+$length of ${samples.size}" }
        val floats = FloatArray(length) { samples[offset + it] / 32768f }
        return acceptAudio(floats)
    }

    /** Ends the utterance: returns the final text and resets for the next utterance. */
    fun finish(): String = synchronized(lock) {
        ensureOpen()
        failSafe {
            while (buffered > 0) {
                if (buffered < tier.windowSamples) buffer.fill(0f, buffered, tier.windowSamples)
                encodeWindow()
                consume(minOf(tier.hopSamples, buffered))
            }
            decoder.close(pcec::add)
            val text = pcec.text(final = true)
            resetUtterance()
            text
        }
    }

    /** Discards the current utterance; the loaded program is kept. */
    fun reset() = synchronized(lock) {
        ensureOpen()
        resetUtterance()
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        program.close()
    }

    private fun ensureOpen() {
        if (closed) throw Asr4AllClosedException()
    }

    /** Runs [block]; on any failure the utterance is discarded so no stale state leaks into the next one. */
    private inline fun <T> failSafe(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        if (!closed) resetUtterance()
        throw e
    }

    private fun resetUtterance() {
        decoder.reset()
        pcec.reset()
        filterCache = program.initialFilterCache()
        attnCache = program.initialAttnCache()
        buffered = 0
        partial = ""
    }

    private fun append(samples: FloatArray, offset: Int, length: Int) {
        if (buffered + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, buffered + length))
        System.arraycopy(samples, offset, buffer, buffered, length)
        buffered += length
    }

    private fun consume(count: Int) {
        System.arraycopy(buffer, count, buffer, 0, buffered - count)
        buffered -= count
    }

    /** Encodes `buffer[0 until windowSamples]`; returns true when a token started. */
    private fun encodeWindow(): Boolean {
        val window = buffer.copyOf(tier.windowSamples)
        val out = program.encoder(tier, window, filterCache, attnCache)
        filterCache = out.filterCache
        attnCache = out.attnCache
        return decoder.accept(out.logits, out.hidden, tier.commitFrames, pcec::add)
    }

    companion object {
        /**
         * Default XNNPACK thread count: four threads, the performance-core count of the
         * S23 Ultra and S21 class devices this engine targets.
         */
        const val DEFAULT_THREADS = 4

        /**
         * Opens the [size] export in [modelDir] at [tierName] (default: the export's default
         * tier), validating the config files against each other and against [size], checking the
         * program's methods, and running each method once on silence so that any shape mismatch
         * between the config and the `.pte` fails here with a typed error.
         */
        fun open(
            modelDir: File,
            size: Asr4AllSize,
            loader: EtProgramLoader,
            tierName: String? = null,
            threads: Int = DEFAULT_THREADS,
        ): Asr4AllEngine {
            require(threads >= 1) { "threads must be >= 1" }
            val config = Asr4AllModelConfig.load(modelDir, size)
            val tier = config.tier(tierName)
            if (!config.encoderFile.isFile) throw Asr4AllConfigException("missing ${config.encoderFile.path}")
            val etProgram = try {
                loader.load(config.encoderFile, threads)
            } catch (e: RuntimeException) {
                throw Asr4AllRuntimeException("cannot load ${config.encoderFile.name}: ${e.message}", e)
            }
            val program = Asr4AllProgram(etProgram, config)
            try {
                program.requireMethods(tier)
                validate(program, config, tier)
            } catch (e: Throwable) {
                program.close()
                if (e is Asr4AllRuntimeException) {
                    throw Asr4AllConfigException("program rejected inputs derived from the config files: ${e.message}", e)
                }
                throw e
            }
            return Asr4AllEngine(config, tier, program)
        }

        private fun validate(program: Asr4AllProgram, config: Asr4AllModelConfig, tier: Asr4AllTier) {
            program.encoder(tier, FloatArray(tier.windowSamples), program.initialFilterCache(), program.initialAttnCache())
            val rung = tier.pcecRung
            val pad = PcecInputs(
                LongArray(rung) { config.pcec.padInputId.toLong() },
                LongArray(rung),
                FloatArray(rung * config.hiddenDim),
                FloatArray(rung),
            )
            val stream = program.pcecStream(tier, pad, program.initialPcecState(), 0)
            program.pcecFlush(tier, pad, 0, stream.state, stream.seen)
        }
    }
}
