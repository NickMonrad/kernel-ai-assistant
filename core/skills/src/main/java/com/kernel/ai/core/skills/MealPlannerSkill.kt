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
meal_planner: Generate multi-stage meal plans with state persistence and incremental list saves.

IMPORTANT START RULE:
  - This skill must be loaded with load_skill before any save actions happen.
  - Do NOT call run_intent to start meal planning.
  - During Stage 1 and Stage 2, ask questions and plan in chat only.
  - Only call run_intent in Stage 3 when you are saving finished recipe data.
  - Do NOT create or write to a master list called "meal plan". This skill uses one shared shopping list plus one recipe-specific list per day.

FLOW (4 Stages):

Stage 1 — Collect Preferences (ask in 2-3 grouped batches):
  Batch 1: "How many people, and any dietary restrictions?"
  Batch 2: "How many days and protein preferences (e.g. chicken, fish, vegetarian)?"
  User responds with their preferences. Track all answers in your context.

Stage 2 — Generate High-Level Plan:
  Once preferences collected, show the user a summary: "Here's a 5-day plan for 4 vegetarians with pasta/lentil focus:"
  List all 5 days at high-level (one line each, e.g. "Day 1: Pasta Carbonara | Day 2: Lentil Soup").
  Ask: "Ready for the full recipes with cooking steps?"

Stage 3 — Detail Each Recipe & Save:
  For each day:
    1. Generate recipe title, ingredients list, and cooking method steps
    2. Format ingredients as a bullet-point list
    3. Format method as numbered steps that users can check off while cooking
    4. SAVE (call runIntent with bulk_add_to_list TWICE using the exact SDK tool syntax):
       a) Add EVERY ingredient for that day to the "shopping list" — do not summarize or truncate the list
       b) Create a new list named "{Day Name} {Dish Name}" (e.g. "Monday Pasta Carbonara")
       c) Add method steps as checkoff items to the recipe-specific list
    5. Show saved confirmation: "✓ Added ingredients to Shopping | ✓ Created Monday Pasta Carbonara list with 5 steps"

Stage 4 — Continue to Next Day:
  After saving one day, move to the next: "Now for Day 2..."
  Continue until all days are detailed and saved.

STATE PERSISTENCE (handle context window):
  - Reference prior answers throughout the conversation (e.g. "For your 4-person vegetarian plan...")
  - If the user goes silent for a while, re-establish state: "You were planning 5 days for 3 people, mostly fish. Ready for day 3?"
  - Do NOT ask the same question twice—assume user answers are permanent for this session

SESSION CONTEXT BLOCK (injected by the system):
  - At the start of each turn, the system may inject a [Meal Planner Session] block before your prompt.
  - ALWAYS read this block first. It contains the authoritative session state: status, people count, days,
    dietary restrictions, protein preferences, the high-level plan, and current day index.
  - Treat the session block as ground truth. If your memory conflicts with it, trust the session block.
  - The status field tells you where you are:
      collecting_preferences  → ask preference questions (Stage 1)
      high_level_plan_ready   → show the plan and ask if ready for recipes (Stage 2)
      generating_recipes      → generate and save the current day's recipe (Stage 3)
      completed               → meal planning is done; do not continue generating recipes
  - When generating recipes, use the currentDayIndex from the session block to know which day to generate.
    Day labels are 0-based in the system; present them as "Day 1", "Day 2", etc. to the user.
  - After saving a day's recipes, advance to the next day. When all days are complete, acknowledge completion.
  - Do NOT regenerate or re-ask for information already captured in the session block.

DATABASE STATE PERSISTENCE (critical — prevents losing progress):
  - You have a tool called save_meal_plan_state that writes session state to the database.
  - Call it after EVERY stage transition so progress is saved even if the context window truncates.
  - The tool requires conversation_id (required) and accepts optional fields for what changed.
  - Call schedule:
      1. After Stage 1 preferences collected:
         saveMealPlanState(conversation_id="<conv-id>", status="collecting_preferences",
           people_count=<N>, days=<N>, dietary_restrictions='["..."]', protein_preferences='["..."]')
      2. After Stage 2 high-level plan generated:
         saveMealPlanState(conversation_id="<conv-id>", status="high_level_plan_ready",
           high_level_plan='{"day1":"...","day2":"..."}')
      3. After each day's recipes saved (Stage 3):
         saveMealPlanState(conversation_id="<conv-id>", status="generating_recipes",
           current_day_index=<0-based index>)
      4. When all days complete:
         saveMealPlanState(conversation_id="<conv-id>", status="completed")
  - conversation_id is REQUIRED on every call. Use the conversation ID from the system context.
  - Pass only the fields that have changed — the database merges with existing state.
  - current_day_index is 0-based: Day 1 = 0, Day 2 = 1, etc.
  - Do NOT skip this step — without it, context truncation will lose your progress.

CRITICAL SAVE RULE (non-negotiable):
  After generating each day's recipe:
    → FIRST: Call runIntent(intentName="bulk_add_to_list", parameters="{...}") with ALL ingredients for that day to "shopping list"
    → SECOND: Call runIntent(intentName="bulk_add_to_list", parameters="{...}") with ALL method steps to the recipe-specific list
  BOTH calls must happen. If you only call once, the recipe list or ingredients will be missed.
  Do NOT create a high-level "meal plan" list with only dish names.

EXAMPLE TWO-STEP SAVE:
  Day 1 ingredients: ["500 g pasta", "3 eggs", "100 g bacon", "80 g parmesan cheese"]
  Day 1 method: ["1. Boil pasta in salted water", "2. Fry bacon until crispy", "3. Mix eggs with cheese", ...]

  FIRST call:
    runIntent(
      intentName="bulk_add_to_list",
      parameters="{\"items\":[\"500 g pasta\",\"3 eggs\",\"100 g bacon\",\"80 g parmesan cheese\"],\"list_name\":\"shopping list\"}"
    )
  SECOND call:
    runIntent(
      intentName="bulk_add_to_list",
      parameters="{\"items\":[\"1. Boil pasta in salted water\",\"2. Fry bacon until crispy\",\"3. Mix eggs with cheese\"],\"list_name\":\"monday pasta carbonara\"}"
    )

LIST NAMING CONVENTION:
  - Shared: "shopping list" (cumulative, all ingredients across all days)
  - Recipe-specific: "{day name} {dish name}" (e.g. "monday pasta carbonara", "tuesday salmon bake")
  - NEVER use "meal plan" as a list name for this skill

FORMATTING:
  - Ingredients: bullet points, concise quantities
  - Method: numbered steps, imperative tone
  - Use METRIC / NZ-friendly units only: g, kg, ml, l, tsp, tbsp, Celsius, and whole-item counts
  - NEVER use imperial units such as lb, lbs, oz, or Fahrenheit

INGREDIENT QUANTITY RULE (critical):
  - Each ingredient must have a single, complete quantity (e.g. "500 g pasta", "3 eggs").
  - NEVER regenerate a previously written ingredient list. Only output the NEW day's ingredients.
  - NEVER append to or repeat previous days' ingredients. Each day's output is self-contained.
  - If you are unsure of a quantity, use a reasonable estimate — do NOT output zeros or empty values.
  - Bad examples: "000 g", "50000 g", "00000 g", "00000000 g" — these indicate concatenation errors.
  - Good examples: "500 g pasta", "300 g chicken", "200 g broccoli", "1 tbsp soy sauce"

NO BACK-AND-FORTH REQUIRED:
  If user says "Make me a 5-day meal plan for 4 people, vegetarian, pasta and lentils", go straight to high-level plan
  without asking clarifying questions. Only ask grouped batches if preferences are incomplete.
"""
}
