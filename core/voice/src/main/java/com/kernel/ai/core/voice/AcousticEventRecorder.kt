package com.kernel.ai.core.voice

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Narrow recorder interface for production-side journal hooks.
 *
 * Production code calls [record] at well-defined state transitions.  The
 * default [NoOp] implementation is used in release builds or when no debug
 * journal has been installed.  The actual bounded journal, storage and
 * machine-interface components live in the debug source set and are wired
 * through [AcousticJournalBridge].
 *
 * Recordings are low-volume and transition-based — never per-frame or
 * per-inference-step.
 */
fun interface AcousticEventRecorder {
    fun record(event: AcousticEvent)

    companion object {
        val NoOp: AcousticEventRecorder = AcousticEventRecorder { /* no-op */ }
    }
}

/**
 * Static bridge that connects production-side hooks to the debug-only journal.
 *
 * In debug builds [install] is called early (e.g. from the debug receiver)
 * to wire the real bounded journal.  In release builds the [NoOp] default
 * remains active — the bridge adds no detectable surface or allocation path.
 */
object AcousticJournalBridge {
    @Volatile
    private var recorder: AcousticEventRecorder = AcousticEventRecorder.NoOp

    private val generationCounter = AtomicLong(0)
    private val sessionCounter = AtomicLong(0)

    /** Allocates the next monotonically increasing detector generation ID. */
    fun allocateGenerationId(): Long = generationCounter.incrementAndGet()

    /** Allocates the next monotonically increasing voice session ID. */
    fun allocateSessionId(): Long = sessionCounter.incrementAndGet()

    fun install(recorder: AcousticEventRecorder) {
        this.recorder = recorder
    }

    fun current(): AcousticEventRecorder = recorder

    /**
     * Convenience factory: creates an [AcousticEvent] with the next
     * available sequence number from a supplied [SequenceProvider] and
     * records it through [current].
     */
    fun record(
        type: String,
        generationId: Long = 0L,
        sessionId: Long = 0L,
        sequenceProvider: SequenceProvider = AtomicSequenceProvider,
        metadata: () -> Map<String, String> = EMPTY_METADATA,
    ) {
        val target = recorder
        if (target === AcousticEventRecorder.NoOp) return
        target.record(
            AcousticEvent(
                sequence = sequenceProvider.nextSequence(),
                monotonicMs = SystemClock.elapsedRealtime(),
                wallClockMs = System.currentTimeMillis(),
                type = type,
                generationId = generationId,
                sessionId = sessionId,
                metadata = metadata(),
            )
        )
    }

    private val EMPTY_METADATA: () -> Map<String, String> = { emptyMap() }
}

/**
 * Provides monotonically increasing sequence numbers for [AcousticEvent].
 *
 * The [AtomicSequenceProvider] default is safe for concurrent use across
 * multiple threads (detector thread, main thread, broadcast receiver).
 */
fun interface SequenceProvider {
    fun nextSequence(): Long
}

object AtomicSequenceProvider : SequenceProvider {
    private val counter = AtomicLong(0)
    override fun nextSequence(): Long = counter.incrementAndGet()
}
