package com.kernel.ai.feature.chat.model

import com.kernel.ai.core.skills.ToolPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolCallInfoJsonTest {

    @Test
    fun `tool call info round-trips with presentation`() {
        val original = ToolCallInfo(
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
                humidityText = "82%",
                windText = "4 m/s",
                precipText = "5mm rain",
                airQualityText = null,
            ),
        )

        val parsed = toolCallInfoFromJson(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun `invalid tool call json returns null`() {
        assertNull(toolCallInfoFromJson("nope"))
    }
}
