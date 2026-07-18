package com.kernel.ai.debug.journal

import com.kernel.ai.core.voice.AcousticEvent
import com.kernel.ai.core.voice.AcousticEventType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TargetEventJournalEndpointTest {
    private lateinit var journal: AcousticEventJournal
    private val callers = Executors.newFixedThreadPool(2)

    @BeforeEach
    fun setUp() {
        journal = AcousticEventJournal()
        TargetEventJournalEndpoint.replaceJournalForTest(journal)
    }

    @AfterEach
    fun tearDown() {
        callers.shutdownNow()
    }

    @Test
    fun `independent waits leave control calls responsive and cancel exactly one request`() {
        val cancelledWait = callers.submit<TargetEventJournalResponse> {
            TargetEventJournalEndpoint.waitForEvent(
                requestId = "cancel-me",
                sinceSequence = 0L,
                eventType = AcousticEventType.DETECTOR_REARMED,
                timeoutMs = 5_000L,
            )
        }
        val foundWait = callers.submit<TargetEventJournalResponse> {
            TargetEventJournalEndpoint.waitForEvent(
                requestId = "find-me",
                sinceSequence = 0L,
                eventType = AcousticEventType.STT_READY,
                timeoutMs = 5_000L,
            )
        }

        assertEquals("0", TargetEventJournalEndpoint.sequence().data)
        assertEquals(TargetEventJournalContract.RESULT_OK, TargetEventJournalEndpoint.snapshot(0L).code)

        val cancellation = awaitSuccessfulCancellation("cancel-me")
        assertEquals("cancelled:cancel-me", cancellation.data)

        journal.record(
            AcousticEvent(
                sequence = 1L,
                monotonicMs = 10L,
                type = AcousticEventType.STT_READY,
                generationId = 2L,
                sessionId = 3L,
            ),
        )

        assertEquals(
            TargetEventJournalContract.RESULT_CANCELLED,
            cancelledWait.get(1L, TimeUnit.SECONDS).code,
        )
        val found = foundWait.get(1L, TimeUnit.SECONDS)
        assertEquals(TargetEventJournalContract.RESULT_OK, found.code)
        assertTrue(found.data.contains("\"t\":\"STT_READY\""))
        assertEquals(
            TargetEventJournalContract.ERROR_UNKNOWN_REQUEST_ID,
            TargetEventJournalEndpoint.cancelWait("cancel-me").data,
        )
    }

    @Test
    fun `fifth wait fails fast without consuming control capacity`() {
        val saturatedCallers = Executors.newFixedThreadPool(4)
        val requestIds = (1..4).map { "wait-$it" }
        try {
            val waits = requestIds.map { requestId ->
                saturatedCallers.submit<TargetEventJournalResponse> {
                    TargetEventJournalEndpoint.waitForEvent(
                        requestId = requestId,
                        sinceSequence = 0L,
                        eventType = AcousticEventType.STT_READY,
                        timeoutMs = 5_000L,
                    )
                }
            }
            awaitActiveWaitCount(4)

            val busy = TargetEventJournalEndpoint.waitForEvent(
                requestId = "wait-5",
                sinceSequence = 0L,
                eventType = AcousticEventType.STT_READY,
                timeoutMs = 5_000L,
            )

            assertEquals(TargetEventJournalContract.RESULT_ERROR, busy.code)
            assertEquals(TargetEventJournalContract.ERROR_ENDPOINT_BUSY, busy.data)
            assertEquals(TargetEventJournalContract.RESULT_OK, TargetEventJournalEndpoint.sequence().code)
            requestIds.forEach { assertEquals(3, TargetEventJournalEndpoint.cancelWait(it).code) }
            waits.forEach {
                assertEquals(
                    TargetEventJournalContract.RESULT_CANCELLED,
                    it.get(1L, TimeUnit.SECONDS).code,
                )
            }
        } finally {
            saturatedCallers.shutdownNow()
        }
    }

    private fun awaitActiveWaitCount(expected: Int) {
        repeat(1_000) {
            if (TargetEventJournalEndpoint.activeWaitCountForTest() == expected) return
            Thread.sleep(1L)
        }
        error("Expected $expected active waits")
    }

    private fun awaitSuccessfulCancellation(requestId: String): TargetEventJournalResponse {
        repeat(1_000) {
            val response = TargetEventJournalEndpoint.cancelWait(requestId)
            if (response.code == TargetEventJournalContract.RESULT_CANCELLED) return response
            Thread.sleep(1L)
        }
        error("Wait registration was not visible")
    }
}
