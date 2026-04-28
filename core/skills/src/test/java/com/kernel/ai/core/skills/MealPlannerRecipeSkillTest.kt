package com.kernel.ai.core.skills

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MealPlannerRecipeSkillTest {

    private val skill = MealPlannerRecipeSkill()

    @Test
    fun `skill name is meal_planner_recipe`() {
        assertEquals("meal_planner_recipe", skill.name)
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
    fun `fullInstructions mentions save_meal_plan_state`() {
        assertTrue(skill.fullInstructions.contains("save_meal_plan_state"))
    }

    @Test
    fun `fullInstructions mentions conversation_id from session block`() {
        assertTrue(skill.fullInstructions.contains("conversation_id"))
        assertTrue(skill.fullInstructions.contains("Meal Planner Session"))
    }

    @Test
    fun `fullInstructions mentions currentDayIndex`() {
        assertTrue(skill.fullInstructions.contains("currentDayIndex"))
    }

    @Test
    fun `fullInstructions mentions METRIC units only`() {
        assertTrue(skill.fullInstructions.contains("METRIC"))
    }

    @Test
    fun `fullInstructions mentions bulk_add_to_list`() {
        assertTrue(skill.fullInstructions.contains("bulk_add_to_list"))
    }

    @Test
    fun `fullInstructions mentions shopping list`() {
        assertTrue(skill.fullInstructions.contains("shopping list"))
    }
}
