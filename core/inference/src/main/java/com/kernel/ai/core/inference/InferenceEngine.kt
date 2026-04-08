package com.kernel.ai.core.inference

import kotlinx.coroutines.flow.Flow

/**
 * Core interface for on-device LLM inference.
 * Implementations wrap LiteRT-LM for different model types.
 */
interface InferenceEngine {

    /** Whether the engine has a model loaded and ready for inference. */
    val isReady: Boolean

    /** Load model weights from the given file path. */
    suspend fun loadModel(modelPath: String)

    /** Generate a streaming response for the given prompt. */
    fun generate(prompt: String): Flow<String>

    /** Release model weights and free memory. */
    suspend fun unload()
}
