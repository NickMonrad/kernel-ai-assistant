package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolPresentationSpokenSummaryTest {
    private fun weather(
        locationName: String = "Wellington",
        temperatureText: String = "25°C",
        feelsLikeText: String? = "Feels like 23°C",
        description: String = "Partly cloudy",
        emoji: String = "⛅",
        highLowText: String? = "H 27°C / L 18°C",
    ): ToolPresentation.Weather = ToolPresentation.Weather(
        locationName = locationName,
        temperatureText = temperatureText,
        feelsLikeText = feelsLikeText,
        description = description,
        emoji = emoji,
        highLowText = highLowText,
        humidityText = "Humidity 60%",
        windText = "NW 15 km/h",
        precipText = "20%",
        airQualityText = null,
    )

    @Test
    fun `toSpokenSummary returns non-null for Weather presentation`() {
        val presentation: ToolPresentation = weather()
        assertNotNull(presentation.toSpokenSummary())
    }

    @Test
    fun `toSpokenSummary returns null for Status presentation`() {
        val presentation: ToolPresentation = ToolPresentation.Status(icon = "✓", title = "Done")
        assertNull(presentation.toSpokenSummary())
    }

    @Test
    fun `toSpokenSummary returns null for ListPreview presentation`() {
        val presentation: ToolPresentation =
            ToolPresentation.ListPreview(title = "My list", items = listOf("A", "B"), totalCount = 2)
        assertNull(presentation.toSpokenSummary())
    }

    @Test
    fun `toSpokenSummary returns null for ComputedResult presentation`() {
        val presentation: ToolPresentation =
            ToolPresentation.ComputedResult(primaryText = "42", contextText = "Meaning of life")
        assertNull(presentation.toSpokenSummary())
    }

    @Test
    fun `weather spoken summary contains location name`() {
        val spoken = weather(locationName = "Wellington").toSpokenSummary()
        assertTrue(spoken.contains("Wellington"))
    }

    @Test
    fun `weather spoken summary strips country code from location`() {
        val spoken = weather(locationName = "Brisbane, AU").toSpokenSummary()
        assertTrue(spoken.contains("Brisbane"))
        assertFalse(spoken.contains(", AU"))
    }

    @Test
    fun `weather spoken summary expands temperature to degrees Celsius`() {
        val spoken = weather(temperatureText = "25°C").toSpokenSummary()
        assertTrue(spoken.contains("25 degrees Celsius"))
        assertFalse(spoken.contains("25°C"))
    }

    @Test
    fun `weather spoken summary contains description in original case`() {
        val spoken = weather(description = "Partly cloudy").toSpokenSummary()
        assertTrue(spoken.contains("Partly cloudy"))
    }

    @Test
    fun `weather spoken summary includes feels-like temperature when present`() {
        val spoken = weather(feelsLikeText = "Feels like 23°C").toSpokenSummary()
        assertTrue(spoken.contains("23 degrees Celsius"))
        assertFalse(spoken.contains("23°C"))
    }

    @Test
    fun `weather spoken summary omits feels-like when null`() {
        val spoken = weather(feelsLikeText = null).toSpokenSummary()
        assertFalse(spoken.contains("feels"))
    }

    @Test
    fun `weather spoken summary does not contain high-low display text`() {
        val spoken = weather(highLowText = "H 27°C / L 18°C").toSpokenSummary()
        assertFalse(spoken.contains("H 27°C"))
        assertFalse(spoken.contains("/ L"))
    }

    @Test
    fun `weather spoken summary does not contain emoji`() {
        val spoken = weather(emoji = "⛅").toSpokenSummary()
        assertFalse(spoken.contains("⛅"))
    }

    @Test
    fun `weather spoken summary does not contain degree symbol`() {
        val spoken = weather().toSpokenSummary()
        assertFalse(spoken.contains("°"))
    }

    @Test
    fun `weather spoken summary differs from raw display text`() {
        val displayText = "Wellington forecast: 25°C, feels like 23°C. H 27°C / L 18°C. Wind NW 15 km/h."
        val spoken = weather().toSpokenSummary()
        assertTrue(spoken != displayText)
    }

    @Test
    fun `weather spoken summary omits Unknown description`() {
        val spoken = weather(description = "Unknown").toSpokenSummary()
        assertFalse(spoken.contains("Unknown"))
        assertFalse(spoken.contains("unknown"))
    }

    @Test
    fun `weather spoken summary handles negative temperature`() {
        val spoken = weather(
            temperatureText = "-3°C",
            feelsLikeText = "Feels like -6°C",
        ).toSpokenSummary()
        assertTrue(spoken.contains("-3 degrees Celsius"))
        assertTrue(spoken.contains("-6 degrees Celsius"))
    }

    @Test
    fun `weather spoken summary handles Fahrenheit temperature`() {
        val spoken = weather(
            temperatureText = "77°F",
            feelsLikeText = "Feels like 74°F",
        ).toSpokenSummary()
        assertTrue(spoken.contains("77 degrees Fahrenheit"))
        assertFalse(spoken.contains("77°F"))
    }

    @Test
    fun `expandTemperatureUnits expands Celsius`() {
        assertEquals("25 degrees Celsius", "25°C".expandTemperatureUnits())
    }

    @Test
    fun `expandTemperatureUnits expands Fahrenheit`() {
        assertEquals("77 degrees Fahrenheit", "77°F".expandTemperatureUnits())
    }

    @Test
    fun `expandTemperatureUnits expands negative Celsius`() {
        assertEquals("-3 degrees Celsius", "-3°C".expandTemperatureUnits())
    }

    @Test
    fun `expandTemperatureUnits expands both units in same string`() {
        val result = "Feels like 23°C or 73°F".expandTemperatureUnits()
        assertTrue(result.contains("23 degrees Celsius"))
        assertTrue(result.contains("73 degrees Fahrenheit"))
    }

    @Test
    fun `expandTemperatureUnits passes through strings without units`() {
        val noUnit = "This has no temperature notation."
        assertEquals(noUnit, noUnit.expandTemperatureUnits())
    }
}
