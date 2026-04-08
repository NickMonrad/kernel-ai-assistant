package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity

@Dao
interface MessageEmbeddingDao {

    /** Insert a new embedding record and return the auto-generated rowId. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEmbeddingEntity): Long

    /** Fetch a single record by its sqlite-vec rowId. */
    @Query("SELECT * FROM message_embeddings WHERE rowId = :rowId LIMIT 1")
    suspend fun getByRowId(rowId: Long): MessageEmbeddingEntity?

    /** Fetch records for a list of rowIds (returned from vector search). */
    @Query("SELECT * FROM message_embeddings WHERE rowId IN (:rowIds)")
    suspend fun getByRowIds(rowIds: List<Long>): List<MessageEmbeddingEntity>

    /** Check whether a message has already been indexed. */
    @Query("SELECT rowId FROM message_embeddings WHERE messageId = :messageId LIMIT 1")
    suspend fun getRowIdForMessage(messageId: String): Long?

    /** Total number of indexed messages (for diagnostics). */
    @Query("SELECT COUNT(*) FROM message_embeddings")
    suspend fun count(): Int
}
