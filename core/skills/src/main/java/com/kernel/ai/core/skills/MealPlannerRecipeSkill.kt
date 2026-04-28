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

STEP-BY-STEP EXECUTION ORDER (follow exactly):

STEP 1 — Read session context:
  - Read [Meal Planner Session] block for currentDayIndex (0-based)
  - Convert to 1-based for user: "Day 1", "Day 2", etc.
  - If status == "completed", reply: "Your meal plan is complete."
  - conversationId is in the [Meal Planner Session] context block.

STEP 2 — Generate recipe:
  - Generate recipe title, ingredients (METRIC units only), and method steps
  - Show to the user

STEP 3 — Save ingredients to shopping list:
  Call run_intent with intentName="bulk_add_to_list", list_name="shopping list", items=[...ingredients...]

STEP 4 — Save method steps to recipe list:
  Call run_intent with intentName="bulk_add_to_list", list_name="{day} {dish}", items=[...steps...]

STEP 5 — Show saved confirmation:
  "Added ingredients to Shopping | Created {day} {dish} list with N steps"

STEP 6 — ADVANCE STATE (REQUIRED):
  Call the saveMealPlanState tool with EXACT parameters:
    saveMealPlanState(
      conversationId="<conv-id from session block>",
      status="generating_recipes",
      currentDayIndex=<incremented>
    )
  - currentDayIndex is 0-based and MUST be incremented
  - This is the ONLY way to advance to the next day
  - If you skip this step, you will be stuck on the same day

CRITICAL:
  - You MUST call saveMealPlanState in STEP 6. Without it, the model cannot
    know which day to generate next and will loop on the current day.
  - currentDayIndex is 0-based: Day 1 = 0, Day 2 = 1, Day 3 = 2
  - conversationId comes from the [Meal Planner Session] context block
  - Do NOT generate recipes for a day you have already saved
  - If currentDayIndex >= days, the plan is complete — acknowledge and close

FORMATTING RULES:
  - Ingredients: bullet points, METRIC/NZ units only (g, kg, ml, l, tsp, tbsp, Celsius, counts)
  - NEVER use: lb, lbs, oz, Fahrenheit
  - Each ingredient: single complete quantity (e.g. "500 g pasta", "3 eggs")
  - Bad examples: "000 g", "50000 g" — these indicate concatenation errors
  - Method: numbered steps, imperative tone
  - Do NOT regenerate previously written ingredients
""".trimIndent()
}
