package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContextWindowManagerTruncateTest {

    private val manager = ContextWindowManager()

    private fun turns(n: Int): List<Pair<String, String>> =
        (1..n).map { i -> "user $i" to "assistant $i" }

    @Test
    fun truncateToWindow_historyExceedsMax_keepsLastMaxTurns() {
        val input = turns(20)
        val result = manager.truncateToWindow(input)
        assertEquals(ContextWindowManager.MAX_CONTEXT_TURNS, result.size)
        assertEquals("user 9" to "assistant 9", result.first())
        assertEquals("user 20" to "assistant 20", result.last())
    }

    @Test
    fun truncateToWindow_historyAtExactMax_returnsUnchanged() {
        val input = turns(ContextWindowManager.MAX_CONTEXT_TURNS)
        val result = manager.truncateToWindow(input)
        assertEquals(input, result)
    }

    @Test
    fun truncateToWindow_historyBelowMax_returnsUnchanged() {
        val input = turns(5)
        val result = manager.truncateToWindow(input)
        assertEquals(input, result)
    }

    @Test
    fun truncateToWindow_emptyList_returnsEmpty() {
        val result = manager.truncateToWindow(emptyList())
        assertEquals(emptyList<Pair<String, String>>(), result)
    }

    @Test
    fun truncateToWindow_singleTurn_returnsUnchanged() {
        val input = listOf("hello" to "hi there")
        val result = manager.truncateToWindow(input)
        assertEquals(input, result)
    }

    @Test
    fun truncateToWindow_preservesChronologicalOrder() {
        val input = turns(15)
        val result = manager.truncateToWindow(input)
        // Result should be the last MAX_CONTEXT_TURNS in original order
        val expected = input.takeLast(ContextWindowManager.MAX_CONTEXT_TURNS)
        assertEquals(expected, result)
    }
}
