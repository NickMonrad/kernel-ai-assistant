package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.ToolPresentation
import com.kernel.ai.core.skills.ToolPresentationJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickActionSpeechTextTest {

    @Test
    fun `weather card speaks its short summary instead of every display field`() {
        val weather = ToolPresentation.Weather(
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

        assertEquals(
            "In Auckland, it's 18 degrees Celsius, Cloudy.",
            quickActionSpeechText("Display text", ToolPresentationJson.toJsonString(weather)),
        )
    }

    @Test
    fun `card without a presentation speaks its reply text`() {
        assertEquals(
            "Timer set for 5 minutes.",
            quickActionSpeechText("Timer set for 5 minutes.", null),
        )
    }
}
