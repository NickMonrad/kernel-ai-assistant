package com.kernel.ai.core.skills.mealplan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `extractProteinPreferences recognizes turkey and heuristic proteins`() {
        assertEquals(listOf("turkey"), extractor.extractProteinPreferences("Turkey"))
        assertEquals(listOf("turkey"), extractor.extractProteinPreferences("Turkey only"))
        assertEquals(listOf("snapper"), extractor.extractProteinPreferences("Snapper"))
        assertEquals(listOf("prawns"), extractor.extractProteinPreferences("Shrimp"))
        assertEquals(listOf("chickpeas"), extractor.extractProteinPreferences("Chickpeas"))
        assertEquals(listOf("halloumi"), extractor.extractProteinPreferences("Halloumi"))
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
    fun `extractDietaryRestrictions recognizes allergens lifestyles and ingredient exclusions`() {
        assertEquals(
            listOf(
                "kid friendly",
                "peanut free",
                "nut free",
                "sesame free",
                "pescatarian",
                "no aubergines",
                "no coriander",
            ),
            extractor.extractDietaryRestrictions(
                "Kid-friendly, nut allergy, peanut allergy, sesame allergy, pescatarian, no aubergines, without coriander",
            ),
        )
    }

    @Test
    fun `extractDietaryRestrictions keeps mixed exclusions and dietary phrases consistent`() {
        assertEquals(listOf("gluten free", "egg free"), extractor.extractDietaryRestrictions("No eggs and gluten free"))
        assertEquals(listOf("egg free", "dairy free"), extractor.extractDietaryRestrictions("No eggs and dairy"))
    }

    @Test
    fun `extractProteinPreferences ignores negated allergen and exclusion phrases`() {
        assertEquals(null, extractor.extractProteinPreferences("No egg"))
        assertEquals(null, extractor.extractProteinPreferences("egg free"))
        assertEquals(listOf("chicken"), extractor.extractProteinPreferences("egg free, chicken"))
        assertEquals(null, extractor.extractProteinPreferences("without chicken or beef"))
    }

    @Test
    fun `extract removal commands for dietary and protein preferences`() {
        assertEquals(listOf("kid friendly", "gluten free"), extractor.extractRemovedDietaryRestrictions("remove gluten free and kid friendly"))
        assertEquals(listOf("beef", "pork"), extractor.extractRemovedProteinPreferences("please remove beef and pork"))
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
        assertTrue(extractor.isGenerateRecipesRequest("ok, continue"))
        assertTrue(extractor.isGenerateRecipesRequest("I'd like to generate recipes"))
        assertTrue(extractor.isGenerateRecipesRequest("resume"))
        assertTrue(extractor.isGenerateRecipesRequest("make recipes"))
        assertTrue(extractor.isGenerateRecipesRequest("create recipes"))
        assertTrue(extractor.isGenerateRecipesRequest("start meal plan"))
        assertFalse(extractor.isGenerateRecipesRequest("make the recipe less spicy"))
        assertFalse(extractor.isGenerateRecipesRequest("make recipe less spicy"))
        assertFalse(extractor.isGenerateRecipesRequest("create a note"))
        assertFalse(extractor.isGenerateRecipesRequest("create meal plan later"))
        assertFalse(extractor.isGenerateRecipesRequest("start from scratch"))
        assertFalse(extractor.isGenerateRecipesRequest("start meal plan tomorrow"))
    }

    @Test
    fun `isShowCurrentPlanRequest recognizes inspection phrases`() {
        assertTrue(extractor.isShowCurrentPlanRequest("show current plan"))
        assertTrue(extractor.isShowCurrentPlanRequest("show the current plan"))
        assertTrue(extractor.isShowCurrentPlanRequest("show me the current plan"))
        assertTrue(extractor.isShowCurrentPlanRequest("what's my current plan"))
        assertTrue(extractor.isShowCurrentPlanRequest("what is my current plan"))
        assertTrue(extractor.isShowCurrentPlanRequest("what is the current plan"))
        assertFalse(extractor.isShowCurrentPlanRequest("show me the shopping list"))
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