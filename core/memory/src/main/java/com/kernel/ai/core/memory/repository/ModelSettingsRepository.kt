package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.entity.ModelSettingsEntity

interface ModelSettingsRepository {
    /**
     * Returns saved settings for [modelId], or sensible defaults if none are stored.
     * Default [ModelSettingsEntity.contextWindowSize] is RAM-aware: ≥12 GB → 8192, else 4096.
     */
    suspend fun getSettings(modelId: String): ModelSettingsEntity

    /** Persist [entity], overwriting any existing entry for the same [ModelSettingsEntity.modelId]. */
    suspend fun saveSettings(entity: ModelSettingsEntity)

    /**
     * Resets settings for [modelId] to hardware-aware defaults, persists them, and returns the
     * new defaults.
     */
    suspend fun resetToDefaults(modelId: String): ModelSettingsEntity

    /**
     * Returns the max character count the user profile may occupy in a system prompt injection.
     * Capped at 10% of [contextWindowSize], within [500, 3000] chars.
     */
    fun getMaxUserProfileChars(contextWindowSize: Int): Int
}
