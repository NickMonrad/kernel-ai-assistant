package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillParameter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements Google AI Edge Gallery's load_skill pattern (#341).
 *
 * The system prompt stays minimal — it lists only skill names + one-liners.
 * When the model needs to invoke a skill, it first calls this tool to retrieve
 * the full parameter schema, examples, and enforcement rules on demand.
 *
 * Uses [dagger.Lazy] to break the circular dependency:
 * SkillRegistry → Set<Skill> (includes this class) → SkillRegistry.
 * SkillRegistry is only accessed at execute()-time, never during construction.
 */
@Singleton
class LoadSkillSkill @Inject constructor(
    private val skillRegistry: Lazy<SkillRegistry>,
) : Skill {

    override val name = "load_skill"
    override val description =
        "Load full instructions for a skill before calling it. " +
            "Call this first whenever you need to use run_intent, get_weather, " +
            "save_meal_plan_state, save_memory, search_memory, or get_system_info."
    override val schema = SkillSchema(
        parameters = mapOf(
            "skill_name" to SkillParameter(
                type = "string",
                description = "The name of the skill to load.",
                enum = listOf(
                    "run_intent",
                    "get_weather",
                    "query_wikipedia",
                    "meal_planner_collect",
                    "meal_planner_plan",
                    "meal_planner_recipe",
                    "meal_planner_complete",
                    "save_meal_plan_state",
                    "save_memory",
                    "search_memory",
                    "get_system_info",
                    "run_js",
                ),
            ),
        ),
        required = listOf("skill_name"),
    )

    override val examples = listOf(
        "Load device action instructions → loadSkill(skillName=\"run_intent\")",
        "Load Wikipedia instructions → loadSkill(skillName=\"query_wikipedia\")",
        "Load memory save instructions → loadSkill(skillName=\"save_memory\")",
    )

    // load_skill's own fullInstructions are always embedded in the system prompt — no need
    // to load them lazily. This just returns the standard default.

    // Success: instruction context for LLM — not user-facing
    override suspend fun execute(call: SkillCall): SkillResult {
        val skillName = call.arguments["skill_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: skill_name.")
        val skill = skillRegistry.get().get(skillName)
            ?: return SkillResult.Failure(
                name,
                "Unknown skill: '$skillName'. Available: run_intent, get_weather, query_wikipedia, meal_planner_collect, meal_planner_plan, meal_planner_recipe, meal_planner_complete, save_meal_plan_state, save_memory, search_memory, get_system_info, run_js"
)
        return SkillResult.Success("Instructions loaded for '$skillName'. Follow them to complete the task.")
    }
}
