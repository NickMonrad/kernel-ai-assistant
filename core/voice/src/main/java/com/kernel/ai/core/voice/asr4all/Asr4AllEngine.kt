package com.kernel.ai.core.voice.asr4all

import java.io.File

/**
 * Streaming asr4all speech recogniser on FUTO's ExecuTorch INT8 (XNNPACK) export (#2004).
 *
 * Input is mono float PCM at the export's sample rate (16 kHz), unnormalised, as
 * `preprocessor_config.json` defines; [acceptPcm16] converts 16-bit PCM. Audio arrives in
 * chunks of any length. Each time a full encoder window is buffered the engine runs the tier's
 * encoder method, greedy-decodes the CTC frames and feeds the tokens to the PCEC head, so
 * [partialText] is punctuated and cased: settled text plus a provisional tail. The latest token
 * is not shown until the next one starts (its PCEC input spans up to that point). [finish]
 * encodes the rest of the audio (zero-padded to whole windows), decodes the frames that are
 * wholly real audio, flushes PCEC and returns the final text.
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
    private var filterCache = program.initialFilterCache()
    private var attnCache = program.initialAttnCache()

    /** Audio not yet consumed; `buffer[0]` is the first sample of the next encoder window. */
    private var buffer = FloatArray(tier.windowSamples * 2)
    private var buffered = 0
    /** Samples received in this utterance. */
    private var received = 0L
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
            received += length
            var changed = false
            while (buffered >= tier.windowSamples) {
                changed = encodeWindow() or changed
                consume(tier.hopSamples)
            }
            if (changed) partial = pcec.text()
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
            // Only frames wholly covered by real audio are decoded; the padding is not.
            val realFrames = (received / config.samplesPerFrame).toInt()
            while (buffered > 0) {
                val frames = (realFrames - decoder.framesDecoded).coerceIn(0, tier.commitFrames)
                if (frames == 0) break // the rest is padding
                if (buffered < tier.windowSamples) buffer.fill(0f, buffered, tier.windowSamples)
                encodeWindow(frames)
                consume(minOf(tier.hopSamples, buffered))
            }
            decoder.finish(pcec::add)
            val text = pcec.text()
            resetUtterance()
            text
        }
    }

    /** Discards the current utterance; the loaded program is kept. */
    fun reset() = synchronized(lock) {
        ensureOpen()
        resetUtterance()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            program.close()
        }
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
        received = 0
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

    /**
     * Encodes `buffer[0 until windowSamples]` and decodes the first [frames] committed frames;
     * returns true when a token was handed to PCEC.
     */
    private fun encodeWindow(frames: Int = tier.commitFrames): Boolean {
        val window = buffer.copyOf(tier.windowSamples)
        val out = program.encoder(window, filterCache, attnCache)
        filterCache = out.filterCache
        attnCache = out.attnCache
        return decoder.accept(out.logits, out.hidden, frames, pcec::add)
    }

    companion object {
        /**
         * Default XNNPACK thread count: four threads, the performance-core count of the
         * S23 Ultra and S21 class devices this engine targets. ExecuTorch has one process-wide
         * pool, so every ExecuTorch model open at the same time must use the same count
         * (`:core:executorch` rejects a conflicting load).
         */
        const val DEFAULT_THREADS = 4

        /**
         * Opens the [size] export in [modelDir] at [tierName] (default: the export's default
         * tier) with ExecuTorch through `:core:executorch`.
         *
         * Before the runtime sees the program, the config files are checked against each other
         * and against [size], and the method signatures derived from them are compared with the
         * ones the `.pte` declares ([PteMethodTable]): the 1.5.1 runtime aborts the process on a
         * shape mismatch, so a program from another size or revision must fail here, typed.
         * Each method then runs once on silence, which also checks every output shape.
         *
         * @throws Asr4AllConfigException if the files disagree with each other or with [size].
         * @throws Asr4AllShapeException if a method returns outputs of another layout.
         * @throws Asr4AllRuntimeException if the runtime cannot load or run the program.
         */
        fun open(
            modelDir: File,
            size: Asr4AllSize,
            tierName: String? = null,
            threads: Int = DEFAULT_THREADS,
            loader: Asr4AllBackendLoader = ExecuTorchBackend,
        ): Asr4AllEngine = open(modelDir, size, tierName, threads, loader, PteMethodTable::read)

        internal fun open(
            modelDir: File,
            size: Asr4AllSize,
            tierName: String?,
            threads: Int,
            loader: Asr4AllBackendLoader,
            declaredMethods: (File) -> Map<String, PteMethodInfo>,
        ): Asr4AllEngine {
            require(threads >= 1) { "threads must be >= 1" }
            val config = Asr4AllModelConfig.load(modelDir, size)
            val tier = config.tier(tierName)
            if (!config.encoderFile.isFile) throw Asr4AllConfigException("missing ${config.encoderFile.path}")
            val signatures = Asr4AllSignatures(config, tier)
            signatures.checkDeclared(declaredMethods(config.encoderFile))
            val backend = try {
                loader.load(config.encoderFile, threads)
            } catch (e: RuntimeException) {
                throw Asr4AllRuntimeException("cannot load ${config.encoderFile.name}: ${e.message}", e)
            }
            val program = Asr4AllProgram(backend, config, signatures)
            try {
                program.requireMethods()
                runOnSilence(program, config, tier)
            } catch (e: Throwable) {
                program.close()
                throw e
            }
            return Asr4AllEngine(config, tier, program)
        }

        private fun runOnSilence(program: Asr4AllProgram, config: Asr4AllModelConfig, tier: Asr4AllTier) {
            program.encoder(FloatArray(tier.windowSamples), program.initialFilterCache(), program.initialAttnCache())
            val rung = tier.pcecRung
            val pad = PcecInputs(
                LongArray(rung) { config.pcec.padInputId.toLong() },
                LongArray(rung),
                FloatArray(rung * config.hiddenDim),
                FloatArray(rung),
            )
            val stream = program.pcecStream(pad, program.initialPcecState(), 0)
            program.pcecFlush(pad, 0, stream.state, stream.seen)
        }
    }
}
