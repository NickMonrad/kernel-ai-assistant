package com.kernel.ai.core.skills.slot

import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine that manages multi-turn slot-filling for quick intents.
 *
 * **Flow:**
 * 1. [QuickIntentRouter.route] returns [QuickIntentRouter.RouteResult.NeedsSlot] when a
 *    regex-matched intent is missing a required parameter.
 * 2. [ChatViewModel] calls [startSlotFill], stores the [PendingSlotRequest], and shows
 *    [PendingSlotRequest.promptMessage] as an assistant bubble.
 * 3. On the user's next message, [ChatViewModel] detects [hasPending] and calls
 *    [onUserReply] *instead* of routing to QIR or the LLM.
 * 4. [onUserReply] returns [SlotFillResult.Completed] with the merged params, or
 *    [SlotFillResult.Cancelled] if the user sent a blank reply.
 * 5. [ChatViewModel] executes the completed intent exactly as it would a direct QIR match.
 *
 * This class is a `@Singleton` so it survives configuration changes alongside ChatViewModel.
 * State is intentionally *not* persisted across process death — an interrupted slot fill
 * is a recoverable UX edge case (user simply re-asks).
 */
@Singleton
class SlotFillerManager @Inject constructor() {

    private var _pendingRequest: PendingSlotRequest? = null

    val hasPending: Boolean get() = _pendingRequest != null

    val pendingRequest: PendingSlotRequest? get() = _pendingRequest

    fun startSlotFill(request: PendingSlotRequest) {
        _pendingRequest = request
    }

    /**
     * Called with the user's reply when a slot fill is in progress.
     *
     * @return [SlotFillResult.Completed] with all params merged, or
     *         [SlotFillResult.Cancelled] if [message] is blank.
     */
    fun onUserReply(message: String): SlotFillResult {
        val pending = _pendingRequest ?: return SlotFillResult.Cancelled
        _pendingRequest = null
        return if (message.isBlank()) {
            SlotFillResult.Cancelled
        } else {
            SlotFillResult.Completed(
                intentName = pending.intentName,
                params = pending.existingParams + mapOf(pending.missingSlot.name to message.trim()),
            )
        }
    }

    fun cancel() {
        _pendingRequest = null
    }
}
