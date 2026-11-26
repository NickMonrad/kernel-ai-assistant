package com.kernel.ai.assistant

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JandalVoiceInteractionSessionTest {

    @Test
    fun `a voice turn waits for a slow mic release instead of a fixed delay`() = runTest {
        val release = Job()
        val startedAt = mutableListOf<Long>()
        val turn = launch {
            awaitMicRelease(release)
            startedAt += testScheduler.currentTime
        }
        advanceTimeBy(1_000) // well past the old 300 ms fixed delay
        assertTrue(startedAt.isEmpty(), "the turn must not start while the mic is still held")
        release.complete()
        turn.join()
        assertEquals(listOf(1_000L), startedAt)
    }

    @Test
    fun `a release that never completes gives up at the timeout`() = runTest {
        val stuck = Job()
        assertFalse(awaitMicRelease(stuck, timeoutMs = 2_500))
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test
    fun `no running wake service means nothing to wait for`() = runTest {
        assertTrue(awaitMicRelease(null))
        assertEquals(0L, testScheduler.currentTime)
    }
}
