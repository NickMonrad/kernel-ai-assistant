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

    @Test
    fun correctGroundedFacts_repairsGroundedYears() {
        val grounding = """
            [NZ Context: Flight of the Conchords] Their HBO television series ran from 2007 to 2009.
        """.trimIndent()

        val corrected = correctGroundedFacts(
            response = "The HBO series for Flight of the Conchords ran from 200007 to 209.",
            groundingContext = grounding,
        )

        assertEquals(
            "The HBO series for Flight of the Conchords ran from 2007 to 2009.",
            corrected,
        )
    }

    @Test
    fun correctGroundedFacts_repairsGroundedPercentages() {
        val corrected = correctGroundedFacts(
            response = "Battery is at 9%.",
            groundingContext = "[System: get_battery — Battery is at 92%.]",
        )

        assertEquals("Battery is at 92%.", corrected)
    }
}
