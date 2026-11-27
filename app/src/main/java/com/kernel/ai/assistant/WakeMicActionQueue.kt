package com.kernel.ai.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Queues [block] behind [lock] in call order, then runs it on [worker] (#2109).
 *
 * The coroutine is started UNDISPATCHED, so it joins [lock]'s queue in call order without a dispatch hop. [Mutex]
 * serves waiters first-in first-out, so a pause followed by a resume cannot run the other way round.
 * The block then moves to [worker], because the detector's stop
 * joins its thread and the caller may be the main thread.
 */
internal fun CoroutineScope.launchQueuedUnder(lock: Mutex, worker: CoroutineContext, block: () -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        lock.withLock { withContext(worker) { block() } }
    }
