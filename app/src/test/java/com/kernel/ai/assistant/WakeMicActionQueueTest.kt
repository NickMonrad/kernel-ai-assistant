package com.kernel.ai.assistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeMicActionQueueTest {

    // The worker is a StandardTestDispatcher on the test scheduler, so nothing runs until the test advances it.
    // That makes "the pause still holds the lock" a deterministic state rather than a timing race.

    @Test
    fun `a resume queued behind a pause runs after the pause, and the detector ends running`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val lock = Mutex()
        val order = mutableListOf<String>()
        var running = true

        val pause = launchQueuedUnder(lock, worker) {
            running = false
            order += "pause"
        }
        assertTrue(lock.isLocked, "the pause must hold the lock before it runs")

        val resume = launchQueuedUnder(lock, worker) {
            running = true
            order += "resume"
        }
        assertEquals(emptyList<String>(), order, "nothing runs until the worker is advanced")
        assertTrue(lock.isLocked, "the resume queues behind the pause and does not take the lock")

        advanceUntilIdle()
        joinAll(pause, resume)
        assertEquals(listOf("pause", "resume"), order, "resume must wait behind the pause that holds the lock")
        assertTrue(running, "pause then resume must leave the detector running")
        assertFalse(lock.isLocked, "the lock must be released once the queue drains")
    }

    @Test
    fun `the lock is taken before launchQueuedUnder returns, even when the scope's dispatcher has not run`() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val lock = Mutex()

        val job = launchQueuedUnder(lock, worker) { }

        assertTrue(lock.isLocked, "the queued action must hold the lock as soon as launchQueuedUnder returns")
        advanceUntilIdle()
        joinAll(job)
        assertFalse(lock.isLocked, "the queued action must release the lock once it has run")
    }
}
