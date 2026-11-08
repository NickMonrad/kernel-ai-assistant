package com.kernel.ai.core.skills.slot

/**
 * Result of validating a single slot value.
 *
 * @property isValid True when the value is acceptable for downstream dispatch.
 * @property errorMessage User-facing clarification prompt when invalid; null when valid.
 * @property correctedValue Optional corrected/normalised form of the value.
 */
data class SlotValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val correctedValue: String? = null,
) {
    companion object {
        fun valid(correctedValue: String? = null): SlotValidationResult =
            SlotValidationResult(isValid = true, correctedValue = correctedValue)

        fun invalid(errorMessage: String): SlotValidationResult =
            SlotValidationResult(isValid = false, errorMessage = errorMessage)
    }
}
