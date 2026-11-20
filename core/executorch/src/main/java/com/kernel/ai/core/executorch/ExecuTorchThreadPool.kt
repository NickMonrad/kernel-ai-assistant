package com.kernel.ai.core.executorch

import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards ExecuTorch's process-wide XNNPACK thread pool.
 *
 * ExecuTorch 1.5.1 has one thread pool per process. Every `Module` load calls
 * `threadpool->_unsafe_reset_threadpool(numThreads)`, which destroys the old `pthreadpool` and
 * creates a new one whenever the count changes (`extension/threadpool/threadpool.cpp`). The
 * XNNPACK delegate stores the raw `pthreadpool` pointer when a method is initialised
 * (`backends/xnnpack/runtime/XNNCompiler.cpp`), so changing the count while another model is
 * open leaves that model with a dangling pool: a native use-after-free on its next run.
 *
 * Invariant enforced here: while any model is open, every load must use the same thread
 * count. A conflicting load fails before reaching native code. Loads are serialised so the
 * check and the reset are atomic.
 *
 * `LlmModule` construction resets the pool to (performant cores - 1) regardless of the
 * caller. A future LLM wrapper in this module (#1755) must load through [load] with that
 * count, so it fails fast instead of corrupting open models.
 */
internal object ExecuTorchThreadPool {
    private val loadLock = Any()
    private val openModels = AtomicInteger()

    /** Pool size set by the most recent load attempt; 0 before any load. */
    @Volatile
    var threads: Int = 0
        private set

    /**
     * Runs [block], a native load that resets the pool to [threads], and counts the result as
     * an open model until [released].
     *
     * @throws IllegalStateException if other models are open with a different thread count.
     */
    fun <T> load(threads: Int, block: () -> T): T = synchronized(loadLock) {
        val open = openModels.get()
        check(open == 0 || threads == this.threads) {
            "ExecuTorch's process-wide thread pool has ${this.threads} threads and $open model(s) " +
                "open; loading with $threads threads would free the pool their XNNPACK " +
                "delegates use. Load with ${this.threads} threads or close the open models first."
        }
        this.threads = threads
        val result = block()
        openModels.incrementAndGet()
        result
    }

    /** Records that a model returned by [load] has released its native resources. */
    fun released() {
        openModels.decrementAndGet()
    }
}
