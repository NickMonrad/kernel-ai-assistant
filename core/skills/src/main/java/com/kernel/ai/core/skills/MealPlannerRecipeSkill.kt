package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 3 of the meal planning flow: generate and save recipe details for the current day.
 *
 * This is a "text-style" skill: it loads full instructions once (via load_skill), then the model
 * uses run_intent + bulk_add_to_list to save generated content. State is tracked through chat
 * history and the [Meal Planner Session] context block.
 *
 * Responsibilities:
 * - Read currentDayIndex from the [Meal Planner Session] context block
 * - Convert 0-based index to 1-based day label ("Day 1", "Day 2", etc.)
 * - Generate recipe title, ingredients list, and cooking method steps
 * - Format ingredients as bullet points with METRIC/NZ units only
 * - Format method as numbered steps for checkoff
 * - Call runIntent TWICE: ingredients → "shopping list", method steps → recipe-specific list
 * - Show saved confirmation
 * - Call save_meal_plan_state with status="generating_recipes" and current_day_index incremented
 *
 * Key rules:
 * - Do NOT regenerate previously written ingredients
 * - Each day's output is self-contained
 * - If status is "completed", do NOT generate recipes
 * - If the user says "skip this day", still call save_meal_plan_state to advance
 */
@Singleton
class MealPlannerRecipeSkill @Inject constructor() : Skill {

    override val name = "meal_planner_recipe"
    override val description =
        "Generate recipe details (ingredients + cooking steps) for the current day and save them to lists."

    override val schema = SkillSchema(
        parameters = emptyMap(),
        required = emptyList(),
    )

    override val examples = listOf(
        "Model reads currentDayIndex from [Meal Planner Session] → generates Day 1 recipe → saves ingredients to shopping list → saves method steps to 'Monday Pasta Carbonara' list → advances state",
    )

    override suspend fun execute(call: SkillCall): SkillResult {
        // Text-style skill: just return success. The skill's fullInstructions guide the model
        // to use run_intent + bulk_add_to_list for saving. No execution needed here.
        return SkillResult.Success("Recipe skill loaded. Follow the instructions to generate and save the current day's recipe.")
    }

    override val fullInstructions: String = """
meal_planner_recipe: Generate and save recipe details for the current day.

IMPORTANT: This skill handles ONLY Stage 3 — generating and saving recipes.
Do NOT collect preferences (Stage 1) or generate the high-level plan (Stage 2).

SESSION CONTEXT BLOCK:
  - At the start of each turn, the system may inject a [Meal Planner Session] block.
  - ALWAYS read this block first. It contains: status, currentDayIndex, people count, days,
    dietary restrictions, protein preferences, and the high-level plan.
  - If status == "completed", do NOT generate recipes. Respond that the meal plan is done.
  - conversation_id is in the [Meal Planner Session] context block. Use it for save_meal_plan_state.

CURRENT DAY:
  - Read currentDayIndex from the session block (0-based). Default to 0 if null.
  - Convert to 1-based for user-facing output: "Day 1", "Day 2", etc.
  - If the user says "skip this day" or similar, still advance to the next day.

RECIPE GENERATION:
  1. Generate a recipe title (e.g. "Pasta Carbonara", "Lentil Soup")
  2. Generate ingredients as bullet points with METRIC/NZ units only:
     - Allowed units: g, kg, ml, l, tsp, tbsp, Celsius, whole-item counts
     - NEVER use: lb, lbs, oz, Fahrenheit
     - Each ingredient: single complete quantity (e.g. "500 g pasta", "3 eggs")
     - Bad examples: "000 g", "50000 g" — these indicate concatenation errors
     - Good examples: "500 g pasta", "300 g chicken", "200 g broccoli"
  3. Generate cooking method as numbered steps for checkoff (imperative tone)
  4. Do NOT regenerate previously written ingredients — only output the NEW day's ingredients
  5. Each day's output is self-contained

CRITICAL SAVE RULE (call runIntent TWICE):
  → FIRST:  runIntent(intentName="bulk_add_to_list", parameters={"items":[...all ingredients...],"list_name":"shopping list"})
  → SECOND: runIntent(intentName="bulk_add_to_list", parameters={"items":[...all method steps...],"list_name":"{day} {dish}"})
  Example list name: "monday pasta carbonara" (lowercase, space-separated)

SAVED CONFIRMATION:
  Show: "✓ Added ingredients to Shopping | ✓ Created Monday Pasta Carbonara list with 5 steps"

STATE PERSISTENCE:
  - AFTER saving: call save_meal_plan_state(conversation_id="<conv-id>", status="generating_recipes", current_day_index=<incremented>)
  - current_day_index is 0-based and must be incremented after each day's recipes are saved
  - conversation_id comes from the [Meal Planner Session] context block
  - Do NOT skip this step — without it, progress is lost on context truncation
""".trimIndent()
}
