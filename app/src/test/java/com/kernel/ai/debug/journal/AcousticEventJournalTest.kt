package com.kernel.ai.debug.journal

import com.kernel.ai.core.voice.AcousticEvent
import com.kernel.ai.core.voice.AcousticEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

class AcousticEventJournalTest {

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun event(sequence: Long, type: String = "TEST"): AcousticEvent =
        AcousticEvent(
            sequence = sequence,
            monotonicMs = sequence * 1000L,
            wallClockMs = 0L,
            type = type,
        )

    private var testSequenceCounter = 1L

    private fun AcousticEventJournal.recordN(n: Int, type: String = "TEST") {
        for (i in 1..n) {
            record(event(sequence = testSequenceCounter++, type = type))
        }
    }

    // ── Ordered recording ───────────────────────────────────────────────────────

    @Test
    fun `ordered recording preserves insertion order`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "A"))
        journal.record(event(2, "B"))
        journal.record(event(3, "C"))

        val snapshot = journal.snapshotSince(0)
        assertEquals(3, snapshot.size)
        assertEquals("A", snapshot[0].type)
        assertEquals("B", snapshot[1].type)
        assertEquals("C", snapshot[2].type)
    }

    // ── Monotonic sequence numbers ──────────────────────────────────────────────

    @Test
    fun `sequence numbers increase monotonically`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        for (i in 1..100) {
            journal.record(event(i.toLong()))
        }
        assertEquals(100, journal.currentSequence)
    }

    // ── Monotonic timestamps ────────────────────────────────────────────────────

    @Test
    fun `timestamps increase monotonically`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1))
        Thread.sleep(1)
        journal.record(event(2))
        Thread.sleep(1)
        journal.record(event(3))

        val snapshot = journal.snapshotSince(0)
        assertTrue(snapshot[0].monotonicMs <= snapshot[1].monotonicMs)
        assertTrue(snapshot[1].monotonicMs <= snapshot[2].monotonicMs)
    }

    // ── Generation and session correlation ──────────────────────────────────────

    @Test
    fun `generation and session correlation identifiers are recorded`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val genId = journal.allocateGenerationId()
        val sessId = journal.allocateSessionId()
        journal.record(event(1).copy(generationId = genId, sessionId = sessId))

        val snapshot = journal.snapshotSince(0)
        assertEquals(1, snapshot.size)
        assertEquals(genId, snapshot[0].generationId)
        assertEquals(sessId, snapshot[0].sessionId)
    }

    // ── Bounded capacity ────────────────────────────────────────────────────────

    @Test
    fun `journal has bounded capacity`() {
        val journal = AcousticEventJournal(journalCapacity = 4)
        journal.recordN(6)  // 6 events into capacity 4

        assertEquals(4, journal.snapshotSince(0).size)
        assertTrue(journal.overflowed)
    }

    // ── Oldest-event eviction ───────────────────────────────────────────────────

    @Test
    fun `oldest event is evicted when capacity exceeded`() {
        val journal = AcousticEventJournal(journalCapacity = 4)
        // Fill with events 1-4
        for (i in 1..4) journal.record(event(i.toLong(), "E$i"))
        // Add event 5 — 1 is evicted
        journal.record(event(5L, "E5"))

        val snapshot = journal.snapshotSince(0)
        assertEquals(4, snapshot.size)
        // E1 should be gone; oldest is now E2
        assertEquals("E2", snapshot[0].type)
        assertEquals("E5", snapshot[3].type)
    }

    // ── Explicit overflow/truncation ────────────────────────────────────────────

    @Test
    fun `overflow flag is set when events are evicted`() {
        val journal = AcousticEventJournal(journalCapacity = 2)
        assertFalse(journal.overflowed)
        journal.record(event(1))
        journal.record(event(2))
        assertFalse(journal.overflowed)
        journal.record(event(3)) // triggers eviction
        assertTrue(journal.overflowed)
    }

    // ── Snapshot-since semantics ────────────────────────────────────────────────

    @Test
    fun `snapshotSince returns only events with sequence greater than`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "A"))
        journal.record(event(2, "B"))
        journal.record(event(3, "C"))
        journal.record(event(4, "D"))

        val since2 = journal.snapshotSince(2)
        assertEquals(2, since2.size)
        assertEquals("C", since2[0].type)
        assertEquals("D", since2[1].type)
    }

    @Test
    fun `snapshotSince returns empty when since equals current`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "A"))
        journal.record(event(2, "B"))

        val snapshot = journal.snapshotSince(2)
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun `snapshotSince returns empty for empty journal`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        assertTrue(journal.snapshotSince(0).isEmpty())
    }

    @Test
    fun `snapshotSince handles overflow correctly`() {
        val journal = AcousticEventJournal(journalCapacity = 4)
        for (i in 1..6) journal.record(event(i.toLong(), "E$i"))

        // After overflow: events 3,4,5,6 are in the journal (capacity 4)
        val snapshot = journal.snapshotSince(0)
        assertEquals(4, snapshot.size)
        assertEquals("E3", snapshot[0].type)

        // snapshotSince(4) should return E5, E6
        val since4 = journal.snapshotSince(4)
        assertEquals(2, since4.size)
        assertEquals("E5", since4[0].type)
        assertEquals("E6", since4[1].type)
    }

    // ── Wait before event ───────────────────────────────────────────────────────

    @Test
    fun `waitForEvent returns event that occurs after wait begins`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))

        val waiter = Thread {
            Thread.sleep(50)
            journal.record(event(2, "TARGET"))
        }
        waiter.start()

        val result = journal.waitForEvent(sinceSequence = 1, eventType = "TARGET", timeoutMs = 2000)
        assertNotNull(result)
        assertEquals("TARGET", result?.type)
        waiter.join()
    }

    // ── Event before wait / late subscriber ─────────────────────────────────────

    @Test
    fun `waitForEvent returns immediately when event already present`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))
        journal.record(event(2, "TARGET"))

        val result = journal.waitForEvent(sinceSequence = 0, eventType = "TARGET", timeoutMs = 500)
        assertNotNull(result)
        assertEquals("TARGET", result?.type)
    }

    // ── Timeout ─────────────────────────────────────────────────────────────────

    @Test
    fun `waitForEvent returns null on timeout`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))

        val start = System.nanoTime()
        val result = journal.waitForEvent(sinceSequence = 1, eventType = "NEVER", timeoutMs = 200)
        val elapsed = measureNanoTime { } // not used for assertion, just reference
        assertNull(result)
    }

    @Test
    fun `waitForEvent respects timeout lower bound`() {
        // Verify that a very short timeout still returns null when event never arrives
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))

        val start = System.nanoTime()
        val result = journal.waitForEvent(sinceSequence = 1, eventType = "NEVER", timeoutMs = 50)
        assertNull(result)
    }

    // ── Cancellation ────────────────────────────────────────────────────────────

    @Test
    fun `waitForEvent can be interrupted`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))

        val waiter = Thread {
            journal.waitForEvent(sinceSequence = 1, eventType = "NEVER", timeoutMs = 10_000)
        }
        waiter.start()
        Thread.sleep(100) // let waiter enter the wait loop
        waiter.interrupt()
        waiter.join(2000)
        assertFalse(waiter.isAlive)
    }

    // ── Concurrent waits ────────────────────────────────────────────────────────

    @Test
    fun `multiple concurrent waits both resolve on matching event`() {
        val journal = AcousticEventJournal(journalCapacity = 16)

        val results = arrayOfNulls<AcousticEvent?>(2)
        val threads = List(2) { idx ->
            Thread {
                results[idx] = journal.waitForEvent(
                    sinceSequence = 0, eventType = "TARGET", timeoutMs = 2000,
                )
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(100)

        journal.record(event(1, "TARGET"))
        threads.forEach { it.join(2000) }

        assertNotNull(results[0])
        assertNotNull(results[1])
    }

    // ── Unsupported event filters ───────────────────────────────────────────────

    @Test
    fun `waitForEvent with non-matching event type returns null on timeout`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "A"))
        journal.record(event(2, "B"))

        val result = journal.waitForEvent(sinceSequence = 0, eventType = "C", timeoutMs = 100)
        assertNull(result)
    }

    // ── Privacy-safe serialisation ─────────────────────────────────────────────

    @Test
    fun `event metadata does not contain private fields`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(
            event(1, "STT_PARTIAL").copy(
                metadata = mapOf("length" to "42"),
            )
        )

        val snapshot = journal.snapshotSince(0)
        assertEquals(1, snapshot.size)
        val meta = snapshot[0].metadata
        assertEquals("42", meta["length"])
        // No transcript text, audio, or private fields
        assertNull(meta["text"])
        assertNull(meta["audio"])
        assertNull(meta["path"])
        assertNull(meta["selector"])
    }

    // ── Reset or boundary semantics ─────────────────────────────────────────────

    @Test
    fun `empty journal after creation`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        assertEquals(0, journal.currentSequence)
        assertFalse(journal.overflowed)
        assertTrue(journal.snapshotSince(0).isEmpty())
    }

    // ── Performance: bounded capacity does not degrade ──────────────────────────

    @Test
    fun `recording at capacity does not throw`() {
        val journal = AcousticEventJournal(journalCapacity = 256)
        // Fill and overflow
        for (i in 1..300) journal.record(event(i.toLong()))
        assertTrue(journal.overflowed)
        assertEquals(256, journal.snapshotSince(0).size)
    }

    // ── Default capacity ────────────────────────────────────────────────────────

    @Test
    fun `default capacity is 256`() {
        assertEquals(256, AcousticEventJournal.DEFAULT_CAPACITY)
    }
}
