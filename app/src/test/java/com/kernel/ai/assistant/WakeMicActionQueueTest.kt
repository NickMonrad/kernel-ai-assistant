package com.kernel.ai.assistant

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
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

    @Test
    fun `the block does not run inline when the scope's dispatcher is the worker`() = runTest {
        val worker = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val lock = Mutex()
        var ran = false

        val job = launchQueuedUnder(lock, worker) { ran = true }

        assertFalse(ran, "the block must be dispatched to the worker, not run inline on the queueing thread")
        advanceUntilIdle()
        joinAll(job)
        assertTrue(ran, "the queued block must run once the worker is advanced")
    }

    @Test
    fun `call order holds when the scope's dispatcher is the worker`() = runTest {
        val worker = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val lock = Mutex()
        val order = mutableListOf<String>()

        val pause = launchQueuedUnder(lock, worker) { order += "pause" }
        val resume = launchQueuedUnder(lock, worker) { order += "resume" }
        advanceUntilIdle()
        joinAll(pause, resume)

        assertEquals(listOf("pause", "resume"), order, "resume must wait behind the pause in call order")
        assertFalse(lock.isLocked, "the lock must be released once the queue drains")
    }

    @Test
    fun `the block never runs on the thread that queued it, even when the scope shares the worker`() {
        val callerThread = Thread.currentThread()
        var blockThread: Thread? = null
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            runBlocking {
                scope.launchQueuedUnder(Mutex(), Dispatchers.Default) { blockThread = Thread.currentThread() }.join()
            }
        } finally {
            scope.cancel()
        }

        assertNotNull(blockThread, "the queued block must run")
        assertNotSame(callerThread, blockThread, "the block ran on the thread that queued it")
    }
}
