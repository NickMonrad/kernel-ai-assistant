package com.kernel.ai.core.memory.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores user-configured inference parameters for a specific model.
 *
 * @param modelId Stable identifier, e.g. "gemma4_e4b" or "gemma4_e2b".
 */
@Entity(tableName = "model_settings")
data class ModelSettingsEntity(
    @PrimaryKey val modelId: String,
    /** KV-cache capacity / context window tokens. */
    val contextWindowSize: Int,
    val temperature: Float,
    val topP: Float,
    val minP: Float,
    /** null = disabled. When set, typically 1.0–2.0. */
    val repetitionPenalty: Float?,
    val updatedAt: Long = System.currentTimeMillis(),
)
