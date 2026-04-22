package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealPlannerSkillTest {

    private val skill = MealPlannerSkill()

    @Test
    fun `fullInstructions enforce staged flow and bulk list saves`() {
        val instructions = skill.fullInstructions

        assertTrue(instructions.contains("IMPORTANT START RULE"))
        assertTrue(instructions.contains("Do NOT call run_intent to start meal planning"))
        assertTrue(instructions.contains("Stage 1"))
        assertTrue(instructions.contains("Stage 3"))
        assertTrue(instructions.contains("Shopping"))
        assertTrue(instructions.contains("bulk_add_to_list"))
        assertTrue(instructions.contains("CRITICAL SAVE RULE"))
    }
}
