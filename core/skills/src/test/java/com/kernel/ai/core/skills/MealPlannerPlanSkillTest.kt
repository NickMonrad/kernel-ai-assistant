package com.kernel.ai.core.skills

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MealPlannerPlanSkillTest {

    private val skill = MealPlannerPlanSkill()

    @Test
    fun `skill name is meal_planner_plan`() {
        assertEquals("meal_planner_plan", skill.name)
    }

    @Test
    fun `skill has empty schema parameters`() {
        assertTrue(skill.schema.parameters.isEmpty())
    }

    @Test
    fun `execute returns success`() {
        val result = runBlocking { skill.execute(mockk()) }
        assertTrue(result is SkillResult.Success)
    }

    @Test
    fun `fullInstructions mentions saveMealPlanState`() {
        assertTrue(skill.fullInstructions.contains("saveMealPlanState"))
    }

    @Test
    fun `fullInstructions mentions conversationId from session block`() {
        assertTrue(skill.fullInstructions.contains("conversationId"))
        assertTrue(skill.fullInstructions.contains("Meal Planner Session"))
    }

    @Test
    fun `fullInstructions mentions high_level_plan_ready status`() {
        assertTrue(skill.fullInstructions.contains("high_level_plan_ready"))
    }

    @Test
    fun `fullInstructions mentions METRIC units only`() {
        assertTrue(skill.fullInstructions.contains("METRIC"))
    }

    @Test
    fun `fullInstructions mentions not generating recipes`() {
        assertTrue(skill.fullInstructions.contains("NOT generate recipes") || skill.fullInstructions.contains("just dish names"))
    }
}
