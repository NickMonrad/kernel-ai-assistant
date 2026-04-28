package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerSkillTest {

    private val skill = MealPlannerSkill()

    @Test
    fun `fullInstructions is concise and delegates to session block`() {
        val instructions = skill.fullInstructions

        // MealPlannerSkill is now concise — it delegates to the session context block.
        assertTrue(instructions.contains("[Meal Planner Session]"))
        assertTrue(instructions.contains("saveMealPlanState"))
        assertTrue(instructions.contains("Follow the instructions in that block"))
    }
}
