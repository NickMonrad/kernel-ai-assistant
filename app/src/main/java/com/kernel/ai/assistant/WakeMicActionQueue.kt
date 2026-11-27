package com.kernel.ai.assistant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Queues [block] behind [lock] in call order, then runs it on [worker] (#2109, #2143).
 *
 * The coroutine is started UNDISPATCHED, so it joins [lock]'s queue synchronously, on the caller's thread, in call
 * order. [Mutex] serves waiters first-in first-out, so a pause followed by a resume cannot run the other way round.
 *
 * The block is always dispatched to [worker], even when [worker] is this scope's own dispatcher, so it never runs on
 * the calling thread. The detector's stop joins its thread and the caller may be the main thread. [withContext] would
 * not do this: it runs inline when the context already matches. The lock is held until the block finishes, including
 * when the returned job is cancelled.
 */
internal fun CoroutineScope.launchQueuedUnder(lock: Mutex, worker: CoroutineDispatcher, block: () -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        lock.lock()
        try {
            val run = launch(worker) { block() }
            withContext(NonCancellable) { run.join() }
        } finally {
            lock.unlock()
        }
    }
