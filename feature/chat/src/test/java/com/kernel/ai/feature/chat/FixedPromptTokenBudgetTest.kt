package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.ContextWindowManager
import com.kernel.ai.core.inference.GenerationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedPromptTokenBudgetTest {
    @Test
    fun `small prefill after replay cannot lower fixed estimate or raise history budget`() {
        val systemPromptTokens = 800
        val requestPromptTokens = 100
        val fallbackToolTokens = 500
        val previouslyMeasuredToolTokens = 500
        val beforeFixedPromptTokens = FixedPromptTokenBudget.estimateFixedPromptTokens(
            systemPromptTokens = systemPromptTokens,
            measuredToolDeclarationTokens = previouslyMeasuredToolTokens,
            fallbackToolDeclarationTokens = fallbackToolTokens,
        )
        val beforeHistoryBudget = ContextWindowManager.historyBudget(3_072, beforeFixedPromptTokens)

        val calibration = FixedPromptTokenBudget.calibratedToolDeclarationTokens(
            result = GenerationResult.Complete(durationMs = 1L, prefillTokenCount = 26),
            systemPromptTokens = systemPromptTokens,
            requestPromptTokens = requestPromptTokens,
            turnInvolvedToolCallOrContinuation = false,
        )
        assertNull(calibration, "A last-prefill count below system+request tokens is not a full-prompt measurement")

        val afterFixedPromptTokens = FixedPromptTokenBudget.estimateFixedPromptTokens(
            systemPromptTokens = systemPromptTokens,
            measuredToolDeclarationTokens = calibration ?: previouslyMeasuredToolTokens,
            fallbackToolDeclarationTokens = fallbackToolTokens,
        )
        val afterHistoryBudget = ContextWindowManager.historyBudget(3_072, afterFixedPromptTokens)

        assertTrue(afterFixedPromptTokens >= beforeFixedPromptTokens)
        assertTrue(afterHistoryBudget <= beforeHistoryBudget)
        assertTrue(afterFixedPromptTokens >= FixedPromptTokenBudget.UNCALIBRATED_FIXED_PROMPT_FLOOR)
        assertEquals(0, afterHistoryBudget)
    }

    @Test
    fun `tool call continuation cannot calibrate declaration tokens`() {
        assertNull(
            FixedPromptTokenBudget.calibratedToolDeclarationTokens(
                result = GenerationResult.Complete(durationMs = 1L, prefillTokenCount = 5_000),
                systemPromptTokens = 800,
                requestPromptTokens = 100,
                turnInvolvedToolCallOrContinuation = true,
            ),
        )
    }

    @Test
    fun `complete non tool prefill estimates declaration cost`() {
        assertEquals(
            4_100,
            FixedPromptTokenBudget.calibratedToolDeclarationTokens(
                result = GenerationResult.Complete(durationMs = 1L, prefillTokenCount = 5_000),
                systemPromptTokens = 800,
                requestPromptTokens = 100,
                turnInvolvedToolCallOrContinuation = false,
            ),
        )
    }

    @Test
    fun `measured estimate never undercuts fallback declaration estimate`() {
        assertEquals(
            2_900,
            FixedPromptTokenBudget.estimateFixedPromptTokens(
                systemPromptTokens = 2_400,
                measuredToolDeclarationTokens = 10,
                fallbackToolDeclarationTokens = 500,
            ),
        )
    }
}
