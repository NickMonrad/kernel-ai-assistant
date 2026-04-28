package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-stage meal planning skill with stateful preference collection and iterative recipe generation.
 *
 * This is a "text-style" skill: it loads full instructions once (via load_skill), then the model
 * uses run_intent + bulk_add_to_list to save generated content. State is tracked through chat
 * history to handle context window management.
 *
 * Flow:
 *   Stage 1: Collect preferences (people count, dietary restrictions, protein, days)
 *   Stage 2: Generate high-level meal plan
 *   Stage 3: For each day, generate recipe details (ingredients + cooking steps) and save:
 *     - Add ingredients to shared "Shopping" list
 *     - Create daily recipe list (e.g. "Monday Pasta", "Tuesday Salmon") with method steps as checkoff items
 *   Stage 4: Iterate until all days are detailed
 *
 * Key rules:
 *   - Ask questions in 2-3 grouped batches, not one-at-one
 *   - Reference prior answers to show state persistence (e.g. "So for 4 vegetarians over 5 days...")
 *   - Save after each day (not waiting for all days)
 *   - Two lists per day: (1) shared shopping list, (2) recipe-specific list with cooking steps
 */
@Singleton
class MealPlannerSkill @Inject constructor() : Skill {

    override val name = "meal_planner"
    override val description = "Plan meals with preferences, generate recipes daily, and save to lists."

    override val schema = SkillSchema(
        parameters = emptyMap(),
        required = emptyList(),
    )

    override val examples = listOf(
        "Plan meals → user triggers meal planner skill by saying 'make me a meal plan' or 'plan my meals for 5 days'",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        // Text-style skill: just return success. The skill's fullInstructions guide the model
        // to use run_intent + bulk_add_to_list for saving. No execution needed here.
        return SkillResult.Success("Meal planner skill loaded. Follow the instructions to collect preferences, generate recipes, and save to lists.")
    }

    override val fullInstructions: String = """
meal_planner: Multi-stage meal planning with state persistence.

The system injects a [Meal Planner Session] context block with your current status and session data.
Follow the instructions in that block. Use save_meal_plan_state to persist progress after each stage.
""".trimIndent()
}
