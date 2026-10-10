package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextWindowManagerTruncateTest {

    private val manager = ContextWindowManager()

    private fun turns(n: Int): List<Pair<String, String>> =
        (1..n).map { i -> "user $i" to "assistant $i" }

    // historyBudget(4096) = 4096 - 1024 - 1400 = 1672 → maxTurns = 1672 / 100 = 16
    private val contextWindow4096 = 4096
    private val maxTurns4096 = ContextWindowManager.maxTurnsForContext(contextWindow4096)

    // historyBudget(8192) = 8192 - 1024 - 1400 = 5768 → maxTurns = 5768 / 100 = 57
    private val contextWindow8192 = 8192
    private val maxTurns8192 = ContextWindowManager.maxTurnsForContext(contextWindow8192)

    @Test
    fun maxTurnsForContext_4096window_returns16() {
        assertEquals(16, maxTurns4096)
    }

    @Test
    fun maxTurnsForContext_8192window_returns57() {
        assertEquals(57, maxTurns8192)
    }

    @Test
    fun maxTurnsForContext_smallWindow_clampsToMinimum4() {
        // Very small window where budget/100 < 4 → clamped to 4
        val maxTurns = ContextWindowManager.maxTurnsForContext(contextWindowSize = 1500)
        assertEquals(4, maxTurns)
    }

    @Test
    fun maxTurnsForContext_4096window_20turns_truncatedTo16() {
        val input = turns(20)
        val result = input.takeLast(maxTurns4096)
        assertEquals(16, result.size)
        assertEquals("user 5" to "assistant 5", result.first())
        assertEquals("user 20" to "assistant 20", result.last())
    }

    @Test
    fun maxTurnsForContext_8192window_20turns_allKept() {
        val input = turns(20)
        val result = input.takeLast(maxTurns8192)
        assertEquals(20, result.size)
        assertEquals(input, result)
    }

    @Test
    fun maxTurnsForContext_4096window_exactlyAtLimit_returnsUnchanged() {
        val input = turns(maxTurns4096)
        val result = input.takeLast(maxTurns4096)
        assertEquals(input, result)
    }

    @Test
    fun maxTurnsForContext_belowLimit_returnsUnchanged() {
        val input = turns(5)
        val result = input.takeLast(maxTurns4096)
        assertEquals(input, result)
    }

    @Test
    fun maxTurnsForContext_emptyList_returnsEmpty() {
        val result = emptyList<Pair<String, String>>().takeLast(maxTurns4096)
        assertTrue(result.isEmpty())
    }

    @Test
    fun maxTurnsForContext_scalesWithLargerWindow() {
        // Larger context window should yield more turns
        assertTrue(maxTurns8192 > maxTurns4096)
    }

    @Test
    fun maxTurnsForContext_preservesChronologicalOrder() {
        val input = turns(20)
        val result = input.takeLast(maxTurns4096)
        val expected = input.drop(20 - maxTurns4096)
        assertEquals(expected, result)
    }
    @Test
    fun historyBudget_s21MeasuredFixedPrompt_leavesNoHistory() {
        val fixedPromptTokens = 2_300
        val contextWindowSize = 3_072
        val expectedHistoryBudget =
            (contextWindowSize - ContextWindowManager.RESPONSE_RESERVE - fixedPromptTokens).coerceAtLeast(0)

        val actualHistoryBudget = ContextWindowManager.historyBudget(contextWindowSize, fixedPromptTokens)

        assertEquals(expectedHistoryBudget, actualHistoryBudget)
        val selected = manager.selectHistory(turns(12), actualHistoryBudget)
        val selectedTokens = selected.sumOf {
            manager.estimateTokens(it.first) + manager.estimateTokens(it.second)
        }
        assertTrue(selectedTokens <= expectedHistoryBudget)
        assertTrue(selectedTokens <= contextWindowSize - ContextWindowManager.RESPONSE_RESERVE)
    }

    @Test
    fun historyBudget_flagship_keepsShortHistoryUnchanged() {
        val input = turns(20)

        val previousSelection = manager.selectHistory(input, ContextWindowManager.historyBudget(8_000))
        val selected = manager.selectHistory(
            input,
            ContextWindowManager.historyBudget(8_000, fixedPromptTokens = 2_300),
        )

        assertEquals(previousSelection, selected)

        assertEquals(input, selected)
    }

}
