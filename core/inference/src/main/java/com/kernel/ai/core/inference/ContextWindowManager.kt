package com.kernel.ai.core.inference

/**
 * Manages context window budget for LiteRT-LM conversations.
 *
 * LiteRT-LM Gemma-4 E2B/E4B has an 8192-token context window. After a reset
 * (cancel, restore from Room), prior conversation turns must be re-injected
 * so the model has context. This class estimates token usage and selects
 * which turns to include within the available budget.
 *
 * Token estimation uses a conservative 3 chars/token heuristic to account
 * for code, punctuation, and non-English text.
 */
class ContextWindowManager {

    companion object {
        /** Gemma-4 E2B/E4B default context length. */
        const val MAX_CONTEXT_TOKENS = 8192

        /** Reserved for the model's generated response. */
        const val RESPONSE_RESERVE = 1024

        /** Reserved for system prompt + datetime + user profile + RAG context. */
        const val SYSTEM_OVERHEAD = 2048

        /** Tokens available for conversation history replay. */
        const val HISTORY_BUDGET = MAX_CONTEXT_TOKENS - RESPONSE_RESERVE - SYSTEM_OVERHEAD // 5120

        private const val CHARS_PER_TOKEN = 3
    }

    /** Conservative token count estimate for [text]. */
    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    /**
     * Returns the most recent (user, assistant) pairs from [turns] that fit
     * within [budget] tokens, in chronological order.
     */
    fun selectHistory(
        turns: List<Pair<String, String>>,
        budget: Int = HISTORY_BUDGET,
    ): List<Pair<String, String>> {
        var remaining = budget
        val result = ArrayDeque<Pair<String, String>>()
        for (turn in turns.reversed()) {
            val cost = estimateTokens(turn.first) + estimateTokens(turn.second)
            if (remaining < cost) break
            result.addFirst(turn)
            remaining -= cost
        }
        return result
    }

    /**
     * Formats selected history turns as a context prefix to inject before
     * the user's current message. Returns blank string if [turns] is empty.
     */
    fun formatHistoryBlock(turns: List<Pair<String, String>>): String {
        if (turns.isEmpty()) return ""
        return buildString {
            appendLine("[Previous conversation]")
            for ((user, assistant) in turns) {
                appendLine("User: $user")
                appendLine("Assistant: $assistant")
            }
            appendLine("[End of previous conversation]")
            appendLine()
        }
    }

    /**
     * Extracts complete (user, assistant) pairs from a flat message list.
     * Incomplete trailing pairs (e.g. a user message with no response yet) are dropped.
     */
    fun extractTurns(messages: List<Pair<String, Boolean>>): List<Pair<String, String>> {
        // messages is list of (content, isUser)
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < messages.size - 1) {
            val (userContent, isUser) = messages[i]
            val (assistantContent, isAssistant) = messages[i + 1]
            if (isUser && !isAssistant) {
                result.add(userContent to assistantContent)
                i += 2
            } else {
                i++
            }
        }
        return result
    }

    /** Fraction of [HISTORY_BUDGET] consumed by [turns]. Values > 1.0 indicate overflow. */
    fun historyFillFraction(turns: List<Pair<String, String>>): Float {
        val used = turns.sumOf { estimateTokens(it.first) + estimateTokens(it.second) }
        return used.toFloat() / HISTORY_BUDGET
    }
}
