package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerSkillTest {

    private val skill = MealPlannerSkill()

    @Test
    fun `fullInstructions describe saving to meal plan and shopping list`() {
        val instructions = skill.fullInstructions

        assertTrue(instructions.contains("meal plan"))
        assertTrue(instructions.contains("shopping list"))
        assertTrue(instructions.contains("bulk_add_to_list"))
        assertTrue(instructions.contains("Do NOT use save_memory"))
    }
}
