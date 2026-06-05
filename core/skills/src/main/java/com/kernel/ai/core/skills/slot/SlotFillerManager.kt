package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.intent.IntentContractRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State machine that manages multi-turn slot-filling for quick intents in conversation mode.
 *
 * **Flow:**
 * 1. [QuickIntentRouter.route] returns [QuickIntentRouter.RouteResult.NeedsSlot] when a
 *    regex-matched intent is missing a required parameter.
 * 2. [ChatViewModel] calls [startSlotFill], stores the [PendingSlotRequest][com.kernel.ai.core.skills.slot.PendingSlotRequest], and shows
 *    [PendingSlotRequest.promptMessage] as an assistant bubble.
 * 3. On the user's next message, [ChatViewModel] detects [hasPending] and calls
 *    [onUserReply] *instead* of routing to QIR or the LLM.
 * 4. [onUserReply] merges the reply, asks [IntentContractRegistry.nextMissingSlot], and either
 *    returns [SlotFillResult.NeedsMore] with the next prompt or [SlotFillResult.Completed]
 *    with the fully merged params.
 * 5. [ChatViewModel] executes the completed intent exactly as it would a direct QIR match.
 * This class is a `@Singleton` so it survives configuration changes alongside ChatViewModel.
 * State is intentionally *not* persisted across process death — an interrupted slot fill
 * is a recoverable UX edge case (user simply re-asks).
 */
@Singleton
class SlotFillerManager @Inject constructor(
    private val intentContractRegistry: IntentContractRegistry,
) {
    private val pendingRequests = mutableMapOf<String, PendingSlotRequest>()

    /** Tracks which conversations have a recovery-originated slot fill in progress (P0 guard). */
    private val recoveryConversations = mutableSetOf<String>()

    val hasPending: Boolean get() = pendingRequests.isNotEmpty()

    fun hasPendingFor(conversationId: String): Boolean = pendingRequests.containsKey(conversationId)

    val pendingRequest: PendingSlotRequest? get() = pendingRequests.values.firstOrNull()

    fun pendingRequestFor(conversationId: String): PendingSlotRequest? = pendingRequests[conversationId]

    fun startSlotFill(conversationId: String, request: PendingSlotRequest) {
        pendingRequests[conversationId] = request
    }

    /** Mark a slot fill as originating from recovery (risk gate check). */
    fun markRecovery(conversationId: String) { recoveryConversations.add(conversationId) }

    /** Check if the in-progress slot fill for [conversationId] came from recovery. */
    fun isRecovery(conversationId: String): Boolean = conversationId in recoveryConversations

    /** Clear recovery-origin tracking for [conversationId]. */
    fun clearRecovery(conversationId: String) { recoveryConversations.remove(conversationId) }

    /**
     * Called with the user's reply when a slot fill is in progress.
     *
     * @return [SlotFillResult.Completed] with all params merged when the slot contract is
     *         satisfied, [SlotFillResult.NeedsMore] when another required slot remains, or
     *         [SlotFillResult.Cancelled] if [message] is blank.
     */
    fun onUserReply(conversationId: String, message: String): SlotFillResult {
        val pending = pendingRequests[conversationId] ?: return SlotFillResult.Cancelled
        val normalizedMessage = normalizeSlotReply(message, pending.missingSlot.name)
        if (normalizedMessage.isBlank()) {
            pendingRequests.remove(conversationId)
            recoveryConversations.remove(conversationId)
            return SlotFillResult.Cancelled
        }

        val mergedParams = pending.existingParams + mapOf(pending.missingSlot.name to normalizedMessage)
        val nextMissingSlot = intentContractRegistry.nextMissingSlot(
            intentName = pending.intentName,
            params = mergedParams,
        )
        return if (nextMissingSlot != null) {
            val nextRequest = PendingSlotRequest(
                intentName = pending.intentName,
                existingParams = mergedParams,
                missingSlot = nextMissingSlot,
                isRecovery = pending.isRecovery,
            )
            pendingRequests[conversationId] = nextRequest
            SlotFillResult.NeedsMore(nextRequest)
        } else {
            pendingRequests.remove(conversationId)
            recoveryConversations.remove(conversationId)
            SlotFillResult.Completed(
                intentName = pending.intentName,
                params = mergedParams,
            )
        }
    }

    fun cancel() {
        pendingRequests.clear()
        recoveryConversations.clear()
    }
}
