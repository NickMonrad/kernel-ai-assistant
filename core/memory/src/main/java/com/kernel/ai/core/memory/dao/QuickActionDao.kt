package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.QuickActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickActionDao {

    @Query("SELECT * FROM quick_actions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<QuickActionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: QuickActionEntity)

    @Query("DELETE FROM quick_actions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM quick_actions")
    suspend fun deleteAll()
}
