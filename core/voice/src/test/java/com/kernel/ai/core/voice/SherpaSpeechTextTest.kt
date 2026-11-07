package com.kernel.ai.core.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SherpaSpeechTextTest {
    @Test
    fun `date difference reply is spelled out`() {
        val spoken = SherpaSpeechText.prepare("126 days from now (18 weeks) — Sunday, 14 March 2027.")
        assertFalse(spoken.any(Char::isDigit), spoken)
        assertTrue(spoken.startsWith("one hundred and twenty"), spoken)
        assertTrue("the fourteenth of March two thousand and twenty" in spoken, spoken)
        assertTrue(spoken.endsWith("."), spoken)
    }

    @Test
    fun `time reply gets a final full stop so the last word is not clipped`() {
        assertEquals("It's eight thirty one a m.", SherpaSpeechText.prepare("It's 8:31 am"))
    }

    @Test
    fun `existing terminal punctuation is kept`() {
        assertEquals("Done!", SherpaSpeechText.prepare("Done!"))
        assertEquals("Added \"bananas\" to your shopping list.", SherpaSpeechText.prepare("Added \"bananas\" to your shopping list."))
        assertEquals("Next,", SherpaSpeechText.prepare("Next,"))
    }

    @Test
    fun `shouted words and large counts read naturally`() {
        val spoken = SherpaSpeechText.prepare("That is VERY good: 15,000 steps")
        assertTrue("VERY" in spoken, spoken)
        assertTrue("fifteen thousand steps" in spoken, spoken)
    }

    @Test
    fun `day-month dates read as ordinals`() {
        assertEquals("Sunday, the first of June.", SherpaSpeechText.prepare("Sunday, 1 June"))
        assertEquals("the twenty second of March and the thirteenth of May.", SherpaSpeechText.prepare("22 March and 13 May"))
    }

    @Test
    fun `blank text stays blank`() {
        assertEquals("", SherpaSpeechText.prepare("   "))
    }

    @Test
    fun `inflect defaults are unchanged`() {
        assertEquals("en zee", InflectMicroTextFrontend.normalize("NZ"))
        assertEquals("one five oh oh oh", InflectMicroTextFrontend.normalize("15000"))
    }
}
