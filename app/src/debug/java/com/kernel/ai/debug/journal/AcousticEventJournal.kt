package com.kernel.ai.debug.journal

import com.kernel.ai.core.voice.AcousticEvent
import com.kernel.ai.core.voice.AcousticEventRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class AcousticJournalSnapshot(
    val lowestSequence: Long,
    val highestSequence: Long,
    val overflowed: Boolean,
    val events: List<AcousticEvent>,
)

sealed interface AcousticEventWaitResult {
    data class Found(val event: AcousticEvent) : AcousticEventWaitResult
    object TimedOut : AcousticEventWaitResult
    object Cancelled : AcousticEventWaitResult
}

class AcousticEventWaitCancellation {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    internal fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}

internal class AcousticEventWaitRegistration(
    val cancellation: AcousticEventWaitCancellation = AcousticEventWaitCancellation(),
) {
    private val state = AtomicReference(State.ACTIVE)

    fun cancel(): Boolean = state.compareAndSet(State.ACTIVE, State.CANCELLED)

    fun resolve(result: AcousticEventWaitResult): AcousticEventWaitResult =
        if (state.compareAndSet(State.ACTIVE, State.COMPLETED)) {
            result
        } else if (state.get() == State.CANCELLED) {
            AcousticEventWaitResult.Cancelled
        } else {
            result
        }

    private enum class State {
        ACTIVE,
        CANCELLED,
        COMPLETED,
    }
}

/**
 * Bounded ring-buffer event journal for structured target-side diagnostics.
 *
 * Capacity of 256 covers the expected ~20–30 events per trial across
 * multiple trials (recently armed, 30s idle, 2min idle) plus diagnostic
 * overhead from service/error events, without unbounded growth.
 *
 * **Thread safety:** all mutating operations use [synchronized] on the
 * journal instance.  Recording is low-volume and transition-based, never
 * per-frame, so contention is negligible.
 *
 * **Overflow:** when the journal is full the oldest event is silently
 * replaced and [overflowed] is set to true.
 */

class AcousticEventJournal(
    val journalCapacity: Int = DEFAULT_CAPACITY,
) : AcousticEventRecorder {

    private val ring: Array<AcousticEvent?> = arrayOfNulls<AcousticEvent>(journalCapacity)
    private var writeIndex = 0
    private var count = 0
    private val _overflowed = AtomicBoolean(false)
    private val lock = Any()

    /** True if at least one event was evicted due to capacity. */
    val overflowed: Boolean get() = _overflowed.get()

    private var localMaxSequence: Long = 0
    val currentSequence: Long get() = synchronized(lock) { localMaxSequence }

    override fun record(event: AcousticEvent) {
        synchronized(lock) {
            val idx = if (count < journalCapacity) {
                count++
                writeIndex
            } else {
                _overflowed.set(true)
                writeIndex
            }
            ring[idx] = event
            writeIndex = (idx + 1) % journalCapacity
            if (event.sequence > localMaxSequence) localMaxSequence = event.sequence
            (lock as java.lang.Object).notifyAll()
        }
    }

    fun snapshotSince(sinceSequence: Long): AcousticJournalSnapshot = synchronized(lock) {
        val ordered = reconstructOrdered()
        AcousticJournalSnapshot(
            lowestSequence = ordered.firstOrNull()?.sequence ?: 0L,
            highestSequence = localMaxSequence,
            overflowed = _overflowed.get(),
            events = ordered.filter { it.sequence > sinceSequence },
        )
    }

    fun waitForEvent(
        sinceSequence: Long,
        eventType: String,
        timeoutMs: Long,
        cancellation: AcousticEventWaitCancellation = AcousticEventWaitCancellation(),
    ): AcousticEventWaitResult {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        synchronized(lock) {
            while (true) {
                if (cancellation.isCancelled) return AcousticEventWaitResult.Cancelled
                reconstructOrdered()
                    .firstOrNull { it.sequence > sinceSequence && it.type == eventType }
                    ?.let { return AcousticEventWaitResult.Found(it) }
                val remaining = deadline - System.nanoTime() / 1_000_000
                if (remaining <= 0) return AcousticEventWaitResult.TimedOut
                (lock as java.lang.Object).wait(remaining)
            }
        }
    }

    fun cancelWait(cancellation: AcousticEventWaitCancellation): Boolean {
        synchronized(lock) {
            val changed = cancellation.cancel()
            if (changed) (lock as java.lang.Object).notifyAll()
            return changed
        }
    }

    private fun reconstructOrdered(): List<AcousticEvent> {
        if (count == 0) return emptyList()
        val result = ArrayList<AcousticEvent>(count)
        if (count < journalCapacity) {
            for (i in 0 until count) {
                ring[i]?.let { result.add(it) }
            }
        } else {
            for (i in writeIndex until journalCapacity) {
                ring[i]?.let { result.add(it) }
            }
            for (i in 0 until writeIndex) {
                ring[i]?.let { result.add(it) }
            }
        }
        result.sortBy(AcousticEvent::sequence)
        return result
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

