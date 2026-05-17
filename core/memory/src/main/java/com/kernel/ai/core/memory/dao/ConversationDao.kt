package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archivedAt IS NULL ORDER BY updatedAt DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archivedAt IS NOT NULL ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET lastDistilledAt = :timestamp WHERE id = :id")
    suspend fun updateLastDistilledAt(id: String, timestamp: Long)

    @Query("SELECT * FROM conversations WHERE title IS NULL OR title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
    fun searchByTitle(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archivedAt IS NULL AND (title IS NULL OR title LIKE '%' || :query || '%' ESCAPE '\\') ORDER BY updatedAt DESC")
    fun searchActive(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archivedAt IS NOT NULL AND (title IS NULL OR title LIKE '%' || :query || '%' ESCAPE '\\') ORDER BY updatedAt DESC")
    fun searchArchived(query: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET archivedAt = :timestamp WHERE id = :id")
    suspend fun archiveConversation(id: String, timestamp: Long)

    @Query("UPDATE conversations SET archivedAt = NULL WHERE id = :id")
    suspend fun restoreConversation(id: String)

    @Query("UPDATE conversations SET archivedAt = :timestamp WHERE id IN (:ids)")
    suspend fun archiveConversations(ids: Collection<String>, timestamp: Long)

    @Query("UPDATE conversations SET archivedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreConversations(ids: Collection<String>)

    @Query("SELECT id FROM conversations WHERE archivedAt IS NOT NULL AND archivedAt < :cutoffMs")
    suspend fun getArchivedIdsBefore(cutoffMs: Long): List<String>

    @Query("DELETE FROM conversations WHERE archivedAt IS NOT NULL AND archivedAt < :cutoffMs")
    suspend fun deleteArchivedOlderThan(cutoffMs: Long)
}
