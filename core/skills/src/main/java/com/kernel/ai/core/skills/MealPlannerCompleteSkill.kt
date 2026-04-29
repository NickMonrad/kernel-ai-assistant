package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 4 of the meal planner flow: mark the meal plan as complete and provide a summary.
 *
 * Text-style skill — fullInstructions guide the model to call save_meal_plan_state
 * and deliver a concise closing message.
 */
@Singleton
class MealPlannerCompleteSkill @Inject constructor() : Skill {

    override val name = "meal_planner_complete"
    override val description = "Mark the meal plan as complete and provide a summary."

    override val schema = SkillSchema(
        parameters = emptyMap(),
        required = emptyList(),
    )

    override val examples = emptyList<String>()

    override val fullInstructions: String = """
meal_planner_complete: Confirm the meal plan is finished, save final state, and provide a brief summary.

Rules:
  - Call saveMealPlanState with status="completed" and conversationId from the [Meal Planner Session] context block.
  - If the session status is already "completed", just confirm: "Your meal plan is already complete."
  - If no active session exists, reply: "No active meal plan found. Would you like to start a new one?"
  - Provide a short summary: number of people, days, and a quick recap of the plan.
  - Offer follow-up: "Would you like to adjust any recipes or start a new meal plan?"
  - Do NOT generate any more recipes after this call.


""".trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult {
        return SkillResult.Success("Meal planner completion ready. Follow the instructions to save final state and summarise the plan.")
    }
}
