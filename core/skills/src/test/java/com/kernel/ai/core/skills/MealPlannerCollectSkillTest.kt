package com.kernel.ai.core.skills

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MealPlannerCollectSkillTest {

    private val skill = MealPlannerCollectSkill()

    @Test
    fun `skill name is meal_planner_collect`() {
        assertEquals("meal_planner_collect", skill.name)
    }

    @Test
    fun `skill has schema parameters`() {
        assertTrue(skill.schema.parameters.containsKey("peopleCount"))
        assertTrue(skill.schema.parameters.containsKey("days"))
        assertTrue(skill.schema.parameters.containsKey("dietaryRestrictions"))
        assertTrue(skill.schema.parameters.containsKey("proteinPreferences"))
    }

    @Test
    fun `execute returns success`() {
        val result = runBlocking { skill.execute(mockk()) }
        assertTrue(result is SkillResult.Success)
    }

    @Test
    fun `fullInstructions mentions save_meal_plan_state`() {
        assertTrue(skill.fullInstructions.contains("save_meal_plan_state"))
    }

    @Test
    fun `fullInstructions mentions conversation_id from session block`() {
        assertTrue(skill.fullInstructions.contains("conversation_id"))
        assertTrue(skill.fullInstructions.contains("Meal Planner Session"))
    }

    @Test
    fun `fullInstructions mentions METRIC units only`() {
        assertTrue(skill.fullInstructions.contains("METRIC"))
    }

    @Test
    fun `fullInstructions mentions batched questions`() {
        assertTrue(skill.fullInstructions.contains("batched") || skill.fullInstructions.contains("batch"))
    }
}
