package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.WorldClockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldClockDao {
    @Query("SELECT * FROM world_clocks ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<WorldClockEntity>>

    @Query("SELECT * FROM world_clocks ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<WorldClockEntity>

    @Query("SELECT * FROM world_clocks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorldClockEntity?

    @Query("SELECT * FROM world_clocks WHERE zone_id = :zoneId LIMIT 1")
    suspend fun getByZoneId(zoneId: String): WorldClockEntity?

    @Query("SELECT MAX(sort_order) FROM world_clocks")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WorldClockEntity)

    @Update
    suspend fun updateAll(entities: List<WorldClockEntity>)

    @Query("DELETE FROM world_clocks WHERE id = :id")
    suspend fun delete(id: String): Int
}
