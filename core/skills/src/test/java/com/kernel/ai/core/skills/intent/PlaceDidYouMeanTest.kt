package com.kernel.ai.core.skills.intent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaceDidYouMeanTest {

    private val nz = listOf("Porirua", "Paraparaumu", "Whangārei", "Tauranga", "Taupō")
    private val competitors = listOf("Perth", "Adelaide", "Parramatta")
    private val suggester = PlaceDidYouMeanSuggester { PlaceSlotMatcher(nz, competitors) }

    @Test
    fun nearMissOfNzPlaceIsSuggested() {
        assertEquals("Porirua", suggester.suggest("Porirwa"))
    }

    @Test
    fun exactNzSpellingIsNotAskedAbout() {
        assertNull(suggester.suggest("Porirua"))
    }

    @Test
    fun unrelatedWordIsNotAskedAbout() {
        assertNull(suggester.suggest("pizza"))
    }

    @Test
    fun competitorNearerThanNzSuppressesQuestion() {
        // Perth is an exact-enough competitor for "Perht"; NZ top must not win over it.
        val tight = PlaceDidYouMeanSuggester { PlaceSlotMatcher(listOf("Perhaps"), listOf("Perth")) }
        assertNull(tight.suggest("Perht"))
    }

    @Test
    fun yesNoAndOtherReplies() {
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("Yes."))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("  yeah "))
        assertEquals(PlaceDidYouMeanAnswer.No, PlaceDidYouMeanReply.interpret("No"))
        assertEquals(PlaceDidYouMeanAnswer.No, PlaceDidYouMeanReply.interpret("nope!"))
        assertEquals(PlaceDidYouMeanAnswer.Other, PlaceDidYouMeanReply.interpret("Taupo"))
        assertEquals(PlaceDidYouMeanAnswer.Other, PlaceDidYouMeanReply.interpret(""))
    }

    @Test
    fun promptNamesSuggestedPlace() {
        val pending = PendingPlaceDidYouMean(
            originalText = "navigate to Porirwa",
            suggestedText = "navigate to Porirua",
            suggestedPlace = "Porirua",
        )
        assertEquals("Did you mean Porirua?", pending.prompt)
        assertEquals(2, PlaceDidYouMeanConfig.SILENCE_BUDGET)
    }

    @Test
    fun suggestedUtteranceReplacesHeardPlaceOnly() {
        assertEquals(
            "weather in Porirua",
            suggestedUtterance("weather in Porirwa", "Porirwa", "Porirua"),
        )
        assertNull(suggestedUtterance("weather", "Porirwa", "Porirua"))
    }
}
