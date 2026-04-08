package com.kernel.ai.feature.chat.model

data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val thinkingText: String? = null,
    val isStreaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }
}
