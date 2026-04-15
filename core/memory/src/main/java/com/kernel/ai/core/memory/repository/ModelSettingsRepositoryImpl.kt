package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.memory.dao.ModelSettingsDao
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelSettingsRepositoryImpl @Inject constructor(
    private val dao: ModelSettingsDao,
    private val hardwareProfileDetector: HardwareProfileDetector,
) : ModelSettingsRepository {

    override suspend fun getSettings(modelId: String): ModelSettingsEntity {
        return dao.getSettings(modelId) ?: defaultSettings(modelId)
    }

    override suspend fun saveSettings(entity: ModelSettingsEntity) {
        dao.upsertSettings(entity)
    }

    override suspend fun resetToDefaults(modelId: String): ModelSettingsEntity {
        val defaults = defaultSettings(modelId)
        dao.upsertSettings(defaults)
        return defaults
    }

    override fun getMaxUserProfileChars(contextWindowSize: Int): Int =
        (contextWindowSize * 0.1).toInt().coerceIn(500, 3000)

    private fun defaultSettings(modelId: String): ModelSettingsEntity {
        val defaultContextWindow = hardwareProfileDetector.profile.recommendedMaxTokens
        return ModelSettingsEntity(
            modelId = modelId,
            contextWindowSize = defaultContextWindow,
            temperature = 1.0f,
            topP = 0.95f,
            topK = 40,
            showThinkingProcess = true,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
