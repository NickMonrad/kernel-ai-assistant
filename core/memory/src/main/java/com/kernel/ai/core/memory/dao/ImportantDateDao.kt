package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportantDateDao {
    @Query("SELECT * FROM important_dates ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ImportantDateEntity>>

    @Query("SELECT * FROM important_dates ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAll(): List<ImportantDateEntity>

    @Query("SELECT * FROM important_dates WHERE normalized_label = :normalizedLabel LIMIT 1")
    suspend fun findByNormalizedLabel(normalizedLabel: String): ImportantDateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(date: ImportantDateEntity): Long

    @Query("SELECT * FROM important_dates WHERE notification_enabled = 1 ORDER BY label COLLATE NOCASE ASC")
    suspend fun getAllWithNotificationEnabled(): List<ImportantDateEntity>

    @Query("DELETE FROM important_dates WHERE normalized_label = :normalizedLabel")
    suspend fun deleteByNormalizedLabel(normalizedLabel: String): Int
}
