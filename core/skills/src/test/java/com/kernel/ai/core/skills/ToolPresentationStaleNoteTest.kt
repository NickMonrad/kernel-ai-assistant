package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #1921 — the stale-forecast note must survive chat persistence, or reloaded cards lose the label. */
class ToolPresentationStaleNoteTest {
    @Test
    fun `stale note survives JSON save and reload`() {
        val note = "Forecast from 9:40 AM. The weather service isn't answering, so it may be out of date."
        val weather = ToolPresentation.Weather(
            locationName = "Wellington",
            temperatureText = "13°C",
            feelsLikeText = null,
            description = "Cloudy",
            emoji = "☁",
            humidityText = null,
            windText = null,
            precipText = null,
            airQualityText = null,
            staleNoteText = note,
        )

        val reloaded = ToolPresentationJson.fromJsonString(ToolPresentationJson.toJsonString(weather))

        assertEquals(note, (reloaded as ToolPresentation.Weather).staleNoteText)
    }
}
