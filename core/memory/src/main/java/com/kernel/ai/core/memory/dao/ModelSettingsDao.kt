package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.kernel.ai.core.memory.entity.ModelSettingsEntity

@Dao
interface ModelSettingsDao {
    @Query("SELECT * FROM model_settings WHERE modelId = :modelId LIMIT 1")
    suspend fun getSettings(modelId: String): ModelSettingsEntity?

    @Upsert
    suspend fun upsertSettings(entity: ModelSettingsEntity)
}
