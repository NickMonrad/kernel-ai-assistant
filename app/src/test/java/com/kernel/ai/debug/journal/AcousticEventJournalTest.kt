package com.kernel.ai.debug.journal

import com.kernel.ai.core.voice.AcousticEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class AcousticEventJournalTest {

    private fun event(
        sequence: Long,
        type: String = "TEST",
        generationId: Long = 0,
        sessionId: Long = 0,
        metadata: Map<String, String> = emptyMap(),
    ): AcousticEvent = AcousticEvent(
        sequence = sequence,
        monotonicMs = sequence * 1000L,
        wallClockMs = sequence * 2000L,
        type = type,
        generationId = generationId,
        sessionId = sessionId,
        metadata = metadata,
    )

    @Test
    fun `snapshot is ordered and uses strict greater-than filtering`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(3, "C"))
        journal.record(event(1, "A"))
        journal.record(event(2, "B"))

        val snapshot = journal.snapshotSince(1)

        assertEquals(listOf(2L, 3L), snapshot.events.map { it.sequence })
        assertEquals(1L, snapshot.lowestSequence)
        assertEquals(3L, snapshot.highestSequence)
        assertFalse(snapshot.overflowed)
        assertTrue(journal.snapshotSince(3).events.isEmpty())
    }

    @Test
    fun `empty snapshot has explicit zero bounds`() {
        val snapshot = AcousticEventJournal(journalCapacity = 16).snapshotSince(0)

        assertEquals(0L, snapshot.lowestSequence)
        assertEquals(0L, snapshot.highestSequence)
        assertFalse(snapshot.overflowed)
        assertTrue(snapshot.events.isEmpty())
    }

    @Test
    fun `overflow reports retained bounds and evicts oldest arrivals`() {
        val journal = AcousticEventJournal(journalCapacity = 4)
        for (sequence in 1L..6L) journal.record(event(sequence, "E$sequence"))

        val snapshot = journal.snapshotSince(0)

        assertEquals(listOf(3L, 4L, 5L, 6L), snapshot.events.map { it.sequence })
        assertEquals(3L, snapshot.lowestSequence)
        assertEquals(6L, snapshot.highestSequence)
        assertTrue(snapshot.overflowed)
        assertEquals(6L, journal.currentSequence)
    }

    @Test
    fun `correlation identifiers are preserved without journal allocation`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, generationId = 41, sessionId = 73))

        val recorded = journal.snapshotSince(0).events.single()

        assertEquals(41L, recorded.generationId)
        assertEquals(73L, recorded.sessionId)
    }

    @Test
    fun `wait returns an already recorded event`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        journal.record(event(1, "PRE"))
        journal.record(event(2, "TARGET"))

        val result = journal.waitForEvent(0, "TARGET", 500)

        assertTrue(result is AcousticEventWaitResult.Found)
        assertEquals(2L, (result as AcousticEventWaitResult.Found).event.sequence)
    }

    @Test
    fun `wait observes an event recorded after subscription`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val writer = Thread {
            Thread.sleep(50)
            journal.record(event(2, "TARGET"))
        }
        writer.start()

        val result = journal.waitForEvent(1, "TARGET", 2_000)

        writer.join()
        assertEquals(
            "TARGET",
            (result as AcousticEventWaitResult.Found).event.type,
        )
    }

    @Test
    fun `wait timeout is bounded and returns null`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val elapsed = measureTimeMillis {
            assertEquals(
                AcousticEventWaitResult.TimedOut,
                journal.waitForEvent(0, "NEVER", 50),
            )
        }

        assertTrue(elapsed >= 40, "wait returned too early: ${elapsed}ms")
        assertTrue(elapsed < 1_000, "wait exceeded bounded tolerance: ${elapsed}ms")
    }

    @Test
    fun `wait can be interrupted`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val waiter = Thread {
            runCatching { journal.waitForEvent(0, "NEVER", 10_000) }
        }
        waiter.start()
        Thread.sleep(100)

        waiter.interrupt()
        waiter.join(2_000)

        assertFalse(waiter.isAlive)
    }

    @Test
    fun `two waits for different events complete independently`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val first = AtomicReference<AcousticEventWaitResult>()
        val second = AtomicReference<AcousticEventWaitResult>()
        val ready = CountDownLatch(2)
        val firstWaiter = Thread {
            ready.countDown()
            first.set(journal.waitForEvent(0, "FIRST", 2_000))
        }
        val secondWaiter = Thread {
            ready.countDown()
            second.set(journal.waitForEvent(0, "SECOND", 2_000))
        }
        firstWaiter.start()
        secondWaiter.start()
        ready.await()

        journal.record(event(1, "SECOND"))
        journal.record(event(2, "FIRST"))
        firstWaiter.join(2_000)
        secondWaiter.join(2_000)

        assertEquals(
            "FIRST",
            (first.get() as AcousticEventWaitResult.Found).event.type,
        )
        assertEquals(
            "SECOND",
            (second.get() as AcousticEventWaitResult.Found).event.type,
        )
    }

    @Test
    fun `snapshot and sequence remain responsive during active waits`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val cancellation = AcousticEventWaitCancellation()
        val waiter = Thread {
            journal.waitForEvent(0, "NEVER", 10_000, cancellation)
        }
        waiter.start()
        Thread.sleep(50)

        journal.record(event(1, "OTHER"))
        assertEquals(1L, journal.currentSequence)
        assertEquals(listOf("OTHER"), journal.snapshotSince(0).events.map { it.type })
        assertTrue(journal.cancelWait(cancellation))
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
    }

    @Test
    fun `cancellation wakes wait promptly and wins before a later event`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        val cancellation = AcousticEventWaitCancellation()
        val result = AtomicReference<AcousticEventWaitResult>()
        val waiter = Thread {
            result.set(journal.waitForEvent(0, "TARGET", 10_000, cancellation))
        }
        waiter.start()
        Thread.sleep(50)

        val elapsed = measureTimeMillis {
            assertTrue(journal.cancelWait(cancellation))
            journal.record(event(1, "TARGET"))
            waiter.join(2_000)
        }

        assertEquals(AcousticEventWaitResult.Cancelled, result.get())
        assertTrue(elapsed < 1_000, "cancellation was not prompt: ${elapsed}ms")
        assertFalse(journal.cancelWait(cancellation))
    }

    @Test
    fun `request keyed cancellation wins deterministically over a concurrent event`() {
        val registration = AcousticEventWaitRegistration()
        val found = AcousticEventWaitResult.Found(event(1, "TARGET"))

        assertTrue(registration.cancel())
        assertEquals(AcousticEventWaitResult.Cancelled, registration.resolve(found))
        assertFalse(registration.cancel())
    }

    @Test
    fun `completed wait rejects late cancellation`() {
        val registration = AcousticEventWaitRegistration()
        val found = AcousticEventWaitResult.Found(event(1, "TARGET"))

        assertEquals(found, registration.resolve(found))
        assertFalse(registration.cancel())
    }

    @Test
    fun `contract rejects malformed arguments and timeout bounds`() {
        assertEquals(
            TargetEventJournalContract.ERROR_MISSING_REQUEST_ID,
            TargetEventJournalContract.requestIdError(null),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_INVALID_REQUEST_ID,
            TargetEventJournalContract.requestIdError("bad id"),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_NEGATIVE_SINCE_SEQUENCE,
            TargetEventJournalContract.sinceSequenceError(-1),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_MISSING_EVENT_TYPE,
            TargetEventJournalContract.eventTypeError(null),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_INVALID_EVENT_TYPE,
            TargetEventJournalContract.eventTypeError("NOT_AN_EVENT"),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_INVALID_TIMEOUT,
            TargetEventJournalContract.timeoutError(
                TargetEventJournalContract.MIN_TIMEOUT_MS - 1,
            ),
        )
        assertEquals(
            TargetEventJournalContract.ERROR_INVALID_TIMEOUT,
            TargetEventJournalContract.timeoutError(
                TargetEventJournalContract.MAX_TIMEOUT_MS + 1,
            ),
        )
        assertNull(
            TargetEventJournalContract.timeoutError(
                TargetEventJournalContract.MAX_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `wait status exposes registration before source-side work can start`() {
        val journal = AcousticEventJournal(journalCapacity = 16)
        TargetEventJournalEndpoint.replaceJournalForTest(journal)
        val response = AtomicReference<TargetEventJournalResponse>()
        val waiter = Thread {
            response.set(
                TargetEventJournalEndpoint.waitForEvent(
                    requestId = "arm-probe",
                    sinceSequence = 0,
                    eventType = "STT_READY",
                    timeoutMs = 10_000,
                ),
            )
        }
        waiter.start()
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (TargetEventJournalEndpoint.activeWaitCountForTest() == 0 &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.sleep(5)
        }

        assertEquals(
            TargetEventJournalResponse(
                TargetEventJournalContract.RESULT_OK,
                "active:arm-probe",
            ),
            TargetEventJournalEndpoint.waitStatus("arm-probe"),
        )
        assertEquals(
            TargetEventJournalContract.RESULT_CANCELLED,
            TargetEventJournalEndpoint.cancelWait("arm-probe").code,
        )
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertEquals(TargetEventJournalContract.RESULT_CANCELLED, response.get().code)
        assertEquals(
            TargetEventJournalContract.ERROR_UNKNOWN_REQUEST_ID,
            TargetEventJournalEndpoint.waitStatus("arm-probe").data,
        )
    }

    @Test
    fun `concurrent record and snapshot preserve a coherent sequence envelope`() {
        val journal = AcousticEventJournal(journalCapacity = 512)
        val sequence = AtomicLong()
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writers = List(4) {
            Thread {
                start.await()
                repeat(100) {
                    runCatching {
                        journal.record(event(sequence.incrementAndGet()))
                    }.onFailure(failures::add)
                }
            }
        }
        val reader = Thread {
            start.await()
            while (writers.any(Thread::isAlive)) {
                runCatching {
                    val snapshot = journal.snapshotSince(0)
                    assertTrue(snapshot.events.zipWithNext().all { (a, b) ->
                        a.sequence < b.sequence
                    })
                    assertTrue(snapshot.highestSequence >= snapshot.events.lastOrNull()?.sequence ?: 0L)
                }.onFailure(failures::add)
            }
        }
        writers.forEach(Thread::start)
        reader.start()
        start.countDown()
        writers.forEach { it.join(2_000) }
        reader.join(2_000)

        val snapshot = journal.snapshotSince(0)
        assertTrue(failures.isEmpty(), failures.joinToString())
        assertEquals(400, snapshot.events.size)
        assertEquals(400L, snapshot.highestSequence)
        assertEquals((1L..400L).toList(), snapshot.events.map { it.sequence })
    }

    @Test
    fun `snapshot JSON has exact valid envelope and privacy-safe event fields`() {
        val snapshot = AcousticJournalSnapshot(
            lowestSequence = 7,
            highestSequence = 9,
            overflowed = true,
            events = listOf(
                event(
                    sequence = 9,
                    type = "STT_PARTIAL",
                    generationId = 11,
                    sessionId = 13,
                    metadata = linkedMapOf("length" to "42", "escaped" to "\"\n"),
                ),
            ),
        )

        val json = AcousticJournalJson.serialiseSnapshot(snapshot)

        assertEquals(
            """{"lowestSequence":7,"highestSequence":9,"overflowed":true,"events":[{"s":9,"m":9000,"w":18000,"t":"STT_PARTIAL","g":11,"i":13,"d":{"length":"42","escaped":"\"\n"}}]}""",
            json,
        )
        assertFalse(json.contains("text"))
        assertFalse(json.contains("audio"))
        assertFalse(json.contains("path"))
    }

    @Test
    fun `recording remains bounded at default capacity`() {
        val journal = AcousticEventJournal()
        for (sequence in 1L..300L) journal.record(event(sequence))

        assertEquals(256, journal.snapshotSince(0).events.size)
        assertEquals(256, AcousticEventJournal.DEFAULT_CAPACITY)
        assertTrue(journal.overflowed)
    }
}
