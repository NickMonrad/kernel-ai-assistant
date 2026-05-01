package com.kernel.ai.core.skills

import org.json.JSONObject
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

    @Test
    fun `forecast day round-trips through json`() {
        val original = ToolPresentation.ForecastDay(
            date = "Mon 31 Mar",
            emoji = "⛅",
            description = "Partly cloudy",
            highText = "High 22°C",
            lowText = "Low 14°C",
            precipText = "2.5mm rain",
            uvText = "UV max 6 (High)",
            sunText = "Sunrise 06:45 • Sunset 18:30",
        )

        val parsed = ToolPresentationJson.fromForecastJson(ToolPresentationJson.toForecastJson(listOf(original)))

        assertInstanceOf(ToolPresentation.ForecastDay::class.java, parsed[0])
        assertEquals(original, parsed[0])
    }

    @Test
    fun `weather presentation with forecast round-trips through json`() {
        val original = ToolPresentation.Weather(
            locationName = "Auckland",
            temperatureText = "18°C / 14°C",
            feelsLikeText = "Feels like 16°C",
            description = "Partly cloudy",
            emoji = "⛅",
            highLowText = "High 18°C • Low 14°C",
            humidityText = "Humidity 70%",
            windText = "Wind 12 km/h",
            precipText = "10mm rain",
            uvText = "UV max 5 (Moderate)",
            airQualityText = null,
            sunText = "Sunrise 07:00 • Sunset 17:30",
            forecast = listOf(
                ToolPresentation.ForecastDay(
                    date = "Mon 31 Mar",
                    emoji = "🌧️",
                    description = "Rain",
                    highText = "High 16°C",
                    lowText = "Low 10°C",
                    precipText = "15mm rain",
                    uvText = "UV max 3 (Moderate)",
                    sunText = "Sunrise 07:01 • Sunset 17:29",
                ),
                ToolPresentation.ForecastDay(
                    date = "Tue 01 Apr",
                    emoji = "⛅",
                    description = "Partly cloudy",
                    highText = "High 20°C",
                    lowText = "Low 13°C",
                    precipText = null,
                    uvText = "UV max 7 (High)",
                    sunText = "Sunrise 07:00 • Sunset 17:30",
                ),
            ),
        )

        val parsed = ToolPresentationJson.fromJsonString(ToolPresentationJson.toJsonString(original))

        assertEquals(original, parsed)
        assertInstanceOf(ToolPresentation.Weather::class.java, parsed)
        val weather = parsed as ToolPresentation.Weather
        assertEquals(2, weather.forecast.size)
    }

    @Test
    fun `weather presentation without forecast omits forecast key`() {
        val original = ToolPresentation.Weather(
            locationName = "Christchurch",
            temperatureText = "22°C",
            feelsLikeText = "Feels like 20°C",
            description = "Clear sky",
            emoji = "☀️",
            highLowText = "High 22°C",
            humidityText = null,
            windText = null,
            precipText = null,
            uvText = null,
            airQualityText = null,
            sunText = null,
        )

        val json = ToolPresentationJson.toJsonString(original)

        // forecast key should not be present when empty
        assert(!json.contains("forecast"))
    }
}
