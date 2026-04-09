package com.kernel.ai.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core interface for on-device LLM inference backed by LiteRT-LM.
 *
 * Lifecycle:
 * 1. [initialize] — loads model weights onto the chosen hardware backend (10–30 s).
 * 2. [generate] — streams [GenerationResult] tokens for one or more turns.
 * 3. [resetConversation] — clears context window while keeping the engine warm.
 * 4. [shutdown] — releases all native resources.
 *
 * Thread-safety: implementations pin all work to [LlmDispatcher].
 */
interface InferenceEngine {

    /** True once the engine has initialized and is ready to generate. */
    val isReady: StateFlow<Boolean>

    /** True while a [generate] call is in flight. */
    val isGenerating: StateFlow<Boolean>

    /** The hardware backend currently in use, or null before initialization. */
    val activeBackend: StateFlow<BackendType?>

    /**
     * Load model weights and initialize the LiteRT-LM engine.
     * Blocks the [LlmDispatcher] thread; observe [isReady] for completion.
     *
     * @throws [InferenceException] if all backends fail.
     */
    suspend fun initialize(config: ModelConfig)

    /**
     * Send [userMessage] to the model and stream back [GenerationResult] events.
     * The conversation history is preserved across calls until [resetConversation].
     *
     * Cancel the returned [Flow] to abort generation mid-stream.
     */
    fun generate(userMessage: String): Flow<GenerationResult>

    /** Cancel any in-flight generation immediately. */
    fun cancelGeneration()

    /**
     * Clear the conversation context window and start fresh.
     * The engine stays warm — no model reload required.
     */
    suspend fun resetConversation()

    /**
     * Update the system prompt and reset the conversation with the new prompt.
     * The engine stays warm — no model reload required.
     */
    suspend fun updateSystemPrompt(systemPrompt: String)

    /** Release the engine and all native resources. Safe to call multiple times. */
    suspend fun shutdown()
}
