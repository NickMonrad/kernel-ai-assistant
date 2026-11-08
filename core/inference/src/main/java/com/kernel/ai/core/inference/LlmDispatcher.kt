package com.kernel.ai.core.inference

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Dedicated single-threaded dispatcher for all LiteRT-LM operations.
 *
 * LiteRT-LM's [com.google.ai.edge.litertlm.Conversation] is NOT thread-safe.
 * Pinning all inference work to a single named thread ensures safety and makes
 * profiling/debugging straightforward (look for "llm-inference" in traces).
 */
val LlmDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "llm-inference").apply { isDaemon = true }
}.asCoroutineDispatcher()
