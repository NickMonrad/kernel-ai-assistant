package com.kernel.ai.core.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the #1433 detector-stop contract: `OnnxWakeWordDetector.stop()` must not
 * return until the detection loop has terminated and released its AudioRecord, and
 * must never deadlock when invoked near (or from) the detection callback.
 *
 * The join mechanics are extracted into [awaitThreadTermination] so they are
 * deterministic to test without an Android AudioRecord.
 */
class WakeWordDetectorLifecycleTest {

    @Test
    fun `awaitThreadTermination returns only after the thread has terminated`() {
        val latch = CountDownLatch(1)
        val thread = Thread { latch.await() }.also { it.start() }

        assertTrue(thread.isAlive)
        latch.countDown()

        awaitThreadTermination(thread, 2_000L)

        assertFalse(thread.isAlive)
    }

    @Test
    fun `awaitThreadTermination never joins the caller's own thread`() {
        // Would deadlock if it joined the current thread; must return immediately.
        awaitThreadTermination(Thread.currentThread(), 1_000L)
    }

    @Test
    fun `awaitThreadTermination is bounded and does not wait for a live thread forever`() {
        val thread = Thread { Thread.sleep(30_000) }.also { it.start() }

        val started = System.nanoTime()
        awaitThreadTermination(thread, 100L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMs < 5_000L, "join must be bounded, took ${elapsedMs}ms")
        assertTrue(thread.isAlive)
        thread.interrupt()
        thread.join(2_000)
    }

    @Test
    fun `awaitThreadTermination tolerates a null thread`() {
        awaitThreadTermination(null, 100L)
    }

    @Test
    fun `awaitThreadTermination returns after the thread dies even when it outlives the bound`() {
        val gate = CountDownLatch(1)
        val thread = Thread {
            try {
                gate.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.also { it.start() }

        // First call times out with the thread still alive…
        awaitThreadTermination(thread, 50L)
        assertTrue(thread.isAlive)

        // …and a second call completes once the thread actually terminates.
        gate.countDown()
        awaitThreadTermination(thread, 2_000L)
        assertFalse(thread.isAlive)
    }
}
