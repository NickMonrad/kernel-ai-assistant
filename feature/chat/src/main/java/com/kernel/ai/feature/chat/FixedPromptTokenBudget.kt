package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.GenerationResult

internal object FixedPromptTokenBudget {
    /** Minimum total fixed-prompt estimate, including after calibration. */
    const val UNCALIBRATED_FIXED_PROMPT_FLOOR = 2_400

    fun estimateFixedPromptTokens(
        systemPromptTokens: Int,
        measuredToolDeclarationTokens: Int?,
        fallbackToolDeclarationTokens: Int,
    ): Int {
        val fallbackEstimate = systemPromptTokens + fallbackToolDeclarationTokens
        val estimate = systemPromptTokens + (measuredToolDeclarationTokens ?: fallbackToolDeclarationTokens)
        return maxOf(estimate, fallbackEstimate, UNCALIBRATED_FIXED_PROMPT_FLOOR)
    }

    /** Uses only positive measurements that include the complete app-owned prompt. */
    fun calibratedToolDeclarationTokens(
        result: GenerationResult.Complete,
        systemPromptTokens: Int,
        requestPromptTokens: Int,
        turnInvolvedToolCallOrContinuation: Boolean,
    ): Int? {
        if (turnInvolvedToolCallOrContinuation) return null
        val prefillTokenCount = result.prefillTokenCount ?: return null
        val appOwnedPromptTokens = systemPromptTokens + requestPromptTokens
        if (prefillTokenCount < appOwnedPromptTokens) return null
        return (prefillTokenCount - appOwnedPromptTokens).takeIf { it > 0 }
    }
}
