package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.slot.SlotSpec

/**
 * The result of attempting to recover a deterministic action from a
 * [QuickIntentRouter.RouteResult.FallThrough] with a [bestGuess].
 *
 * The [IntentRecoveryOrchestrator] returns one of these, and [ChatViewModel]
 * dispatches accordingly:
 *
 * - [Execute] — run the intent directly via the existing skill execution path.
 * - [AskSlot] — one or more required slots are missing; use [SlotFillerManager] to ask.
 * - [AskConfirmation] — the intent is medium/high risk; ask the user before executing.
 * - [AskClarification] — the input is ambiguous; ask the user to clarify.
 * - [NotActionable] — cannot determine what to do; fall through to Gemma.
 */
sealed class RecoveryResult {
    /**
     * Execute the intent directly. Slots have been fully extracted and the risk
     * level does not require confirmation.
     */
    data class Execute(
        val intentName: String,
        val params: Map<String, String>,
    ) : RecoveryResult()

    /**
     * A required slot is missing. The caller should use [SlotFillerManager] to
     * prompt the user for [missingSlot].
     */
    data class AskSlot(
        val intentName: String,
        val existingParams: Map<String, String>,
        val missingSlot: SlotSpec,
    ) : RecoveryResult()

    /**
     * The recovered intent requires user confirmation before execution
     * (medium or high risk). Show [message] and wait for affirmation.
     */
    data class AskConfirmation(
        val intentName: String,
        val params: Map<String, String>,
        val message: String,
    ) : RecoveryResult()

    /**
     * The input is too ambiguous to recover deterministically. Ask the
     * user to clarify with [message].
     */
    data class AskClarification(
        val message: String,
    ) : RecoveryResult()

    /**
     * The orchestrator cannot produce a deterministic result. Fall through
     * to Gemma for natural language processing.
     */
    data object NotActionable : RecoveryResult()
}
