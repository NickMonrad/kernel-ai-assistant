package com.kernel.ai.core.skills

import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves meal-planner session state to the Room database so the model can resume
 * deterministically across context-window boundaries.
 *
 * All parameters except [conversationId] are optional — the model passes only what
 * has changed since the last call. The repository merges partial updates with
 * existing state.
 *
 * When to call:
 *   - After collecting preferences (Stage 1): people_count, days, dietary_restrictions,
 *     protein_preferences, status="collecting_preferences"
 *   - After generating the high-level plan (Stage 2): high_level_plan (JSON object),
 *     status="high_level_plan_ready"
 *   - After saving each day's recipes (Stage 3): current_day_index (0-based, incremented),
 *     status="generating_recipes"
 *   - When all days are complete: status="completed"
 */
@Singleton
class SaveMealPlanStateSkill @Inject constructor(
    private val repository: MealPlanSessionRepository,
) : Skill {

    override val name = "save_meal_plan_state"
    override val description =
        "Persist meal-planner session state to the database. Call after each stage " +
        "transition so the model can resume after context-window truncation."

    override val schema = SkillSchema(
        parameters = mapOf(
            "conversation_id" to SkillParameter(
                type = "string",
                description = "The conversation ID for this meal plan. Must be included in every call.",
            ),
            "status" to SkillParameter(
                type = "string",
                description = "Current flow stage: collecting_preferences, high_level_plan_ready, generating_recipes, or completed.",
                enum = listOf("collecting_preferences", "high_level_plan_ready", "generating_recipes", "completed"),
            ),
            "people_count" to SkillParameter(
                type = "integer",
                description = "Number of people the plan is for.",
            ),
            "days" to SkillParameter(
                type = "integer",
                description = "Number of days in the plan.",
            ),
            "dietary_restrictions" to SkillParameter(
                type = "string",
                description = "JSON array of dietary restrictions, e.g. '[\"vegetarian\",\"gluten-free\"]'.",
            ),
            "protein_preferences" to SkillParameter(
                type = "string",
                description = "JSON array of protein preferences, e.g. '[\"chicken\",\"fish\"]'.",
            ),
            "high_level_plan" to SkillParameter(
                type = "string",
                description = "JSON object mapping day keys to meal names, e.g. '{\"day1\":\"Pasta Carbonara\",\"day2\":\"Lentil Soup\"}'.",
            ),
            "current_day_index" to SkillParameter(
                type = "integer",
                description = "0-based index of the current day being generated. Increment after each day's recipes are saved.",
            ),
        ),
        required = listOf("conversation_id"),
    )

    override val examples = listOf(
        "After collecting preferences → saveMealPlanState(conversation_id=\"<conv-id>\", status=\"collecting_preferences\", people_count=4, days=5, dietary_restrictions='[\"vegetarian\"]', protein_preferences='[\"chicken\",\"fish\"]')",
        "After generating high-level plan → saveMealPlanState(conversation_id=\"<conv-id>\", status=\"high_level_plan_ready\", high_level_plan='{\"day1\":\"Pasta Carbonara\",\"day2\":\"Lentil Soup\",\"day3\":\"Chicken Curry\"}')",
        "After saving Day 1 recipes → saveMealPlanState(conversation_id=\"<conv-id>\", status=\"generating_recipes\", current_day_index=1)",
        "After saving Day 2 recipes → saveMealPlanState(conversation_id=\"<conv-id>\", status=\"generating_recipes\", current_day_index=2)",
        "When all days complete → saveMealPlanState(conversation_id=\"<conv-id>\", status=\"completed\")",
    )

    override val fullInstructions: String = """
save_meal_plan_state: Persist meal-planner session state to the database.

Parameters:
  - conversation_id (required, string): The conversation ID for this meal plan.
  - status (enum: collecting_preferences, high_level_plan_ready, generating_recipes, completed)
  - people_count (integer): Number of people the plan is for
  - days (integer): Number of days in the plan
  - dietary_restrictions (string): JSON array, e.g. '["vegetarian","gluten-free"]'
  - protein_preferences (string): JSON array, e.g. '["chicken","fish"]'
  - high_level_plan (string): JSON object mapping day keys to meals, e.g. '{"day1":"Pasta Carbonara","day2":"Lentil Soup"}'
  - current_day_index (integer): 0-based index of the current day being generated

Rules:
  - conversation_id is REQUIRED on every call. Use the conversation ID from the system context.
  - Call this tool after EVERY stage transition so the model can resume after context truncation.
  - Pass only the fields that have changed — the repository merges with existing state.
  - current_day_index is 0-based: Day 1 = 0, Day 2 = 1, etc. Increment it after saving each day.
  - When all days are complete, call with status="completed" to end the session.
  - Do NOT wait until the end of the entire meal plan — save after each stage.
""".trimIndent()

    override suspend fun execute(call: SkillCall): SkillResult {
        val args = call.arguments

        val conversationId = args["conversation_id"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(
                name,
                "Missing required parameter: conversation_id.",
            )

        val status = args["status"]
        val peopleCount = args["people_count"]?.toIntOrNull()
        val days = args["days"]?.toIntOrNull()
        val dietaryRestrictions = args["dietary_restrictions"]
        val proteinPreferences = args["protein_preferences"]
        val highLevelPlan = args["high_level_plan"]
        val currentDayIndex = args["current_day_index"]?.toIntOrNull()

        // Validate status enum if provided
        if (status != null && status !in listOf(
            "collecting_preferences",
            "high_level_plan_ready",
            "generating_recipes",
            "completed",
        )) {
            return SkillResult.Failure(
                name,
                "Invalid status: '$status'. Must be one of: collecting_preferences, high_level_plan_ready, generating_recipes, completed.",
            )
        }

        // Validate JSON fields if provided
        if (dietaryRestrictions != null) {
            try {
                JSONArray(dietaryRestrictions)
            } catch (e: Exception) {
                return SkillResult.Failure(
                    name,
                    "Invalid dietary_restrictions JSON: ${e.message}",
                )
            }
        }
        if (proteinPreferences != null) {
            try {
                JSONArray(proteinPreferences)
            } catch (e: Exception) {
                return SkillResult.Failure(
                    name,
                    "Invalid protein_preferences JSON: ${e.message}",
                )
            }
        }
        if (highLevelPlan != null) {
            try {
                JSONObject(highLevelPlan)
            } catch (e: Exception) {
                return SkillResult.Failure(
                    name,
                    "Invalid high_level_plan JSON: ${e.message}",
                )
            }
        }

        try {
            // If high_level_plan changed, use saveHighLevelPlan
            if (highLevelPlan != null) {
                repository.saveHighLevelPlan(conversationId, highLevelPlan)
            }

            // If current_day_index changed, set it directly
            if (currentDayIndex != null) {
                val existing = repository.getSession(conversationId)
                existing?.let {
                    repository.upsert(
                        it.copy(
                            currentDayIndex = currentDayIndex,
                            status = status ?: it.status,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } else {
                // General update: use updatePreferences for non-special fields
                // and updateStatus for status changes
                if (status != null) {
                    repository.updateStatus(conversationId, status)
                }
                // Merge other fields via updatePreferences
                repository.updatePreferences(
                    conversationId = conversationId,
                    peopleCount = peopleCount,
                    days = days,
                    dietaryRestrictionsJson = dietaryRestrictions,
                    proteinPreferencesJson = proteinPreferences,
                )
            }

            return SkillResult.Success(
                buildString {
                    append("Meal planner state saved for conversation $conversationId.\n")
                    if (status != null) append("Status: $status\n")
                    if (peopleCount != null) append("People: $peopleCount\n")
                    if (days != null) append("Days: $days\n")
                    if (dietaryRestrictions != null) append("Dietary: $dietaryRestrictions\n")
                    if (proteinPreferences != null) append("Proteins: $proteinPreferences\n")
                    if (highLevelPlan != null) append("Plan saved.\n")
                    if (currentDayIndex != null) append("Current day index: $currentDayIndex\n")
                }.trimEnd(),
            )
        } catch (e: Exception) {
            return SkillResult.Failure(
                name,
                "Failed to save meal plan state: ${e.message}",
            )
        }
    }
}
