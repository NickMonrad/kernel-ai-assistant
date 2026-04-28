package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerSkillTest {

    private val skill = MealPlannerSkill()

    @Test
    fun `fullInstructions redirect to stage-specific skills based on session status`() {
        val instructions = skill.fullInstructions

        // MealPlannerSkill is now a redirect — it tells the model which stage skill to load.
        assertTrue(instructions.contains("load_skill(skill_name=\"meal_planner_collect\")"))
        assertTrue(instructions.contains("load_skill(skill_name=\"meal_planner_plan\")"))
        assertTrue(instructions.contains("load_skill(skill_name=\"meal_planner_recipe\")"))
        assertTrue(instructions.contains("status == \"collecting_preferences\""))
        assertTrue(instructions.contains("status == \"generating_recipes\""))
        assertTrue(instructions.contains("status == \"completed\""))
    }
}
