package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Card notation is cleaned here and units/dates spelled out (#1901, #2077). Plain numbers are left to each engine's own prep. */
class SpeakableCardTextTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = ';',
        quoteCharacter = '`',
        value = [
            "H:17°C / L:10°C ; high 17, low 10",
            "Today: H:17°C / L:10°C ; Today: high 17, low 10",
            "💧 Humidity: 92% ; humidity 92 percent",
            "Humidity: 92% | 💨 Wind: 1.9 m/s ; humidity 92 percent, Wind: 1.9 metres per second",
            "☀️ Oakland — Clear sky / 🌡 Today ; Oakland, Clear sky, Today",
            "Mostly sunny ☀️ ; Mostly sunny",
            "Clock 8:31 and 14/03 stay ; Clock 8:31 and 14/03 stay",
            "See https://example.com/page-2 for details ; See for details",
            "Note: it's late ; Note: it's late",
            "Shipping → Auckland ; Shipping to Auckland",
            "H:17°F / L:9°F ; high 17 Fahrenheit, low 9 Fahrenheit",
        ],
    )
    fun `card notation is cleaned and nothing else changes`(input: String, expected: String) {
        assertEquals(expected, SpeechCardText.speakable(input))
    }

    @Test
    fun `line breaks after clause punctuation do not double the full stop`() {
        assertEquals("Done. Next", SpeechCardText.speakable("Done.\nNext"))
        assertEquals("list: - milk", SpeechCardText.speakable("list:\n- milk"))
    }

    @Test
    fun `closing quote or bracket before a line break gets no second full stop`() {
        assertEquals("He said \"not wings.\" Next", SpeechCardText.speakable("He said \"not wings.\"\nNext"))
        assertEquals("He said “not wings.” Next", SpeechCardText.speakable("He said “not wings.”\nNext"))
        assertEquals("He asked \"why?\" Next", SpeechCardText.speakable("He asked \"why?\"\nNext"))
    }

    @Test
    fun `wikipedia read-more link and its title are not spoken`() {
        assertEquals(
            "Kiwi are flightless birds.",
            SpeechCardText.speakable("Kiwi are flightless birds.\n\nRead more: https://en.wikipedia.org/wiki/Kiwi_(bird)"),
        )
        assertEquals("Kiwi (bird) is a bird.", SpeechCardText.speakable("Kiwi_(bird) is a bird."))
    }
}
