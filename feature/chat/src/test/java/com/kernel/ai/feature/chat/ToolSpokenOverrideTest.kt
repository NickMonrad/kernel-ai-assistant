package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which voice path speaks the tool's spoken summary instead of the card or the model's words. */
class ToolSpokenOverrideTest {

    private val card = "☀️ Oakland — 11°C · Clear sky\n🌡 Today: H:17°C / L:10°C\n💧 Humidity: 92%"
    private val summary = "In Oakland, it's 11 degrees Celsius, clear sky, high 17, low 10."

    @Test
    fun `model echo of the card speaks the summary`() {
        assertEquals(summary, toolSpokenOverrideFor(card, card, summary))
    }

    @Test
    fun `model reply that includes the card speaks the summary`() {
        assertEquals(summary, toolSpokenOverrideFor("Here you go:\n$card", card, summary))
    }

    @Test
    fun `whitespace differences do not defeat the echo match`() {
        assertEquals(summary, toolSpokenOverrideFor(card.replace("\n", "  "), card, summary))
    }

    @Test
    fun `paraphrase is spoken as written`() {
        assertNull(toolSpokenOverrideFor("It's sunny and about 11 degrees in Oakland.", card, summary))
    }

    @Test
    fun `no spoken summary means no override`() {
        assertNull(toolSpokenOverrideFor(card, card, null))
        assertNull(toolSpokenOverrideFor(card, card, "  "))
    }

    @Test
    fun `no tool result means no override`() {
        assertNull(toolSpokenOverrideFor(card, null, summary))
    }

    @Test
    fun `blank reply means no override`() {
        assertNull(toolSpokenOverrideFor("   ", card, summary))
    }

    @Test
    fun `short fragment of the card is not treated as an echo`() {
        assertNull(toolSpokenOverrideFor("Clear", card, summary))
    }
}
