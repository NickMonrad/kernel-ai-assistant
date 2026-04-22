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
    4. SAVE (call run_intent with bulk_add_to_list TWICE):
       a) Add all ingredients to the "Shopping" list
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

CRITICAL SAVE RULE (non-negotiable):
  After generating each day's recipe:
    → FIRST: Call run_intent to bulk_add_to_list with all ingredients to "Shopping" list
    → SECOND: Call run_intent to bulk_add_to_list with method steps to the recipe-specific list
  BOTH calls must happen. If you only call once, the recipe list or ingredients will be missed.

EXAMPLE TWO-STEP SAVE:
  Day 1 ingredients: ["2 cups pasta", "3 eggs", "100g bacon", "parmesan cheese"]
  Day 1 method: ["1. Boil pasta in salted water", "2. Fry bacon until crispy", "3. Mix eggs with cheese", ...]

  FIRST call: run_intent(intent_name="bulk_add_to_list", list_name="Shopping", items=["2 cups pasta", "3 eggs", "100g bacon", "parmesan cheese", ...])
  SECOND call: run_intent(intent_name="bulk_add_to_list", list_name="Monday Pasta Carbonara", items=["1. Boil pasta in salted water", "2. Fry bacon until crispy", ...])

LIST NAMING CONVENTION:
  - Shared: "Shopping" (cumulative, all ingredients across all days)
  - Recipe-specific: "{Day Name} {Dish Name}" (e.g. "Monday Pasta Carbonara", "Tuesday Salmon Bake")

FORMATTING:
  - Ingredients: bullet points, concise quantities (e.g. "2 cups flour", "1 can diced tomatoes")
  - Method: numbered steps, imperative tone (e.g. "1. Preheat oven to 350°F", "2. Chop onions finely")

NO BACK-AND-FORTH REQUIRED:
  If user says "Make me a 5-day meal plan for 4 people, vegetarian, pasta and lentils", go straight to high-level plan
  without asking clarifying questions. Only ask grouped batches if preferences are incomplete.
"""
}
