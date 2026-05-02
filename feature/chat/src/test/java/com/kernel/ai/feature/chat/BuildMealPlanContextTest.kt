package com.kernel.ai.feature.chat

import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildMealPlanContextTest {

    @Test
    fun `buildMealPlanContext returns empty string for null session`() {
        val result = buildMealPlanContext(null, null)

        assertEquals("", result)
    }

    @Test
    fun `buildMealPlanContext includes status for minimal session`() {
        val session = MealPlanSessionEntity(conversationId = "conv-1")
        val result = buildMealPlanContext(session, "conv-1")

        assertTrue(result.contains("[Meal Planner Session]"))
        assertTrue(result.contains("Status: collecting_preferences"))
        assertTrue(result.contains("[End Meal Planner Session]"))
    }

    @Test
    fun `buildMealPlanContext includes people count when set`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-2",
            peopleCount = 4,
        )
        val result = buildMealPlanContext(session, "conv-2")

        assertTrue(result.contains("People: 4"))
    }

    @Test
    fun `buildMealPlanContext excludes people count when null`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-3",
            peopleCount = null,
        )
        val result = buildMealPlanContext(session, "conv-3")

        assertFalse(result.contains("People:"))
    }

    @Test
    fun `buildMealPlanContext includes days when set`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-4",
            days = 5,
        )
        val result = buildMealPlanContext(session, "conv-4")

        assertTrue(result.contains("Days: 5"))
    }

    @Test
    fun `buildMealPlanContext excludes days when null`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-5",
            days = null,
        )
        val result = buildMealPlanContext(session, "conv-5")

        assertFalse(result.contains("Days:"))
    }

    @Test
    fun `buildMealPlanContext includes dietary restrictions when non-empty`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-6",
            dietaryRestrictionsJson = """["vegetarian","gluten-free"]""",
        )
        val result = buildMealPlanContext(session, "conv-6")

        assertTrue(result.contains("Dietary:"))
        assertTrue(result.contains("vegetarian"))
    }

    @Test
    fun `buildMealPlanContext excludes dietary restrictions when empty array`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-7",
            dietaryRestrictionsJson = "[]",
        )
        val result = buildMealPlanContext(session, "conv-7")

        assertFalse(result.contains("Dietary:"))
    }

    @Test
    fun `buildMealPlanContext includes protein preferences when non-empty`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-8",
            proteinPreferencesJson = """["chicken","fish"]""",
        )
        val result = buildMealPlanContext(session, "conv-8")

        assertTrue(result.contains("Proteins:"))
        assertTrue(result.contains("chicken"))
    }

    @Test
    fun `buildMealPlanContext excludes protein preferences when empty array`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-9",
            proteinPreferencesJson = "[]",
        )
        val result = buildMealPlanContext(session, "conv-9")

        assertFalse(result.contains("Proteins:"))
    }

    @Test
    fun `buildMealPlanContext includes high level plan when set`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-10",
            highLevelPlanJson = """{"day1":"Pasta","day2":"Salad"}""",
        )
        val result = buildMealPlanContext(session, "conv-10")

        assertTrue(result.contains("Plan:"))
        assertTrue(result.contains("Pasta"))
    }

    @Test
    fun `buildMealPlanContext excludes high level plan when null`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-11",
            highLevelPlanJson = null,
        )
        val result = buildMealPlanContext(session, "conv-11")

        assertFalse(result.contains("Plan:"))
    }

    @Test
    fun `buildMealPlanContext includes current day with 1-based label`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-12",
            currentDayIndex = 2,
        )
        val result = buildMealPlanContext(session, "conv-12")

        assertTrue(result.contains("Current day: 3"))
    }

    @Test
    fun `buildMealPlanContext excludes current day when null`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-13",
            currentDayIndex = null,
        )
        val result = buildMealPlanContext(session, "conv-13")

        assertFalse(result.contains("Current day:"))
    }

    @Test
    fun `buildMealPlanContext includes all fields when fully populated`() {
        val session = MealPlanSessionEntity(
            conversationId = "conv-full",
            status = "generating_recipes",
            peopleCount = 3,
            days = 4,
            dietaryRestrictionsJson = """["vegan"]""",
            proteinPreferencesJson = """["tofu"]""",
            highLevelPlanJson = """{"day1":"Tofu stir fry"}""",
            currentDayIndex = 1,
        )
        val result = buildMealPlanContext(session, "conv-full")

        assertTrue(result.contains("[Meal Planner Session]"))
        assertTrue(result.contains("Status: generating_recipes"))
        assertTrue(result.contains("People: 3"))
        assertTrue(result.contains("Days: 4"))
        assertTrue(result.contains("Dietary:"))
        assertTrue(result.contains("Proteins:"))
        assertTrue(result.contains("Plan:"))
        assertTrue(result.contains("Current day: 2"))
        assertTrue(result.contains("[End Meal Planner Session]"))
    }

    @Test
    fun `buildMealPlanContext includes conversation_id when provided`() {
        val session = MealPlanSessionEntity(conversationId = "conv-abc")
        val result = buildMealPlanContext(session, "conv-abc")

        assertTrue(result.contains("conversation_id: conv-abc"))
    }

    @Test
    fun `buildMealPlanContext omits conversation_id when null`() {
        val session = MealPlanSessionEntity(conversationId = "conv-xyz")
        val result = buildMealPlanContext(session, null)

        assertFalse(result.contains("conversation_id:"))
    }

    @Test
    fun `buildMealPlanContext output contains session block and save directive`() {
        val session = MealPlanSessionEntity(conversationId = "conv-block", status = "generating_recipes", currentDayIndex = 0)
        val result = buildMealPlanContext(session, "conv-block")

        assertTrue(result.contains("[Meal Planner Session]"))
        assertTrue(result.contains("[End Meal Planner Session]"))
        assertTrue(result.contains("saveMealPlanState"))
        assertTrue(result.contains("conversationId"))
        assertFalse(result.contains("[Current Task]"))
        assertTrue(result.contains("load_skill meal_planner_recipe"))
    }
}