package com.kernel.ai.core.skills.slot

/** Result returned by [SlotFillerManager.onUserReply]. */
sealed class SlotFillResult {
    /** All required slots have been filled — ready to execute the intent. */
    data class Completed(
        val intentName: String,
        val params: Map<String, String>,
    ) : SlotFillResult()

    /** User sent a blank reply or explicitly cancelled. */
    data object Cancelled : SlotFillResult()
}
