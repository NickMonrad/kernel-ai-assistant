package com.kernel.ai.core.inference

/** Produces a fixed-length float vector from a piece of text. */
interface EmbeddingEngine {
    /** The number of dimensions in each embedding vector. */
    val dimensions: Int

    /**
     * Encodes [text] into a normalised embedding vector.
     * Must be called from a coroutine — runs on IO dispatcher internally.
     */
    suspend fun embed(text: String): FloatArray

    /** Release native resources held by the engine. */
    fun close()
}
