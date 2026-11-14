package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Only card notation is cleaned here. Numbers and percentages are left to each engine's own prep. */
class SpeakableCardTextTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        quoteCharacter = '`',
        value = [
            "H:17°C / L:10°C ; high 17, low 10",
            "Today: H:17°C / L:10°C ; Today: high 17, low 10",
            "💧 Humidity: 92% ; humidity 92%",
            "Humidity: 92% | 💨 Wind: 1.9 m/s ; humidity 92%, Wind: 1.9 m/s",
            "☀️ Oakland — Clear sky / 🌡 Today ; Oakland, Clear sky, Today",
            "Mostly sunny ☀️ ; Mostly sunny",
            "Clock 8:31 and 14/03 stay ; Clock 8:31 and 14/03 stay",
            "See https://example.com/page-2 ; See https://example.com/page-2",
            "Note: it's late ; Note: it's late",
        ],
    )
    fun `card notation is cleaned and nothing else changes`(input: String, expected: String) {
        assertEquals(expected, SpeechCardText.speakable(input))
    }
}
