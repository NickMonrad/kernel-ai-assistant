package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.QuickIntentRouter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceDidYouMeanTest {

    private val nz = listOf("Porirua", "Paraparaumu", "Whangārei", "Tauranga", "Taupō")
    private val competitors = listOf("Perth", "Adelaide", "Parramatta")
    private val suggester = PlaceDidYouMeanSuggester { PlaceSlotMatcher(nz, competitors) }

    @Test
    fun nearMissOfNzPlaceIsSuggested() {
        assertEquals("Porirua", suggester.suggest("Porirwa")?.place)
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
    fun knownLocalityIsNeverAskedEvenWhenNearAnNzName() {
        // "Orewa" is one edit from the NZ "Moerewa"; a known locality is a real place, so there is no question.
        val withoutKnown = PlaceDidYouMeanSuggester { PlaceSlotMatcher(listOf("Moerewa")) }
        assertEquals("Moerewa", withoutKnown.suggest("Orewa")?.place)
        val withKnown = PlaceDidYouMeanSuggester { PlaceSlotMatcher(listOf("Moerewa"), emptyList(), listOf("Orewa")) }
        assertNull(withKnown.suggest("Orewa"))
    }

    @Test
    fun stopListWordIsNeverAskedEvenWhenNearAnNzName() {
        val stop = PlaceDidYouMeanSuggester { PlaceSlotMatcher(listOf("Parakai")) }
        assertNull(stop.suggest("park"))
    }

    @Test
    fun possessiveNameIsNeverAsked() {
        val possessive = PlaceDidYouMeanSuggester { PlaceSlotMatcher(listOf("Thames")) }
        assertNull(possessive.suggest("Tom's place"))
        assertNull(possessive.suggest("toms place"))
    }

    @Test
    fun yesNoAndOtherReplies() {
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("Yes."))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("  yeah "))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("ok"))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("Okay!"))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("yes please"))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("yes, please"))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("Yes,please"))
        assertEquals(PlaceDidYouMeanAnswer.Yes, PlaceDidYouMeanReply.interpret("That\u2019s right."))
        assertEquals(PlaceDidYouMeanAnswer.No, PlaceDidYouMeanReply.interpret("No,"))
        assertEquals(PlaceDidYouMeanAnswer.Other, PlaceDidYouMeanReply.interpret("Taupo, please"))
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
            suggestedUtterance("weather in Porirwa", "Porirwa", PlaceSuggestion("Porirua", 0..6)),
        )
        assertNull(suggestedUtterance("weather", "Porirwa", PlaceSuggestion("Porirua", 0..6)))
    }

    @Test
    fun addressDetailsSurviveTheYes() {
        val question = placeDidYouMeanQuestion(suggester, navigateRoute("12 Porirwa Street"), "navigate to 12 Porirwa Street")
        assertEquals("Did you mean Porirua?", question?.prompt)
        assertEquals("navigate to 12 Porirua Street", question?.suggestedText)
    }

    private fun navigateRoute(place: String) = QuickIntentRouter.RouteResult.RegexMatch(
        intent = QuickIntentRouter.MatchedIntent(
            intentName = "navigate_to",
            params = mapOf("destination" to place),
            source = "regex",
        ),
    )

    @Test
    fun questionIsAskedWhenMatcherIsFast() {
        val question = runBlocking {
            placeDidYouMeanQuestionWithin(suggester, navigateRoute("Porirwa"), "navigate to Porirwa")
        }
        assertEquals("Did you mean Porirua?", question?.prompt)
        assertEquals("navigate to Porirua", question?.suggestedText)
    }

    private fun classifierRoute(intentName: String, slot: String, place: String) =
        QuickIntentRouter.RouteResult.ClassifierMatch(
            intent = QuickIntentRouter.MatchedIntent(
                intentName = intentName,
                params = mapOf(slot to place),
                source = "classifier",
            ),
            confidence = 0.9f,
            needsConfirmation = false,
        )

    @Test
    fun classifierRoutedNavigateIsAskedAbout() {
        val question = placeDidYouMeanQuestion(suggester, classifierRoute("navigate_to", "destination", "Porirwa"), "Navigate to Porirwa")
        assertEquals("Did you mean Porirua?", question?.prompt)
        assertEquals("Navigate to Porirua", question?.suggestedText)
    }

    @Test
    fun classifierRoutedWeatherLocationIsAskedAbout() {
        val question = placeDidYouMeanQuestion(suggester, classifierRoute("get_weather", "location", "Porirwa"), "weather in Porirwa")
        assertEquals("Did you mean Porirua?", question?.prompt)
        assertEquals("weather in Porirua", question?.suggestedText)
    }

    @Test
    fun classifierRoutedOtherIntentIsNotAskedAbout() {
        assertNull(placeDidYouMeanQuestion(suggester, classifierRoute("set_timer", "destination", "Porirwa"), "set timer Porirwa"))
    }

    @Test
    fun slowMatcherGivesNoQuestionWithinBound() {
        // The matcher blocks on a real thread; the bound must return without waiting for the rest of it.
        val slow = PlaceDidYouMeanSuggester {
            Thread.sleep(1_000)
            PlaceSlotMatcher(nz, competitors)
        }
        val start = System.nanoTime()
        val question = runBlocking {
            placeDidYouMeanQuestionWithin(slow, navigateRoute("Porirwa"), "navigate to Porirwa", timeoutMs = 100)
        }
        assertNull(question)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 900)
    }

    @Test
    fun failingMatcherGivesNoQuestion() {
        val broken = PlaceDidYouMeanSuggester { error("asset missing") }
        val question = runBlocking {
            placeDidYouMeanQuestionWithin(broken, navigateRoute("Porirwa"), "navigate to Porirwa")
        }
        assertNull(question)
    }
}
