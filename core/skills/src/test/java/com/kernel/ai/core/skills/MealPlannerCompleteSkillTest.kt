package com.kernel.ai.core.skills

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MealPlannerCompleteSkillTest {

    private val skill = MealPlannerCompleteSkill()

    @Test
    fun `skill name is meal_planner_complete`() {
        assertEquals("meal_planner_complete", skill.name)
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
    fun `fullInstructions mentions saveMealPlanState with completed status`() {
        assertTrue(skill.fullInstructions.contains("saveMealPlanState"))
        assertTrue(skill.fullInstructions.contains("completed"))
    }

    @Test
    fun `fullInstructions mentions conversationId from session block`() {
        assertTrue(skill.fullInstructions.contains("conversationId"))
        assertTrue(skill.fullInstructions.contains("Meal Planner Session"))
    }

    @Test
    fun `fullInstructions mentions not generating more recipes`() {
        assertTrue(skill.fullInstructions.contains("NOT generate any more recipes") ||
            skill.fullInstructions.contains("Do NOT generate any more recipes"))
    }
}
