package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.ContextWindowManager
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Implements Google AI Edge Gallery's load_skill pattern (#341).
 *
 * The system prompt stays minimal — it lists only skill names + one-liners.
 * When the model needs to invoke a skill, it first calls this tool to retrieve
 * full or compact instructions that fit the available context.
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
        "Load full or context-sized instructions for a complex or gateway skill before calling it. " +
            "Use this when you need detailed guidance for tools like run_intent or run_js."

    override val schema = SkillSchema(
        parameters = mapOf(
            "skill_name" to SkillParameter(
                type = "string",
                description = "The name of the skill to load.",
                enum = listOf(
                    "run_intent",
                    "get_weather",
                    "query_wikipedia",
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
        "Load JS gateway instructions → loadSkill(skillName=\"run_js\")",
        "Load detailed weather instructions → loadSkill(skillName=\"get_weather\")",
    )

    // load_skill's own fullInstructions are always embedded in the system prompt — no need
    // to load them lazily. This just returns the standard default.

    override suspend fun execute(call: SkillCall): SkillResult {
        val requestedSkillName = call.arguments["skill_name"]?.takeIf { it.isNotBlank() }
            ?: return SkillResult.Failure(name, "Missing required parameter: skill_name.")
        val isCalendarAlias = requestedSkillName.equals("calendar", ignoreCase = true)
        val skillName = if (isCalendarAlias) "run_intent" else requestedSkillName
        val skill = skillRegistry.get().get(skillName)
            ?: return SkillResult.Failure(
                name,
                "Unknown skill: '$requestedSkillName'. Available: run_intent, get_weather, " +
                    "query_wikipedia, save_memory, search_memory, get_system_info, run_js. " +
                    "Hint: calendar, alarm, SMS, and other device actions use run_intent."
            )

        val calendarPrefix = if (isCalendarAlias) {
            "Calendar actions are handled through run_intent. " +
                "Use runIntent(intentName=\"create_calendar_event\", parameters={...}).\n\n"
        } else {
            ""
        }
        val fullInstructions = if (isCalendarAlias) {
            calendarPrefix + skill.fullInstructions
        } else {
            skill.fullInstructions
        }
        val toolResultBudget = call.maxToolResultTokens
            ?: return SkillResult.Success(fullInstructions)
        if (fitsResult(fullInstructions, toolResultBudget)) {
            return SkillResult.Success(fullInstructions)
        }

        val compactInstructions = skill.compactInstructions?.let { compact ->
            if (isCalendarAlias) calendarPrefix + compact else compact
        }
        if (compactInstructions != null && fitsResult(compactInstructions, toolResultBudget)) {
            return SkillResult.Success(compactInstructions)
        }
        return SkillResult.Failure(
            name,
            "Insufficient context to load $requestedSkillName instructions; do not continue.",
        )
    }

    private fun fitsResult(content: String, tokenBudget: Int): Boolean =
        LoadSkillToolResultBudget.estimateTokens(mapOf("result" to content)) <= tokenBudget
}

/**
 * Estimates the JSON object returned from load_skill, including its key and escaped value.
 * LiteRT-LM adds this object to the next prefill, so measuring only the instruction text
 * undercounts the context consumed by a tool call.
 */
internal object LoadSkillToolResultBudget {
    private val tokenEstimator = ContextWindowManager()

    fun estimateTokens(result: Map<String, String>): Int =
        tokenEstimator.estimateTokens(JSONObject(result).toString())
}
