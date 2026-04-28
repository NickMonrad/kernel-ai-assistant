package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores user-configured inference parameters for a specific model.
 *
 * @param modelId Stable identifier, e.g. "gemma_4_e4b" or "gemma_4_e2b" — matches
 *   [com.kernel.ai.core.inference.download.KernelModel.modelId].
 */
@Entity(tableName = "model_settings")
data class ModelSettingsEntity(
    @PrimaryKey val modelId: String,
    /** KV-cache capacity / context window tokens. */
    val contextWindowSize: Int,
    val temperature: Float,
    val topP: Float,
    /** Top-K candidates to sample from. Ignored when backend is NPU (hardware sampler). */
    val topK: Int = 64,
    /** Whether to display the model's internal reasoning (thinking tokens) in the chat UI. */
    val showThinkingProcess: Boolean = true,
    /** Whether to post-process LLM output to repair truncated percentages and malformed years
     *  from grounding context. Disabled by default (#681) — the Levenshtein-based year repair
     *  was replacing correct numbers that happened to be close to years in RAG context. */
    val correctGroundedFactsEnabled: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
