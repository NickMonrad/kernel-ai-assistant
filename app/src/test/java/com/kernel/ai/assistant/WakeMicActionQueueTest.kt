package com.kernel.ai.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WakeMicActionQueueTest {

    @Test
    fun `a resume queued behind a pause runs after the pause, and the detector ends running`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val lock = Mutex()
        val pauseEntered = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val order = mutableListOf<String>()
        var running = true

        val pause = scope.launchQueuedUnder(lock, Dispatchers.Default) {
            pauseEntered.countDown()
            releasePause.await(5, TimeUnit.SECONDS)
            running = false
            order += "pause"
        }
        assertTrue(pauseEntered.await(5, TimeUnit.SECONDS))

        val resume = scope.launchQueuedUnder(lock, Dispatchers.Default) {
            running = true
            order += "resume"
        }

        releasePause.countDown()
        joinAll(pause, resume)
        assertEquals(listOf("pause", "resume"), order, "resume must wait behind the pause that holds the lock")
        assertTrue(running, "pause then resume must leave the detector running")
    }

    @Test
    fun `the lock is taken before launchQueuedUnder returns, even when the scope's dispatcher has not run`() {
        // The StandardTestDispatcher is never advanced, so the coroutine cannot run before the call returns.
        val scope = CoroutineScope(StandardTestDispatcher())
        val lock = Mutex()

        scope.launchQueuedUnder(lock, Dispatchers.Default) { }

        assertTrue(lock.isLocked, "the queued action must hold the lock as soon as launchQueuedUnder returns")
    }
}
