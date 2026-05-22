package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.js.JsSkillRunner
import javax.inject.Inject
import javax.inject.Singleton


private val WIKIPEDIA_NO_RESULT_PREFIXES = listOf(
    "No Wikipedia results found for:",
    "Wikipedia search failed for:",
    "Couldn't fetch Wikipedia article for:",
    "Wikipedia has no summary for:",
)

internal fun extractWikipediaTitle(result: String): String =
    result.lineSequence().firstOrNull()?.trim().orEmpty()

private val WIKIPEDIA_IDENTIFIER_TOKEN_REGEX = Regex("""\b(?=[A-Za-z0-9-]{4,}\b)(?=[A-Za-z0-9-]*[A-Za-z])(?=[A-Za-z0-9-]*\d)[A-Za-z0-9-]+\b""")

internal fun isIdentifierLikeWikipediaQuery(query: String): Boolean =
    WIKIPEDIA_IDENTIFIER_TOKEN_REGEX.containsMatchIn(query)

internal fun hasConfidentWikipediaIdentifierMatch(query: String, title: String): Boolean {
    val normalizedTitle = title.lowercase().filter { it.isLetterOrDigit() }
    if (normalizedTitle.isBlank()) return false
    val normalizedQuery = query.lowercase().filter { it.isLetterOrDigit() }
    if (normalizedQuery.isNotBlank() &&
        (normalizedTitle == normalizedQuery ||
            normalizedTitle.contains(normalizedQuery) ||
            normalizedQuery.contains(normalizedTitle))
    ) {
        return true
    }
    val queryTokens = WIKIPEDIA_IDENTIFIER_TOKEN_REGEX
        .findAll(query)
        .map { token -> token.value.lowercase().filter { it.isLetterOrDigit() } }
        .filter { it.isNotBlank() }
        .toList()
    val titleTokens = WIKIPEDIA_IDENTIFIER_TOKEN_REGEX
        .findAll(title)
        .map { token -> token.value.lowercase().filter { it.isLetterOrDigit() } }
        .filter { it.isNotBlank() }
        .toList()
    return queryTokens.any { queryToken ->
        normalizedTitle.contains(queryToken) ||
            titleTokens.any { titleToken -> areEquivalentWikipediaIdentifierTokens(queryToken, titleToken) }
    }
}

private fun areEquivalentWikipediaIdentifierTokens(queryToken: String, titleToken: String): Boolean {
    if (queryToken == titleToken ||
        queryToken.contains(titleToken) ||
        titleToken.contains(queryToken)
    ) {
        return true
    }
    val queryParts = splitWikipediaIdentifierToken(queryToken)
    val titleParts = splitWikipediaIdentifierToken(titleToken)
    if (queryParts.size != titleParts.size) return false
    val hasTrailingLetters = queryParts.size >= 3 &&
        queryParts.last().all(Char::isLetter) &&
        queryParts[queryParts.lastIndex - 1].all(Char::isDigit)
    return queryParts.indices.all { index ->
        val queryPart = queryParts[index]
        val titlePart = titleParts[index]
        when {
            queryPart.all(Char::isDigit) && titlePart.all(Char::isDigit) -> queryPart == titlePart
            queryPart.all(Char::isLetter) && titlePart.all(Char::isLetter) -> {
                if (index == 0 && hasTrailingLetters) {
                    titlePart.startsWith(queryPart) || queryPart.startsWith(titlePart)
                } else {
                    queryPart == titlePart
                }
            }
            else -> false
        }
    }
}

private fun splitWikipediaIdentifierToken(token: String): List<String> =
    Regex("""[A-Za-z]+|\d+""").findAll(token).map { it.value }.toList()

internal fun filterWikipediaResult(query: String, result: String): String {
    if (!isIdentifierLikeWikipediaQuery(query)) return result
    if (WIKIPEDIA_NO_RESULT_PREFIXES.any { result.startsWith(it) }) {
        return "No confident Wikipedia result found for: $query"
    }
    val title = extractWikipediaTitle(result)
    return if (hasConfidentWikipediaIdentifierMatch(query, title)) {
        result
    } else {
        "No confident Wikipedia result found for: $query"
    }
}
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
        appendLine("- Call the queryWikipedia tool directly with the query argument.")
        appendLine("- For factual questions phrased as a sentence, search for the core topic/entity when possible.")
        appendLine("  Example: \"When was Constantinople founded?\" → query=\"Constantinople\"")
        appendLine("- After the tool returns, answer from the Wikipedia result. If the result is clearly off-topic, say so instead of pretending it answered the question.")
        appendLine()
        appendLine("Tool format:")
        appendLine("- Call queryWikipedia with the resolved topic or entity as the query argument.")
        appendLine()
        appendLine("Examples:")
        appendLine("  Wikipedia search → queryWikipedia(query=\"New Zealand\")")
        appendLine("  Founding date lookup → queryWikipedia(query=\"Constantinople\")")
    }

    override suspend fun execute(call: SkillCall): SkillResult {
        val query = call.arguments["query"]?.trim()
            ?: return SkillResult.Failure(name, "Missing required parameter: query.")
        val result = runner.execute("query-wikipedia", mapOf("query" to query))
        return SkillResult.DirectReply(filterWikipediaResult(query, result))
    }
}
