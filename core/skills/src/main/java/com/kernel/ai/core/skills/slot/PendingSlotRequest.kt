package com.kernel.ai.core.skills.slot

/**
 * Represents an in-flight slot-filling request — a matched intent whose execution
 * is paused pending a user reply that will fill [missingSlot].
 */
data class PendingSlotRequest(
    val intentName: String,
    val existingParams: Map<String, String>,
    val missingSlot: SlotSpec,
) {
    val promptMessage: String get() = missingSlot.buildPrompt(existingParams)
}
