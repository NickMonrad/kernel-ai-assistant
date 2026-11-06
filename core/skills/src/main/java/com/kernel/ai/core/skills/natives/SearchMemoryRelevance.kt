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
        .map { it.value.trim('’', '\'', '-', '–') }
        .filter { it.length >= 3 && it !in SEARCH_MEMORY_STOPWORDS }
        .flatMap { token -> sequenceOf(token, singularizeToken(token)) }
        .filter { it.length >= 3 }
        .toSet()

private fun hasLexicalOverlap(content: String, terms: Set<String>): Boolean {
    if (terms.isEmpty()) return false
    val lower = content.lowercase()
    return terms.any { term ->
        containsWholeTerm(lower, term) || containsWholeTerm(lower, term.replace('-', ' '))
    }
}

private fun containsWholeTerm(text: String, term: String): Boolean {
    var index = text.indexOf(term)
    while (index >= 0) {
        val end = index + term.length
        val startsAtBoundary = index == 0 || !text[index - 1].isLetterOrDigit()
        val endsAtBoundary = end == text.length || !text[end].isLetterOrDigit()
        if (startsAtBoundary && endsAtBoundary) return true
        index = text.indexOf(term, index + 1)
    }
    return false
}

private fun singularizeToken(token: String): String = when {
    token.endsWith("ies") && token.length > 4 -> token.dropLast(3) + "y"
    token.endsWith("es") && token.length > 4 -> token.dropLast(2)
    token.endsWith("s") && token.length > 4 -> token.dropLast(1)
    else -> token
}
