package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ConversionHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionHistoryDao {
    @Query("SELECT * FROM conversion_history WHERE type = :type ORDER BY created_at DESC LIMIT 20")
    fun observeByType(type: String): Flow<List<ConversionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversionHistoryEntity)

    @Query(
        """
        DELETE FROM conversion_history
        WHERE type = :type
          AND id NOT IN (
              SELECT id FROM conversion_history
              WHERE type = :type
              ORDER BY created_at DESC
              LIMIT 20
          )
        """
    )
    suspend fun pruneToLimit(type: String)

    @Query("DELETE FROM conversion_history WHERE type = :type")
    suspend fun deleteByType(type: String)
}
