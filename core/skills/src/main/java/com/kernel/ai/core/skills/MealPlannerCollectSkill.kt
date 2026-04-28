package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 1 of the meal planner: collect meal plan preferences.
 *
 * This is a "text-style" skill: it loads full instructions once (via load_skill), then the model
 * uses the conversation to gather preferences and calls save_meal_plan_state to persist them.
 *
 * Flow:
 *   - Ask questions in 2-3 grouped batches (not one-at-one)
 *   - Batch 1: people count + dietary restrictions
 *   - Batch 2: number of days + protein preferences
 *   - After collecting: call save_meal_plan_state with all gathered values
 *   - Reference prior answers to show state persistence
 *   - Do NOT generate the plan yet — just collect preferences
 *
 * The conversation_id parameter comes from the [Meal Planner Session] context block
 * injected by the system at the start of each turn.
 */
@Singleton
class MealPlannerCollectSkill @Inject constructor() : Skill {

    override val name = "meal_planner_collect"

    override val description =
        "Collect meal plan preferences: people count, days, dietary restrictions, and protein preferences."

    override val schema = SkillSchema(
        parameters = mapOf(
            "peopleCount" to SkillParameter(
                type = "integer",
                description = "Number of people the meal plan is for.",
            ),
            "days" to SkillParameter(
                type = "integer",
                description = "Number of days to plan for.",
            ),
            "dietaryRestrictions" to SkillParameter(
                type = "string",
                description = "JSON array of dietary restrictions, e.g. '[\"vegetarian\",\"gluten-free\"]'.",
            ),
            "proteinPreferences" to SkillParameter(
                type = "string",
                description = "JSON array of preferred proteins, e.g. '[\"chicken\",\"fish\",\"tofu\"]'.",
            ),
        ),
        required = emptyList(),
    )

    override val examples = listOf(
        "User says 'plan meals for 4 people, vegetarian, 5 days, chicken and fish' → collect all in one go",
        "User says '3 people, no gluten' → ask for days and protein preferences next",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        // Text-style skill: just return success. The skill's fullInstructions guide the model.
        return SkillResult.Success(
            "Meal planner collect skill loaded. Follow the instructions to collect preferences.",
        )
    }

    override val fullInstructions: String = """
meal_planner_collect: Collect meal plan preferences in 2-3 grouped batches, then persist via save_meal_plan_state.

RULES:
  - Ask questions in grouped batches, not one-at-one.
  - Batch 1: "How many people, and any dietary restrictions?"
  - Batch 2: "How many days and protein preferences (e.g. chicken, fish, vegetarian)?"
  - Reference prior answers to show state persistence (e.g. "So for 4 vegetarians over 5 days...").
  - If the user provides all preferences in one message, collect them all and call save_meal_plan_state immediately.
  - Do NOT generate the plan yet — only collect preferences.
  - Use METRIC/NZ units only: g, kg, ml, l, tsp, tbsp, Celsius, and whole-item counts.
  - conversation_id comes from the [Meal Planner Session] context block injected by the system.

STATE PERSISTENCE (critical):
  - After collecting preferences, call save_meal_plan_state to persist them:
      saveMealPlanState(
        conversation_id="<conv-id from session block>",
        status="collecting_preferences",
        people_count=<N>,
        days=<N>,
        dietary_restrictions='["..."]',
        protein_preferences='["..."]'
      )
  - If peopleCount or days is missing/null, still call save_meal_plan_state with whatever you have.
  - Pass only the fields that have changed — the database merges with existing state.

SESSION CONTEXT BLOCK:
  - At the start of each turn, the system may inject a [Meal Planner Session] block.
  - ALWAYS read it first. It contains authoritative session state (status, people count, days, etc.).
  - If your memory conflicts with the session block, trust the session block.
  - If status is already "collecting_preferences" and preferences exist, do NOT re-ask — proceed to Stage 2.

AFTER SAVE:
  - Once save_meal_plan_state succeeds, tell the user: "Great! I've saved your preferences. I'll put together a high-level plan now."
""".trimIndent()
}
