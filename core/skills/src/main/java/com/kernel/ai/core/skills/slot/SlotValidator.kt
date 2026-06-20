package com.kernel.ai.core.skills.slot

/**
 * Validates a single slot value for a specific intent and slot name.
 *
 * Implementations are stateless and thread-safe — the registry holds a single
 * instance per validator, shared across all slot-fill and dispatch paths.
 */
fun interface SlotValidator {
    /**
     * Validate [value] for [slotName] on [intentName].
     *
     * @return [SlotValidationResult.valid] when the value is acceptable;
     *         [SlotValidationResult.invalid] with a user-facing clarification prompt otherwise.
     */
    fun validate(intentName: String, slotName: String, value: String): SlotValidationResult
}
