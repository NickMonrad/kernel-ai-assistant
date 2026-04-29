package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 2 of meal planning: generate a high-level meal plan.
 *
 * This is a "text-style" skill: it loads full instructions once (via load_skill), then the model
 * uses the session block for context and calls save_meal_plan_state to persist the plan.
 *
 * Flow:
 *   - Read [Meal Planner Session] block for collected preferences (Stage 1 output)
 *   - Generate a high-level plan (one dish per day, no recipes yet)
 *   - Show the plan to the user and ask if they're ready for detailed recipes
 *   - Call save_meal_plan_state with status="high_level_plan_ready"
 *
 * If status is already "high_level_plan_ready", re-show the plan (context may have been lost).
 * If no preferences exist in the session, ask the user for them first and defer to Stage 1.
 */
@Singleton
class MealPlannerPlanSkill @Inject constructor() : Skill {

    override val name = "meal_planner_plan"
    override val description = "Generate a high-level meal plan for the specified days and preferences."

    override val schema = SkillSchema(
        parameters = emptyMap(),
        required = emptyList(),
    )

    override val examples = listOf(
        "Plan meals → user triggers meal planner plan skill by saying 'make me a meal plan' or 'plan my meals for 5 days'",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        // Text-style skill: just return success. The skill's fullInstructions guide the model
        // to use the session block and save_meal_plan_state. No execution needed here.
        return SkillResult.Success("Meal planner plan skill loaded. Follow the instructions to generate a high-level plan.")
    }

    override val fullInstructions: String = """
meal_planner_plan: Generate a high-level meal plan for the specified days and preferences.

IMPORTANT: Load with load_skill first. Do NOT call run_intent. Only call saveMealPlanState after showing the plan.

SESSION CONTEXT BLOCK: At the start of each turn, the system may inject a [Meal Planner Session] block.
ALWAYS read this first. It contains: status, people_count, days, dietary_restrictions, protein_preferences, high_level_plan.
Treat the session block as ground truth. If your memory conflicts, trust the session block.
Status values and actions:
  collecting_preferences  -> ask the user for people, restrictions, protein, days (Stage 1).
  high_level_plan_ready   -> plan already generated; re-show it (context may have been lost).
  generating_recipes      -> Stage 3 started; do not regenerate the plan.
  completed               -> done; do not continue.
If status is missing or collecting_preferences, ask: "How many people, dietary restrictions, protein preferences, and how many days?"

GENERATE THE PLAN (when preferences are available):
  1. Show summary: "Here's a 5-day plan for 4 vegetarians with pasta/lentil focus:"
  2. List all days at high-level (one line each): "Day 1: Pasta Carbonara | Day 2: Lentil Soup"
  3. Keep diverse and realistic - vary cuisines and protein sources.
  4. Reflect dietary restrictions and protein preferences from the session block.
  5. Do NOT generate recipes or ingredients yet - just dish names.
  6. Ask: "Ready for the full recipes with cooking steps?"

SAVE STATE (critical):
  After showing the plan, call saveMealPlanState with status="high_level_plan_ready".
  Use the EXACT parameter names (camelCase). Do NOT use snake_case.
  Pass the highLevelPlan as a JSON object mapping day keys to meal names.
  Do NOT skip this — without it, context truncation loses the plan.



FORMATTING: Use METRIC / NZ units only (g, kg, ml, l, tsp, tbsp, Celsius, counts). NEVER use lb, oz, Fahrenheit.""".trimIndent()
}
