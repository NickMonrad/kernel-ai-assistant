package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ToolCallInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WeatherConversationReferenceResolverTest {

    @Test
    fun `resolves there from previous capital answer`() {
        val messages = listOf(
            ChatMessage(
                id = "assistant-1",
                role = ChatMessage.Role.ASSISTANT,
                content = "Wellington is the capital of New Zealand.",
            ),
        )

        val location = WeatherConversationReferenceResolver.resolveLocation(
            query = "How's the weather there",
            messages = messages,
        )

        assertEquals("Wellington", location)
    }

    @Test
    fun `resolves there from previous weather card`() {
        val messages = listOf(
            ChatMessage(
                id = "assistant-1",
                role = ChatMessage.Role.ASSISTANT,
                content = "Weather reply",
                toolCall = ToolCallInfo(
                    skillName = "get_weather",
                    requestJson = """{"location":"Wellington"}""",
                    resultText = "Weather result",
                    isSuccess = true,
                    presentation = ToolPresentation.Weather(
                        locationName = "Wellington",
                        temperatureText = "13°C",
                        feelsLikeText = null,
                        description = "Rain",
                        emoji = "🌧️",
                        highLowText = null,
                        humidityText = null,
                        windText = null,
                        precipText = null,
                        uvText = null,
                        airQualityText = null,
                        sunText = null,
                    ),
                ),
            ),
        )

        val location = WeatherConversationReferenceResolver.resolveLocation(
            query = "What's the weather there",
            messages = messages,
        )

        assertEquals("Wellington", location)
    }

    @Test
    fun `ignores out there local-weather phrasing`() {
        val messages = listOf(
            ChatMessage(
                id = "assistant-1",
                role = ChatMessage.Role.ASSISTANT,
                content = "Wellington is the capital of New Zealand.",
            ),
        )

        val location = WeatherConversationReferenceResolver.resolveLocation(
            query = "How's the weather out there",
            messages = messages,
        )

        assertNull(location)
    }
}
