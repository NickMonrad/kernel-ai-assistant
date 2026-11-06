package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.memory.rag.MessageSearchResult
import com.kernel.ai.core.memory.repository.MemorySearchResult

private const val MIN_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP = 0.34f
// Cross-preference neighbours can score 0.445; exact-topic lower-score matches use lexical overlap.
private const val MIN_PREFERENCE_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP = 0.5f
private const val MIN_MESSAGE_SCORE_WITHOUT_LEXICAL_OVERLAP = 0.22f
private val SEARCH_MEMORY_STOPWORDS = setOf(
    "a", "about", "all", "an", "and", "any", "are", "can", "could", "did", "do", "does",
    "favorite", "favourite", "for", "has", "her", "how", "i", "if", "in", "is", "like", "me",
    "memory", "my", "of", "our", "prefer", "preferred", "preference", "preferences", "remember",
    "say", "search", "see", "tell", "the", "to", "was", "we", "what", "what's", "whats", "who",
    "you", "your",
)

internal data class SearchMemoryFilterResult(
    val memoryResults: List<MemorySearchResult>,
    val messageResults: List<MessageSearchResult>,
)

internal fun filterSearchMemoryResults(
    query: String,
    memoryResults: List<MemorySearchResult>,
    messageResults: List<MessageSearchResult>,
): SearchMemoryFilterResult {
    val terms = extractSearchMemoryTerms(query)
    val minimumMemoryScore = if (containsPersonalPreferenceCue(query)) {
        MIN_PREFERENCE_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP
    } else {
        MIN_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP
    }
    val filteredMemory = memoryResults.filter { result ->
        val hasOverlap = hasLexicalOverlap(result.content, terms)
        hasOverlap || result.score >= minimumMemoryScore
    }
    val filteredMessages = messageResults.filter { result ->
        val hasOverlap = hasLexicalOverlap(result.content, terms)
        hasOverlap || result.score >= MIN_MESSAGE_SCORE_WITHOUT_LEXICAL_OVERLAP
    }
    return SearchMemoryFilterResult(
        memoryResults = filteredMemory,
        messageResults = filteredMessages,
    )
}

private fun extractSearchMemoryTerms(query: String): Set<String> =
    Regex("""[\p{L}\d'’-]+""")
        .findAll(query.lowercase())
        .map { stripPossessive(it.value.trim('’', '\'', '-', '–')) }
        .filter { it.length >= 3 && it !in SEARCH_MEMORY_STOPWORDS }
        .flatMap { token -> sequenceOf(token) + singularizeToken(token) }
        .filter { it.length >= 3 }
        .toSet()

/** "dog's" / "dog’s" → "dog"; leaves "kiwis" and plain words alone. */
private fun stripPossessive(token: String): String =
    token.removeSuffix("'s").removeSuffix("’s")

private fun hasLexicalOverlap(content: String, terms: Set<String>): Boolean {
    if (terms.isEmpty()) return false
    val lower = content.lowercase()
    return terms.any { term ->
        containsWholeTerm(lower, term) || containsWholeTerm(lower, term.replace('-', ' '))
    }
}

/** Plural/possessive endings a stored word may carry beyond the query term ("movie" → "movies"). */
private val WORD_ENDINGS = listOf("", "s", "es", "'s", "’s")

private fun containsWholeTerm(text: String, term: String): Boolean {
    var index = text.indexOf(term)
    while (index >= 0) {
        val end = index + term.length
        val startsAtBoundary = index == 0 || !text[index - 1].isLetterOrDigit()
        if (startsAtBoundary && WORD_ENDINGS.any { endsWordAt(text, end, it) }) return true
        index = text.indexOf(term, index + 1)
    }
    // "story" should also find "stories".
    if (term.endsWith("y") && term.length > 3) {
        return containsWholeTerm(text, term.dropLast(1) + "ies")
    }
    return false
}

private fun endsWordAt(text: String, end: Int, ending: String): Boolean {
    if (!text.startsWith(ending, end)) return false
    val after = end + ending.length
    return after == text.length || !text[after].isLetterOrDigit()
}

/**
 * Singular candidates for [token]. English plurals are ambiguous ("movies" → "movie",
 * "stories" → "story", "boxes" → "box"), so every plausible form is returned.
 */
private fun singularizeToken(token: String): List<String> = when {
    token.endsWith("ies") && token.length > 4 -> listOf(token.dropLast(3) + "y", token.dropLast(1))
    token.endsWith("es") && token.length > 4 -> listOf(token.dropLast(2), token.dropLast(1))
    token.endsWith("s") && !token.endsWith("ss") && token.length > 3 -> listOf(token.dropLast(1))
    else -> emptyList()
}
