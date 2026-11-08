package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.RecentShortcutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentShortcutDao {
    @Query("SELECT * FROM recent_shortcuts ORDER BY opened_at DESC")
    fun observeAll(): Flow<List<RecentShortcutEntity>>

    @Query("SELECT id FROM recent_shortcuts ORDER BY opened_at DESC")
    suspend fun getAllIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentShortcutEntity)

    @Query("DELETE FROM recent_shortcuts WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM recent_shortcuts WHERE id NOT IN (SELECT id FROM recent_shortcuts ORDER BY opened_at DESC LIMIT :limit)")
    suspend fun trimToLimit(limit: Int): Int

    @Query("SELECT COUNT(*) FROM recent_shortcuts")
    suspend fun count(): Int

    @Query("DELETE FROM recent_shortcuts")
    suspend fun deleteAll()
}
