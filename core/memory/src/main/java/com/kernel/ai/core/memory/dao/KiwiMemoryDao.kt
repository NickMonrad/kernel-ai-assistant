package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kernel.ai.core.memory.entity.KiwiMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KiwiMemoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KiwiMemoryEntity): Long

    @Query("SELECT * FROM kiwi_memories ORDER BY lastAccessedAt DESC")
    suspend fun getAll(): List<KiwiMemoryEntity>

    @Query("SELECT * FROM kiwi_memories ORDER BY lastAccessedAt DESC")
    fun observeAll(): Flow<List<KiwiMemoryEntity>>

    @Query("DELETE FROM kiwi_memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM kiwi_memories WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM kiwi_memories WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query("SELECT rowId FROM kiwi_memories WHERE source = :source")
    suspend fun getRowIdsBySource(source: String): List<Long>

    @Query("SELECT COUNT(*) FROM kiwi_memories")
    suspend fun count(): Int

    @Query("UPDATE kiwi_memories SET accessCount = accessCount + 1, lastAccessedAt = :lastAccessedAt WHERE id IN (:ids)")
    suspend fun updateAccessStatsBatch(ids: List<String>, lastAccessedAt: Long)

    @Transaction
    suspend fun incrementAccessStatsAndNotify(ids: List<String>, lastAccessedAt: Long) {
        updateAccessStatsBatch(ids, lastAccessedAt)
    }

    @Query("SELECT * FROM kiwi_memories WHERE vectorized = 0")
    suspend fun getUnvectorized(): List<KiwiMemoryEntity>

    @Query("UPDATE kiwi_memories SET vectorized = 1 WHERE rowId = :rowId")
    suspend fun markVectorized(rowId: Long)

    @Query("UPDATE kiwi_memories SET vectorized = 0")
    suspend fun markAllUnvectorized()

    @Query("SELECT rowId FROM kiwi_memories WHERE id = :id LIMIT 1")
    suspend fun getRowIdById(id: String): Long?

    @Query("UPDATE kiwi_memories SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("""
        DELETE FROM kiwi_memories WHERE rowId IN (
            SELECT rowId FROM kiwi_memories ORDER BY lastAccessedAt ASC, accessCount ASC LIMIT :count
        )
    """)
    suspend fun deleteOldestBeyondLimit(count: Int)

    @Query("SELECT * FROM kiwi_memories WHERE rowId IN (:rowIds)")
    suspend fun getByRowIds(rowIds: List<Long>): List<KiwiMemoryEntity>

    @Update
    suspend fun updateAllEntities(entities: List<KiwiMemoryEntity>)
}
