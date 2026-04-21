package com.kernel.ai.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ToolCallInfo
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the tool-call chip in [MessageBubble].
 */
class ChatScreenToolChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(message: ChatMessage) {
        composeTestRule.setContent {
            MessageBubbleTestWrapper(message = message)
        }
    }

    @Test
    fun toolChipVisible_whenToolCallPresent() {
        val message = ChatMessage(
            id = "1",
            role = ChatMessage.Role.ASSISTANT,
            content = "Done!",
            toolCall = ToolCallInfo(
                skillName = "set_timer",
                requestJson = """{"seconds":60}""",
                resultText = "Timer set for 60 seconds",
                isSuccess = true,
            ),
        )
        setContent(message)

        composeTestRule.onNodeWithTag("tool_chip").assertIsDisplayed()
    }

    @Test
    fun toolChipVisible_whenRichPresentationPresent() {
        val message = ChatMessage(
            id = "rich",
            role = ChatMessage.Role.ASSISTANT,
            content = "Weather response",
            toolCall = ToolCallInfo(
                skillName = "get_weather",
                requestJson = """{"location":"Wellington"}""",
                resultText = "Wellington forecast: 13°C / 12°C",
                isSuccess = true,
                presentation = ToolPresentation.Weather(
                    locationName = "Wellington",
                    temperatureText = "13°C / 12°C",
                    feelsLikeText = null,
                    description = "Rain",
                    emoji = "🌧️",
                    highLowText = "High 13°C • Low 12°C",
                    humidityText = "82%",
                    windText = "4 m/s",
                    precipText = "5mm rain",
                    uvText = "UV max 6 (High)",
                    airQualityText = null,
                    sunText = "Sunrise 07:10 • Sunset 17:31",
                ),
            ),
        )
        setContent(message)

        composeTestRule.onNodeWithTag("tool_chip").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wellington").assertIsDisplayed()
        composeTestRule.onNodeWithText("🌡 High 13°C • Low 12°C").assertIsDisplayed()
    }

    @Test
    fun toolChipNotVisible_whenToolCallNull() {
        val message = ChatMessage(
            id = "2",
            role = ChatMessage.Role.ASSISTANT,
            content = "Just a message",
            toolCall = null,
        )
        setContent(message)

        composeTestRule.onNodeWithTag("tool_chip").assertDoesNotExist()
    }

    @Test
    fun toolChipNotVisible_forUserMessages() {
        val message = ChatMessage(
            id = "3",
            role = ChatMessage.Role.USER,
            content = "Set a timer",
            toolCall = null,
        )
        setContent(message)

        composeTestRule.onNodeWithTag("tool_chip").assertDoesNotExist()
    }
}
