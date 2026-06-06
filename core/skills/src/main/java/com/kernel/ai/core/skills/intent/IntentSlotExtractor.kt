package com.kernel.ai.core.skills.intent

/**
 * The result of attempting to extract slot values from user input.
 *
 * - [Extracted] — extraction produced the given params (may be empty).
 * - [NotActionable] — the input is not an actionable request for this intent
 *   (e.g. a capability question). The orchestrator must return [RecoveryResult.NotActionable]
 *   rather than asking for missing slots.
 */
sealed class ExtractionResult {
    data class Extracted(val params: Map<String, String>) : ExtractionResult()
    data object NotActionable : ExtractionResult()
}

/**
 * Deterministically extracts slot values from raw user input for a given intent.
 *
 * Extractors are called by the [IntentRecoveryOrchestrator] after a [QuickIntentRouter.RouteResult.FallThrough]
 * has a [bestGuess] above [IntentContractRegistry.SOFT_FALLBACK_THRESHOLD]. They attempt to fill
 * required and optional slots without invoking Gemma.
 *
 * Each extractor should be focused on a single intent or group of closely related intents.
 * The first extractor whose [supports] returns true for the candidate intent name is used.
 */
interface IntentSlotExtractor {
    /**
     * Returns true if this extractor knows how to extract slots for [intentName].
     */
    fun supports(intentName: String): Boolean

    /**
     * Attempts to extract slot values from [input] for the intent described by [contract].
     *
     * Return [ExtractionResult.Extracted] with the params found (may be empty map).
     * Return [ExtractionResult.NotActionable] if the input is not an actionable request
     * for this intent (e.g. a capability question).
     */
    fun extract(input: String, contract: IntentContract): ExtractionResult
}
