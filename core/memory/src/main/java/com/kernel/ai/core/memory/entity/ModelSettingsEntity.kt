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
    val topK: Int = 40,
    /** Whether to display the model's internal reasoning (thinking tokens) in the chat UI. */
    val showThinkingProcess: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
)
