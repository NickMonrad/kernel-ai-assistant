package com.kernel.ai.core.memory.rag

/**
 * A single message result returned by [RagRepository.searchMessages].
 *
 * @param role "user" or "assistant"
 * @param content The message text.
 * @param conversationId The conversation this message belongs to.
 * @param timestamp Unix millis when the message was recorded.
 */
data class MessageSearchResult(
    val role: String,
    val content: String,
    val conversationId: String,
    val timestamp: Long,
)
