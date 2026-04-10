package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreMemoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CoreMemoryEntity): Long

    @Query("SELECT * FROM core_memories ORDER BY lastAccessedAt DESC")
    suspend fun getAll(): List<CoreMemoryEntity>

    @Query("SELECT * FROM core_memories ORDER BY lastAccessedAt DESC")
    fun observeAll(): Flow<List<CoreMemoryEntity>>

    @Query("DELETE FROM core_memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM core_memories")
    suspend fun count(): Int

    @Query("""
        DELETE FROM core_memories WHERE rowId IN (
            SELECT rowId FROM core_memories ORDER BY lastAccessedAt ASC, accessCount ASC LIMIT :count
        )
    """)
    suspend fun deleteOldestBeyondLimit(count: Int)

    @Query("SELECT rowId FROM core_memories ORDER BY lastAccessedAt ASC, accessCount ASC LIMIT :count")
    suspend fun getOldestRowIds(count: Int): List<Long>

    @Query("UPDATE core_memories SET accessCount = :accessCount, lastAccessedAt = :lastAccessedAt WHERE id = :id")
    suspend fun updateAccess(id: String, accessCount: Int, lastAccessedAt: Long)

    @Query("UPDATE core_memories SET accessCount = accessCount + 1, lastAccessedAt = :lastAccessedAt WHERE id = :id")
    suspend fun updateAccessStats(id: String, lastAccessedAt: Long)

    @Query("UPDATE core_memories SET accessCount = accessCount + 1, lastAccessedAt = :lastAccessedAt WHERE id IN (:ids)")
    suspend fun updateAccessStatsBatch(ids: List<String>, lastAccessedAt: Long)

    /**
     * Batch-update entities using Room's type-safe @Update annotation, which guarantees
     * the InvalidationTracker is notified and any [observeAll] Flow re-emits with the
     * latest values. Used by [MemoryRepositoryImpl] after access-stat updates so that
     * the Memory screen reflects current counts without a manual screen refresh.
     */
    @Update
    suspend fun updateAllEntities(entities: List<CoreMemoryEntity>)
}
