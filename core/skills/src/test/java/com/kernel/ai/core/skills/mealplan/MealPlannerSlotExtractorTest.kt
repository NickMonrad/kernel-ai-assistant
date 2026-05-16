package com.kernel.ai.core.skills.mealplan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerSlotExtractorTest {
    private val extractor = MealPlannerSlotExtractor()

    @Test
    fun `extractors parse combined meal planner requirements`() {
        val input = "Plan meals for 4 people for 3 days, low lactose, chicken and beef mince"

        assertEquals(4, extractor.extractPeopleCount(input))
        assertEquals(3, extractor.extractDaysCount(input))
        assertEquals(listOf("low lactose"), extractor.extractDietaryRestrictions(input))
        assertEquals(listOf("chicken", "beef mince"), extractor.extractProteinPreferences(input))
    }

    @Test
    fun `negative dietary and protein answers normalize to concrete markers`() {
        assertEquals(
            listOf("no dietary requirements"),
            extractor.extractDietaryRestrictions("No dietary requirements"),
        )
        assertEquals(
            listOf("no dietary requirements"),
            extractor.extractDietaryRestrictions("No dietary"),
        )
        assertEquals(
            listOf("no dietary requirements"),
            extractor.extractDietaryRestrictions("No restrictions"),
        )
        assertEquals(
            listOf("no dietary requirements"),
            extractor.extractDietaryRestrictions("No requirements"),
        )
        assertEquals(
            listOf("no dietary requirements"),
            extractor.extractDietaryRestrictions("None"),
        )
        assertEquals(
            listOf("no protein preference"),
            extractor.extractProteinPreferences("Any protein is fine"),
        )
        assertEquals(
            listOf("no protein preference"),
            extractor.extractProteinPreferences("No preferences"),
        )
        assertEquals(
            listOf("no protein preference"),
            extractor.extractProteinPreferences("None", allowBareNoPreference = true),
        )
        assertEquals(
            listOf("no protein preference"),
            extractor.extractProteinPreferences("Any", allowBareNoPreference = true),
        )
    }

    @Test
    fun `extractReplaceDayIndex parses one based day number`() {
        assertEquals(1, extractor.extractReplaceDayIndex("replace day 2"))
    }

    @Test
    fun `extractRegenerateDayIndex parses one based day number`() {
        assertEquals(2, extractor.extractRegenerateDayIndex("regenerate day 3"))
    }

    @Test
    fun `isCancelRequest recognizes meal planning cancellation`() {
        assertTrue(extractor.isCancelRequest("cancel the meal plan"))
    }

    @Test
    fun `isGenerateRecipesRequest recognizes approval and resume phrases`() {
        assertTrue(extractor.isGenerateRecipesRequest("generate"))
        assertTrue(extractor.isGenerateRecipesRequest("generate recipes"))
        assertTrue(extractor.isGenerateRecipesRequest("resume"))
    }

    @Test
    fun `isRetryRequest recognizes retry phrases`() {
        assertTrue(extractor.isRetryRequest("Retry"))
        assertTrue(extractor.isRetryRequest("try again"))
    }

    @Test
    fun `isChangePreferencesRequest recognizes edit request`() {
        assertTrue(extractor.isChangePreferencesRequest("change preferences"))
    }
}