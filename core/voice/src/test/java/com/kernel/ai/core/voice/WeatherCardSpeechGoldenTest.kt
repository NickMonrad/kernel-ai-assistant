package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
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
            "Oakland, 11°C, Clear sky. Today: high 17, low 10. humidity 92%",
            SpeechCardText.speakable(card),
        )
    }

    @Test
    fun `piper and kokoro spell out only what sherpa owns`() {
        assertEquals(
            "Oakland, 11°C, Clear sky. Today: high seventeen, low ten. humidity 92%.",
            SherpaSpeechText.prepare(SpeechCardText.speakable(card)),
        )
    }

    @Test
    fun `inflect spells out numbers through its own frontend`() {
        assertEquals(
            "Oakland, eleven°C, Clear sky. Today: high seventeen, low ten. humidity ninety two%",
            InflectMicroTextFrontend.normalize(SpeechCardText.speakable(card)),
        )
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
