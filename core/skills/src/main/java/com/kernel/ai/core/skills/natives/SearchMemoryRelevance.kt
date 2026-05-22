package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.memory.rag.MessageSearchResult
import com.kernel.ai.core.memory.repository.MemorySearchResult

private const val MIN_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP = 0.18f
private const val MIN_MESSAGE_SCORE_WITHOUT_LEXICAL_OVERLAP = 0.22f
private val SEARCH_MEMORY_STOPWORDS = setOf(
    "a", "about", "an", "are", "can", "could", "did", "do", "does", "for", "i", "if", "in",
    "is", "like", "me", "memory", "my", "of", "remember", "search", "see", "tell", "the", "to",
    "we", "what", "you", "your",
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
    val filteredMemory = memoryResults.filter { result ->
        val hasOverlap = hasLexicalOverlap(result.content, terms)
        hasOverlap || result.score >= MIN_MEMORY_SCORE_WITHOUT_LEXICAL_OVERLAP
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
        .filter { it.length >= 4 && it !in SEARCH_MEMORY_STOPWORDS }
        .flatMap { token -> sequenceOf(token, singularizeToken(token)) }
        .filter { it.length >= 4 }
        .toSet()

private fun hasLexicalOverlap(content: String, terms: Set<String>): Boolean {
    if (terms.isEmpty()) return false
    val lower = content.lowercase()
    return terms.any { term ->
        lower.contains(term) || lower.contains(term.replace('-', ' '))
    }
}

private fun singularizeToken(token: String): String = when {
    token.endsWith("ies") && token.length > 4 -> token.dropLast(3) + "y"
    token.endsWith("es") && token.length > 4 -> token.dropLast(2)
    token.endsWith("s") && token.length > 4 -> token.dropLast(1)
    else -> token
}
