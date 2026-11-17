package com.kernel.ai.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    @Test
    fun `a caller arriving during a run joins it instead of starting another`() = runTest {
        val flight = SingleFlight<Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val block: suspend () -> Int = { runs++; gate.await(); runs }

        val first = async { flight.run(block) }
        runCurrent()
        val second = async { flight.run(block) }
        runCurrent()
        gate.complete(Unit)

        assertEquals(1, first.await())
        assertEquals(1, second.await())
        assertEquals(1, runs)
    }

    @Test
    fun `a run after the previous one finished starts afresh`() = runTest {
        val flight = SingleFlight<Int>(backgroundScope)
        var runs = 0

        flight.run { ++runs }
        flight.run { ++runs }

        assertEquals(2, runs)
    }

    @Test
    fun `awaitInFlight waits for the running check`() = runTest {
        val flight = SingleFlight<Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val run = async { flight.run { gate.await(); 1 } }
        runCurrent()

        val waiter = async { flight.awaitInFlight() }
        runCurrent()
        assertFalse(waiter.isCompleted)
        gate.complete(Unit)
        waiter.await()
        assertEquals(1, run.await())
    }
}
