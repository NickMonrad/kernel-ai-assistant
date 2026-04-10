package com.kernel.ai.feature.chat

import com.kernel.ai.feature.chat.model.ChatMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatViewModelCopyTest {

    private fun buildMessage(role: ChatMessage.Role, content: String) = ChatMessage(
        id = java.util.UUID.randomUUID().toString(),
        role = role,
        content = content,
    )

    private fun formatMessages(messages: List<ChatMessage>): String =
        messages.joinToString("\n") { msg ->
            val prefix = if (msg.role == ChatMessage.Role.USER) "You" else "Jandal"
            "$prefix: ${msg.content}"
        }

    @Test
    fun getConversationAsText_formatsMessagesWithCorrectPrefixes() {
        val messages = listOf(
            buildMessage(ChatMessage.Role.USER, "Hello"),
            buildMessage(ChatMessage.Role.ASSISTANT, "Hi there!"),
            buildMessage(ChatMessage.Role.USER, "How are you?"),
            buildMessage(ChatMessage.Role.ASSISTANT, "Done."),
        )
        val result = formatMessages(messages)
        val expected = "You: Hello\nJandal: Hi there!\nYou: How are you?\nJandal: Done."
        assertEquals(expected, result)
    }

    @Test
    fun getConversationAsText_returnsEmptyStringForEmptyConversation() {
        assertEquals("", formatMessages(emptyList()))
    }

    @Test
    fun getConversationAsText_singleUserMessage() {
        val messages = listOf(buildMessage(ChatMessage.Role.USER, "Just me"))
        assertEquals("You: Just me", formatMessages(messages))
    }

    @Test
    fun getConversationAsText_singleAssistantMessage() {
        val messages = listOf(buildMessage(ChatMessage.Role.ASSISTANT, "Hello!"))
        assertEquals("Jandal: Hello!", formatMessages(messages))
    }
}
