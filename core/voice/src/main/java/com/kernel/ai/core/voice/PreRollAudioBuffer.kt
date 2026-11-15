package com.kernel.ai.core.voice

/**
 * Single-producer, single-consumer PCM queue that lets a Sherpa capture start at request time,
 * before the recognizer has loaded (#1850).
 *
 * The producer (the AudioRecord reader) calls [append] for every chunk it reads from the moment
 * capture opens. Until a recognizer loop calls [attach], the queue keeps only the newest
 * [maxPreRollSamples] samples, so an early start never grows without bound. [attach] hands back
 * everything still queued, in capture order, and switches the queue to live mode, where
 * [take] blocks until the next chunk or [close]. The consumer therefore sees the pre-roll and
 * the live audio as one ordered stream with no gap and no duplicate.
 */
internal class PreRollAudioBuffer(private val maxPreRollSamples: Int) {

    init {
        require(maxPreRollSamples > 0) { "maxPreRollSamples must be positive" }
    }

    private val lock = Object()
    private val chunks = ArrayDeque<ShortArray>()
    private var queuedSamples = 0
    private var attached = false
    private var closed = false
    private var droppedSamples = 0L

    /** Samples discarded from the pre-roll window before [attach]; 0 when it never overflowed. */
    val trimmedSamples: Long get() = synchronized(lock) { droppedSamples }

    /** Appends the first [count] samples of [pcm]. Chunks after [close] are ignored. */
    fun append(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            if (closed) return
            chunks.addLast(pcm.copyOf(count))
            queuedSamples += count
            if (!attached) {
                // Pre-roll only: drop the oldest chunks, keeping the newest maxPreRollSamples.
                while (queuedSamples > maxPreRollSamples && chunks.size > 1) {
                    val dropped = chunks.removeFirst().size
                    queuedSamples -= dropped
                    droppedSamples += dropped
                }
            }
            lock.notifyAll()
        }
    }

    /**
     * Switches to live mode and returns every queued chunk in capture order. Call once, from the
     * recognizer loop, after the recognizer stream exists.
     */
    fun attach(): List<ShortArray> = synchronized(lock) {
        attached = true
        val pre = chunks.toList()
        chunks.clear()
        queuedSamples = 0
        pre
    }

    /**
     * Blocks until a live chunk is queued and returns it, or returns null once [close] has been
     * called and nothing is left. Must only be called after [attach].
     */
    fun take(): ShortArray? = synchronized(lock) {
        while (chunks.isEmpty() && !closed) lock.wait()
        if (chunks.isEmpty()) null else chunks.removeFirst()
    }

    /** Ends the stream. Pending [take] calls return the remaining chunks, then null. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    companion object {
        /** 15 s at 16 kHz (480 KB as 16-bit PCM): covers the worst-case recognizer load so speech at mic-open is never dropped (#1850). */
        const val PRE_ROLL_SAMPLES = 15 * 16_000
    }
}

/**
 * Next chunk for a recognizer loop: the pre-roll chunks first, in capture order, then live audio.
 * Both Sherpa loops take every chunk through this function, so the ordering tested in
 * `PreRollAudioBufferTest` is the ordering the recognizer sees. Returns null at end of capture.
 */
internal fun nextCaptureChunk(preRoll: ArrayDeque<ShortArray>, buffer: PreRollAudioBuffer): ShortArray? =
    if (preRoll.isNotEmpty()) preRoll.removeFirst() else buffer.take()
