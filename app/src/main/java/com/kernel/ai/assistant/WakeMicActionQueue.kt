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
 * The block never runs inline on the calling thread. It is always launched on [worker], even when [worker] is this
 * scope's own dispatcher, so the detector's stop (which joins its thread) never blocks the caller, who may be the main
 * thread. When [worker] is the scope's dispatcher, the block may still run later on that same thread, after the
 * caller has returned. [withContext] would not do this: it runs inline when the context already matches. The lock is
 * held until the block finishes, including when the returned job is cancelled.
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

/**
 * Starts the detector unless the service is already destroyed, then stops it again if [isDestroyed] became true while
 * [start] ran. Returns true when the detector was left started.
 *
 * [WakeWordService.onDestroy] sets its flag before stopping the detector on the main thread, outside the mic lock. A
 * re-arm already running on the worker can finish its start after that stop, so the check after [start] undoes it.
 */
internal fun armUnlessDestroyed(isDestroyed: () -> Boolean, start: () -> Unit, stop: () -> Unit): Boolean {
    if (isDestroyed()) return false
    start()
    if (isDestroyed()) {
        stop()
        return false
    }
    return true
}
