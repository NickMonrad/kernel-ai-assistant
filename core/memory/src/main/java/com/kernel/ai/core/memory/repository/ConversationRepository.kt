package com.kernel.ai.core.memory.repository

import android.util.Log
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.vector.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val embeddingDao: MessageEmbeddingDao,
    private val vectorStore: VectorStore,
) {

    companion object {
        private const val TAG = "ConversationRepository"
        private const val MESSAGE_VEC_TABLE = "message_embeddings"
    }

    fun observeConversations(): Flow<List<ConversationEntity>> =
        conversationDao.observeAll()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeByConversation(conversationId)

    suspend fun createConversation(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.insert(
            ConversationEntity(id = id, title = null, createdAt = now, updatedAt = now)
        )
        return id
    }

    suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String,
        thinkingText: String? = null,
        toolCallJson: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                id = id,
                conversationId = conversationId,
                role = role,
                content = content,
                thinkingText = thinkingText,
                timestamp = now,
                toolCallJson = toolCallJson,
            )
        )
        conversationDao.touchUpdatedAt(conversationId, now)
        return id
    }

    suspend fun updateMessage(id: String, content: String, thinkingText: String? = null) {
        messageDao.updateContentAndThinking(id, content, thinkingText)
    }

    suspend fun renameConversation(id: String, title: String) {
        conversationDao.updateTitle(id, title, System.currentTimeMillis())
    }

    suspend fun deleteConversation(conversation: ConversationEntity) {
        // Clean up vec entries before Room cascade removes the embedding rows
        withContext(Dispatchers.IO) {
            val rowIds = embeddingDao.getRowIdsForConversation(conversation.id)
            rowIds.forEach { rowId ->
                runCatching { vectorStore.delete(MESSAGE_VEC_TABLE, rowId) }
                    .onFailure { Log.w(TAG, "Failed to delete vec entry rowId=$rowId: ${it.message}") }
            }
        }
        conversationDao.delete(conversation)
    }

    suspend fun getConversation(id: String): ConversationEntity? =
        conversationDao.getById(id)

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        messageDao.getByConversation(conversationId)

    fun searchByTitle(query: String): Flow<List<ConversationEntity>> =
        conversationDao.searchByTitle(query)
}
