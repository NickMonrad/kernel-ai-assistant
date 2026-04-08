package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {

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
        conversationDao.delete(conversation)
    }

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        messageDao.getByConversation(conversationId)
}
