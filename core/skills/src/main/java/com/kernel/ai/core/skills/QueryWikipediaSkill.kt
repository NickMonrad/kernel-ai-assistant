package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.js.JsSkillRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public skill surface for Wikipedia lookups.
 *
 * The model loads this skill's focused instructions, then executes the existing [RunJsSkill]
 * gateway with `skill_name="query-wikipedia"`. This mirrors AI Edge Gallery's pattern where
 * skills are selected individually even when they share the same JS runtime underneath.
 */
@Singleton
class QueryWikipediaSkill @Inject constructor(
    private val runner: JsSkillRunner,
) : Skill {

    override val name = "query_wikipedia"
    override val description =
        "Look up a topic on Wikipedia and return grounded factual context. Use for explicit " +
            "Wikipedia searches or encyclopedia-style fact lookups."

    override val schema = SkillSchema(
        parameters = mapOf(
            "query" to SkillParameter(
                type = "string",
                description = "The topic, entity, or article title to look up on Wikipedia.",
            ),
        ),
        required = listOf("query"),
    )

    override val examples = listOf(
        "Person lookup → {\"name\":\"query_wikipedia\",\"arguments\":{\"query\":\"Taika Waititi\"}}",
        "War lookup → {\"name\":\"query_wikipedia\",\"arguments\":{\"query\":\"Second Schleswig War\"}}",
    )

    override val fullInstructions: String = buildString {
        appendLine("$name: $description")
        appendLine()
        appendLine("Instructions:")
        appendLine("- Call the run_js tool with the format below.")
        appendLine("- For factual questions phrased as a sentence, search for the core topic/entity when possible.")
        appendLine("  Example: \"When was Constantinople founded?\" → query=\"Constantinople\"")
        appendLine("- After the tool returns, answer from the Wikipedia result. If the result is clearly off-topic, say so instead of pretending it answered the question.")
        appendLine()
        appendLine("Tool format:")
        appendLine("- Call runJs with a single 'parameters' argument: a JSON string containing")
        appendLine("  'skill_name' and 'data' with the skill's parameters.")
        appendLine()
        appendLine("Examples:")
        appendLine("  Wikipedia search → runJs(parameters='{\"skill_name\":\"query-wikipedia\",\"data\":{\"query\":\"New Zealand\"}}')")
        appendLine("  Founding date lookup → runJs(parameters='{\"skill_name\":\"query-wikipedia\",\"data\":{\"query\":\"Constantinople\"}}')")
    }

    override suspend fun execute(call: SkillCall): SkillResult {
        val query = call.arguments["query"]?.trim()
            ?: return SkillResult.Failure(name, "Missing required parameter: query.")
        val result = runner.execute("query-wikipedia", mapOf("query" to query))
        return SkillResult.Success(result)
    }
}
