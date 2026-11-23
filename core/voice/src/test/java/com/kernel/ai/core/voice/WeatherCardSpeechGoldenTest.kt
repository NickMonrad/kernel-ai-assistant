package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A real weather card run through each engine's full speech pipeline, as the controllers do it:
 * SpeechCardText first, then the engine's own preparation. Each expected string is the exact
 * text that engine synthesises.
 */
class WeatherCardSpeechGoldenTest {

    private val card = "☀️ Oakland — 11°C · Clear sky\n🌡 Today: H:17°C / L:10°C\n💧 Humidity: 92%"

    @Test
    fun `android tts gets the card cleaned and nothing else`() {
        assertEquals(
            "Oakland, 11 degrees, Clear sky. Today: high 17, low 10. humidity 92 percent",
            SpeechCardText.speakable(card),
        )
    }

    @Test
    fun `piper and kokoro spell out only what sherpa owns`() {
        assertEquals(
            "Oakland, eleven degrees, Clear sky. Today: high seventeen, low ten. humidity ninety two percent.",
            SherpaSpeechText.prepare(SpeechCardText.speakable(card)),
        )
    }

    @Test
    fun `inflect spells out numbers through its own frontend`() {
        assertEquals(
            "Oakland, eleven degrees, Clear sky. Today: high seventeen, low ten. humidity ninety two percent",
            InflectMicroTextFrontend.normalize(SpeechCardText.speakable(card)),
        )
    }

    @Test
    fun `dated weather day reaches inflect with no digits, symbols or abbreviations`() {
        val day = "Mon 23 Nov: ☁️ Overcast, high 27°C, low 19°C, 0mm rain | UV max: 9 (Very High) | 🌅 04:46 / 18:22"
        val spoken = InflectMicroTextFrontend.normalize(SpeechCardText.speakable(day))
        assertTrue(spoken.startsWith("Monday the twenty third of November: Overcast"), spoken)
        assertFalse(Regex("[0-9°%|/]").containsMatchIn(spoken), spoken)
    }

    @Test
    fun `card cleanup is idempotent so chat and controller passes can both run`() {
        val once = SpeechCardText.speakable(card)
        assertEquals(once, SpeechCardText.speakable(once))
    }

    @Test
    fun `installed lexicon entry and card cleanup both apply in sherpa order`() {
        PronunciationLexicon.install(listOf(PronunciationLexicon.Entry("Taupō", "Toe-paw", false)))
        try {
            assertEquals(
                "Toe-paw, high seventeen.",
                SherpaSpeechText.prepare(SpeechCardText.speakable("☀️ Taupō — H:17°C")),
            )
        } finally {
            PronunciationLexicon.install(emptyList())
        }
    }
}
