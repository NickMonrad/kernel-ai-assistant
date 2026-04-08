package com.kernel.ai.core.inference

/** Represents a single unit of output from the LLM streaming pipeline. */
sealed class GenerationResult {

    /** A partial text token from the model. */
    data class Token(val text: String) : GenerationResult()

    /** Internal reasoning token (from Gemma thinking mode). */
    data class Thinking(val text: String) : GenerationResult()

    /** Generation finished successfully. */
    data class Complete(val durationMs: Long) : GenerationResult()

    /** Generation failed with an error. */
    data class Error(val message: String, val cause: Throwable? = null) : GenerationResult()
}
