package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.core.skills.ToolPresentationJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickActionSpeechTextTest {

    private val weather = ToolPresentation.Weather(
        locationName = "Auckland, NZ",
        temperatureText = "18°C",
        feelsLikeText = null,
        description = "Cloudy",
        emoji = "☁️",
        humidityText = "70%",
        windText = "12 km/h",
        precipText = "0 mm",
        airQualityText = null,
    )

    @Test
    fun `successful weather card without a skill summary speaks its presentation summary`() {
        assertEquals(
            "In Auckland, it's 18 degrees Celsius, Cloudy.",
            quickActionSpeechText(
                spokenSummary = null,
                resultText = "Display text",
                presentationJson = ToolPresentationJson.toJsonString(weather),
                isSuccess = true,
            ),
        )
    }

    @Test
    fun `skill spoken summary wins over the presentation summary`() {
        assertEquals(
            "Tomorrow in Auckland, 18 degrees, light rain.",
            quickActionSpeechText(
                spokenSummary = "Tomorrow in Auckland, 18 degrees, light rain.",
                resultText = "Display text",
                presentationJson = ToolPresentationJson.toJsonString(weather),
                isSuccess = true,
            ),
        )
    }

    @Test
    fun `failed card ignores its presentation and speaks the reply text`() {
        assertEquals(
            "Error. Network unavailable.",
            quickActionSpeechText(
                spokenSummary = null,
                resultText = "Error. Network unavailable.",
                presentationJson = ToolPresentationJson.toJsonString(weather),
                isSuccess = false,
            ),
        )
    }

    @Test
    fun `card without a presentation or summary speaks its reply text`() {
        assertEquals(
            "Timer set for 5 minutes.",
            quickActionSpeechText(
                spokenSummary = null,
                resultText = "Timer set for 5 minutes.",
                presentationJson = null,
                isSuccess = true,
            ),
        )
    }
}
