package com.kernel.ai.core.skills.slot

/** Result returned by [SlotFillerManager.onUserReply]. */
sealed class SlotFillResult {
    /** All required slots have been filled — ready to execute the intent. */
    data class Completed(
        val intentName: String,
        val params: Map<String, String>,
    ) : SlotFillResult()

    /** Another required slot is still missing — keep collecting. */
    data class NeedsMore(
        val request: PendingSlotRequest,
    ) : SlotFillResult()

    /**
     * The user provided a value for the current slot, but it failed validation —
     * re-prompt with targeted clarification.
     *
     * The slot-fill remains pending; the user's next reply retries filling the
     * same slot with a corrected value.
     */
    data class InvalidSlot(
        val request: PendingSlotRequest,
    ) : SlotFillResult()

    /** User sent a blank reply or explicitly cancelled. */
    data object Cancelled : SlotFillResult()
}
