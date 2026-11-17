package com.kernel.ai.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs at most one [block] at a time in [scope]: a caller that arrives while a run is in flight
 * awaits that run instead of starting another. The run belongs to [scope], so a cancelled caller
 * does not cancel it for the others.
 */
internal class SingleFlight<T>(private val scope: CoroutineScope) {
    private var inFlight: Deferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val run = synchronized(this) {
            inFlight?.takeIf { it.isActive } ?: scope.async { block() }.also { inFlight = it }
        }
        return run.await()
    }

    /** Waits for the run in flight, if any, without starting one. Never throws for a failed run. */
    suspend fun awaitInFlight() {
        synchronized(this) { inFlight?.takeIf { it.isActive } }?.join()
    }
}
