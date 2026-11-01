package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.js.JsSkillRunner
import org.json.JSONObject
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

private val WIKIPEDIA_FACTUAL_QUESTION_REGEX = Regex(
    """^\s*(?:is|are|was|were|did|does|do|has|have|had|can|could|will|would|who|what|when|where|why|how|which|whose)\s+\S+""",
    RegexOption.IGNORE_CASE,
)

/**
 * True when [text] is phrased as a question (auxiliary or wh- word first), e.g. "Is Andy Samberg
 * Jewish?". Such Wikipedia lookups return question-relevant evidence for the model to answer from
 * (#1689) instead of a terminal article summary.
 */
fun isFactualWikipediaQuestion(text: String): Boolean =
    WIKIPEDIA_FACTUAL_QUESTION_REGEX.containsMatchIn(text)

internal data class WikipediaArticleEvidence(
    val title: String,
    val url: String,
    val summary: String,
    val extract: String,
)

/** Parses the JSON emitted by the JS skill in evidence mode; null for its plain-text no-result messages. */
internal fun parseWikipediaArticleEvidence(raw: String): WikipediaArticleEvidence? {
    if (!raw.trimStart().startsWith("{")) return null
    return runCatching {
        val json = JSONObject(raw)
        WikipediaArticleEvidence(
            title = json.optString("title"),
            url = json.optString("url"),
            summary = json.optString("summary"),
            extract = json.optString("extract"),
        )
    }.getOrNull()?.takeIf { it.title.isNotBlank() }
}

internal const val WIKIPEDIA_MAX_EVIDENCE_PASSAGES = 3
internal const val WIKIPEDIA_MAX_PASSAGE_CHARS = 700
private const val WIKIPEDIA_MAX_SUMMARY_CHARS = 800

private val WIKIPEDIA_QUESTION_STOPWORDS = setOf(
    "is", "are", "was", "were", "did", "does", "do", "has", "have", "had", "can", "could", "will",
    "would", "who", "what", "when", "where", "why", "how", "which", "whose", "the", "and", "for",
    "from", "with", "about", "into", "that", "this", "his", "her", "hers", "him", "she", "they",
    "them", "their", "its", "any", "ever", "been", "being", "much", "many", "please", "tell",
    "wikipedia", "there", "not",
)

private val WIKIPEDIA_SKIPPED_SECTIONS = setOf(
    "references", "external links", "see also", "further reading", "notes", "bibliography", "sources",
)

private val WIKIPEDIA_HEADING_REGEX = Regex("""^=+\s*(.+?)\s*=+$""")
private val WIKIPEDIA_WORD_REGEX = Regex("""[\p{L}\p{N}]+""")

private fun wikipediaWords(text: String): List<String> =
    WIKIPEDIA_WORD_REGEX.findAll(text.lowercase()).map { it.value }.toList()

private fun isWikipediaWordVariant(a: String, b: String): Boolean =
    a.startsWith(b) || (b.startsWith(a) && a.length >= 4)

/**
 * Question terms that can discriminate passages: drops stopwords and anything naming the article
 * subject itself (including speech-like variants such as "Samberger" for "Samberg").
 */
internal fun wikipediaQuestionKeywords(question: String, subject: String): List<String> {
    val subjectWords = wikipediaWords(subject).filter { it.length >= 3 }
    return wikipediaWords(question)
        .filter { it.length >= 3 && it !in WIKIPEDIA_QUESTION_STOPWORDS }
        .filterNot { word -> subjectWords.any { isWikipediaWordVariant(word, it) || isWikipediaWordVariant(it, word) } }
        .distinct()
}

internal data class WikipediaPassage(val heading: String?, val text: String)

/**
 * Selects at most [WIKIPEDIA_MAX_EVIDENCE_PASSAGES] article paragraphs that mention question
 * keywords, ranked by distinct keyword hits, then article order. Each excerpt is bounded to
 * [WIKIPEDIA_MAX_PASSAGE_CHARS] and starts at the sentence containing the first hit.
 */
internal fun selectWikipediaPassages(extract: String, keywords: List<String>): List<WikipediaPassage> {
    if (keywords.isEmpty()) return emptyList()
    var heading: String? = null
    val scored = mutableListOf<Triple<Int, Int, WikipediaPassage>>()
    extract.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val headingMatch = WIKIPEDIA_HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            heading = headingMatch.groupValues[1]
            return@forEach
        }
        if (heading?.lowercase() in WIKIPEDIA_SKIPPED_SECTIONS) return@forEach
        val words = wikipediaWords(line)
        val hits = keywords.filter { keyword -> words.any { isWikipediaWordVariant(it, keyword) } }
        if (hits.isEmpty()) return@forEach
        scored += Triple(hits.size, scored.size, WikipediaPassage(heading, excerptAround(line, hits)))
    }
    return scored
        .sortedWith(compareByDescending<Triple<Int, Int, WikipediaPassage>> { it.first }.thenBy { it.second })
        .take(WIKIPEDIA_MAX_EVIDENCE_PASSAGES)
        .map { it.third }
}

private fun excerptAround(paragraph: String, hits: List<String>): String {
    if (paragraph.length <= WIKIPEDIA_MAX_PASSAGE_CHARS) return paragraph
    val lower = paragraph.lowercase()
    val firstHit = hits.mapNotNull { hit ->
        Regex("""(?<![\p{L}\p{N}])${Regex.escape(hit)}""").find(lower)?.range?.first
    }.minOrNull() ?: 0
    val sentenceStart = lower.lastIndexOf(". ", firstHit).let { if (it < 0) 0 else it + 2 }
    val end = (sentenceStart + WIKIPEDIA_MAX_PASSAGE_CHARS).coerceAtMost(paragraph.length)
    return (if (sentenceStart > 0) "…" else "") +
        paragraph.substring(sentenceStart, end).trim() +
        (if (end < paragraph.length) "…" else "")
}

internal fun formatWikipediaEvidence(question: String, evidence: WikipediaArticleEvidence): String {
    val passages = selectWikipediaPassages(
        evidence.extract,
        wikipediaQuestionKeywords(question, evidence.title),
    )
    return buildString {
        appendLine("Wikipedia evidence for the user's question: \"$question\"")
        appendLine("Article: ${evidence.title}")
        if (evidence.url.isNotBlank()) appendLine("Source: ${evidence.url}")
        appendLine()
        appendLine("Summary: ${evidence.summary.trim().take(WIKIPEDIA_MAX_SUMMARY_CHARS)}")
        appendLine()
        if (passages.isEmpty()) {
            appendLine("No other passage in the article mentions the question's terms.")
        } else {
            appendLine("Relevant passages:")
            passages.forEach { passage ->
                appendLine("- ${passage.heading?.let { "[$it] " }.orEmpty()}${passage.text}")
            }
        }
        appendLine()
        append(WIKIPEDIA_EVIDENCE_ANSWER_RULE)
    }
}

private const val WIKIPEDIA_EVIDENCE_ANSWER_RULE =
    "Answer the user's question directly, using only this evidence. If it does not establish the " +
        "answer, say Wikipedia does not establish it; do not guess or infer it from unrelated biography."

internal fun wikipediaNoEvidenceResult(query: String, question: String): String =
    "No confident Wikipedia article found for: $query\n" +
        "Wikipedia did not provide evidence for the user's question: \"$question\". " +
        "Say that Wikipedia did not establish an answer; do not guess."

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
        "Look up a named person, place, organisation, event, or other encyclopedia topic on " +
            "Wikipedia and return grounded factual context. Use ONLY for explicit Wikipedia " +
            "searches or fact lookups about a specific named entity. NOT for unit, measurement, " +
            "or cooking conversions, arithmetic, weather, date/time, definitions, or how-to questions."

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
        appendLine("- Do NOT use Wikipedia for unit/measurement/cooking conversions (e.g. cups to grams), arithmetic, weather, date/time, definitions, or how-to questions. Answer those directly without calling this tool.")
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
        val question = call.arguments[QUESTION_ARGUMENT]?.trim()?.takeIf { isFactualWikipediaQuestion(it) }
        if (question == null) {
            val result = runner.execute("query-wikipedia", mapOf("query" to query))
            return SkillResult.DirectReply(filterWikipediaResult(query, result))
        }
        // Factual question: return bounded evidence so the model answers it in the same turn (#1689).
        val raw = runner.execute("query-wikipedia", mapOf("query" to query, "mode" to "evidence"))
        val evidence = parseWikipediaArticleEvidence(raw)?.takeIf {
            !isIdentifierLikeWikipediaQuery(query) || hasConfidentWikipediaIdentifierMatch(query, it.title)
        } ?: return SkillResult.Success(wikipediaNoEvidenceResult(query, question))
        return SkillResult.Success(formatWikipediaEvidence(question, evidence))
    }

    companion object {
        /**
         * Internal argument carrying the user's original question. Supplied by [KernelAIToolSet]
         * from the current turn, not by the model.
         */
        const val QUESTION_ARGUMENT = "question"
    }
}
