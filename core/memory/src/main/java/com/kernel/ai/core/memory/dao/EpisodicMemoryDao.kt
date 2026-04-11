package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodicMemoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EpisodicMemoryEntity): Long

    @Query("SELECT * FROM episodic_memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<EpisodicMemoryEntity>

    @Query("SELECT * FROM episodic_memories WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    suspend fun getByConversation(conversationId: String): List<EpisodicMemoryEntity>

    @Query("DELETE FROM episodic_memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM episodic_memories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM episodic_memories")
    fun observeCount(): Flow<Int>

    @Query("SELECT rowId FROM episodic_memories WHERE createdAt < :cutoffMs")
    suspend fun getRowIdsOlderThan(cutoffMs: Long): List<Long>

    @Query("DELETE FROM episodic_memories WHERE createdAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM episodic_memories WHERE createdAt < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("SELECT rowId FROM episodic_memories ORDER BY createdAt ASC LIMIT :count")
    suspend fun getOldestRowIds(count: Int): List<Long>

    @Query("""
        DELETE FROM episodic_memories WHERE rowId IN (
            SELECT rowId FROM episodic_memories ORDER BY createdAt ASC LIMIT :count
        )
    """)
    suspend fun deleteOldestBeyondLimit(count: Int)

    @Query("SELECT rowId FROM episodic_memories ORDER BY lastAccessedAt ASC LIMIT :count")
    suspend fun getOldestRowIdsByLRU(count: Int): List<Long>

    @Query("DELETE FROM episodic_memories WHERE id IN (SELECT id FROM episodic_memories ORDER BY lastAccessedAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("UPDATE episodic_memories SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateLastAccessedAt(id: String, timestamp: Long)

    @Query("SELECT * FROM episodic_memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<EpisodicMemoryEntity>>

    @Query("DELETE FROM episodic_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT rowId FROM episodic_memories WHERE id = :id LIMIT 1")
    suspend fun getRowIdById(id: String): Long?

    @Transaction
    suspend fun getRowIdAndDelete(id: String): Long? {
        val rowId = getRowIdById(id)
        if (rowId != null) deleteById(id)
        return rowId
    }
}
