package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolPresentationJsonTest {

    @Test
    fun `weather presentation round-trips through json`() {
        val original = ToolPresentation.Weather(
            locationName = "Wellington",
            temperatureText = "13°C / 12°C",
            feelsLikeText = "Feels like 11°C",
            description = "Rain",
            emoji = "🌧️",
            highLowText = "High 13°C • Low 12°C",
            humidityText = "Humidity 82%",
            windText = "Wind 4 m/s",
            precipText = "5mm rain",
            uvText = "UV 5 (Moderate) • Max 7",
            airQualityText = "AQI 12",
            sunText = "Sunrise 07:10 • Sunset 17:31",
        )

        val parsed = ToolPresentationJson.fromJsonString(ToolPresentationJson.toJsonString(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `list preview presentation round-trips through json`() {
        val original = ToolPresentation.ListPreview(
            title = "Shopping",
            items = listOf("milk", "eggs", "bread"),
            totalCount = 3,
            emptyMessage = null,
        )

        val parsed = ToolPresentationJson.fromJsonString(ToolPresentationJson.toJsonString(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `computed result presentation round-trips through json`() {
        val original = ToolPresentation.ComputedResult(
            primaryText = "125 days",
            contextText = "Until 22 Aug 2026",
            breakdownText = "17 weeks, 6 days",
        )

        val parsed = ToolPresentationJson.fromJsonString(ToolPresentationJson.toJsonString(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(ToolPresentationJson.fromJsonString("{not-valid-json"))
    }

    @Test
    fun `status presentation parses to correct subtype`() {
        val parsed = ToolPresentationJson.fromJsonString(
            ToolPresentationJson.toJsonString(
                ToolPresentation.Status(icon = "💾", title = "Remembered", subtitle = null),
            ),
        )

        assertInstanceOf(ToolPresentation.Status::class.java, parsed)
    }
}
