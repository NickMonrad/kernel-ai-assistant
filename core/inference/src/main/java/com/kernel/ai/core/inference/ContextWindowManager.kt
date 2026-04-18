package com.kernel.ai.core.inference

/**
 * Manages context window budget for LiteRT-LM conversations.
 *
 * After a reset (cancel, restore from Room), prior conversation turns must be re-injected
 * so the model has context. This class estimates token usage and selects which turns to
 * include within the available budget, scaled to the active context window size.
 *
 * Token estimation uses a conservative 3 chars/token heuristic to account
 * for code, punctuation, and non-English text.
 */
class ContextWindowManager {

    companion object {
        /** Reserved for the model's generated response. */
        const val RESPONSE_RESERVE = 1024

        /** Reserved for system prompt + datetime + user profile + RAG context.
         *  Actual measured overhead: ~800 tokens (system prompt) + ~400 (RAG/profile) + 200 buffer = ~1400.
         *  Previously 2048 — over-reserved, leaving only ~1024 tokens for history on a 4096 window. */
        const val SYSTEM_OVERHEAD = 1400

        /** Conservative average tokens per (user + assistant) turn pair. */
        const val AVG_TOKENS_PER_TURN = 100

        /**
         * Tokens available for conversation history given [contextWindowSize].
         * Clamped to zero so callers never receive a negative budget.
         */
        fun historyBudget(contextWindowSize: Int): Int =
            (contextWindowSize - RESPONSE_RESERVE - SYSTEM_OVERHEAD).coerceAtLeast(0)

        /**
         * Maximum conversation turns to retain given [contextWindowSize].
         * Derived from the history token budget assuming [avgTokensPerTurn] per turn.
         * Acts as a hard ceiling that scales with the active model context window.
         */
        fun maxTurnsForContext(
            contextWindowSize: Int,
            avgTokensPerTurn: Int = AVG_TOKENS_PER_TURN,
        ): Int = (historyBudget(contextWindowSize) / avgTokensPerTurn).coerceAtLeast(4)

        /**
         * Token budget for RAG context block, scaled to [contextWindowSize].
         * 5% of the window, clamped to [200, 600] tokens.
         */
        fun episodicBudget(contextWindowSize: Int): Int =
            (contextWindowSize * 0.05).toInt().coerceIn(200, 600)

        private const val CHARS_PER_TOKEN = 3
    }

    /** Conservative token count estimate for [text]. */
    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    /**
     * Returns the most recent (user, assistant) pairs from [turns] that fit
     * within [budget] tokens, in chronological order.
     *
     * When the most recent turn alone exceeds [budget] (e.g. a very long tool
     * result), it is included truncated rather than dropping all history. This
     * prevents complete context amnesia (#446) when a single large turn fills
     * the budget.
     */
    fun selectHistory(
        turns: List<Pair<String, String>>,
        budget: Int,
    ): List<Pair<String, String>> {
        if (turns.isEmpty() || budget <= 0) return emptyList()
        var remaining = budget
        val result = ArrayDeque<Pair<String, String>>()
        for (turn in turns.reversed()) {
            val cost = estimateTokens(turn.first) + estimateTokens(turn.second)
            if (remaining < cost) {
                // If we haven't collected any turns yet, the most recent turn alone exceeds
                // the budget. Include it truncated so at least one turn of context survives.
                // Guard requires remaining >= 2: estimateTokens() has coerceAtLeast(1) so each
                // half costs minimum 1 token — a budget of 1 would produce 2 tokens (overflow).
                if (result.isEmpty() && remaining >= 2) {
                    val maxCharsEach = (remaining * CHARS_PER_TOKEN) / 2
                    result.addFirst(turn.first.take(maxCharsEach) to turn.second.take(maxCharsEach))
                }
                break
            }
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

    /** Fraction of [historyBudget] consumed by [turns]. Values > 1.0 indicate overflow. */
    fun historyFillFraction(turns: List<Pair<String, String>>, contextWindowSize: Int): Float {
        val used = turns.sumOf { estimateTokens(it.first) + estimateTokens(it.second) }
        return used.toFloat() / historyBudget(contextWindowSize)
    }

}
