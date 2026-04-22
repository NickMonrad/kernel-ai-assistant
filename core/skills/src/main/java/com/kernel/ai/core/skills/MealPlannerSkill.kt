package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-style skill for meal planning.
 *
 * This skill does not expose a separate execution tool. Instead, the model loads the planning
 * instructions via load_skill, generates the plan in chat, and uses existing list tools to save
 * the meal plan + shopping list when the user asks.
 */
@Singleton
class MealPlannerSkill @Inject constructor() : Skill {

    override val name = "meal_planner"
    override val description =
        "Plan meals for a day or week, then optionally save both a meal-plan list and a " +
            "shopping list using the existing list tools."

    override val schema = SkillSchema()

    override val fullInstructions: String = """
meal_planner: Create a meal plan and optionally save it into lists.

Instructions:
- Use this skill when the user asks for meal planning, weekly meals, recipe planning, dinner ideas
  for multiple days, or asks for a meal plan that should be saved into lists.
- You may generate the meal plan directly in your response. Keep it practical and easy to scan.
- If the user clearly wants the result saved, use run_intent to write back into lists.
- Default list names:
  - meal plan
  - shopping list
- Use bulk_add_to_list for both lists whenever there are multiple items.
- Do NOT use save_memory for meal plans or shopping lists.

Saving rules:
- The meal-plan list should contain one concise line per meal, e.g.
  "Monday dinner - Chicken stir-fry"
  "Tuesday lunch - Tuna wraps"
- The shopping list should contain deduplicated ingredient items, using plain shopping-style names.
- If the user asks to replace or refresh a plan, remove old items only if they explicitly ask to
  clear/replace the lists. Otherwise append the new items.
- If the user asks for ideas only, do not call any list tools.

Clarification rules:
- If the user did not specify enough to build a useful plan (for example days/meals, dietary
  constraints, or goals), ask one concise follow-up question.
- If the user already gave enough detail, do not ask unnecessary questions.

Suggested tool sequence when saving:
1. loadSkill(skillName="run_intent")
2. runIntent(intentName="bulk_add_to_list", parameters="{\"items\":\"...\",\"list_name\":\"meal plan\"}")
3. runIntent(intentName="bulk_add_to_list", parameters="{\"items\":\"...\",\"list_name\":\"shopping list\"}")

Example behaviors:
- "Plan 5 easy dinners for this week" → produce a 5-dinner plan in chat
- "Make me a 7 day meal plan and save it to lists" → produce the plan, then save meal-plan items
  to "meal plan" and ingredients to "shopping list"
- "Give me high-protein lunches and put the ingredients on my shopping list" → produce the lunch
  plan, then save only what the user explicitly asked for
    """.trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult =
        SkillResult.Success(fullInstructions)
}
