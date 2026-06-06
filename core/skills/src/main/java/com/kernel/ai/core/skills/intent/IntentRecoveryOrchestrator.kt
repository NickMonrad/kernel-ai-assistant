package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Middle layer between [QuickIntentRouter.RouteResult.FallThrough] and the Gemma fallback.
 *
 * When [QuickIntentRouter] returns a [FallThrough][com.kernel.ai.core.skills.QuickIntentRouter.RouteResult.FallThrough]
 * with a [bestGuess] above [SOFT_FALLBACK_THRESHOLD][IntentContractRegistry.SOFT_FALLBACK_THRESHOLD],
 * this orchestrator uses deterministic slot extractors to fill parameters, checks risk levels,
 * and returns a [RecoveryResult] for the caller to dispatch.
 *
 * **Rules:**
 * - Only operates when `bestConfidence >= SOFT_FALLBACK_THRESHOLD`.
 * - Never executes medium/high-risk intents without confirmation.
 * - Never executes if required slots are missing.
 * - Uses deterministic extractors only — no Gemma calls.
 * - Ambiguous input returns [RecoveryResult.AskClarification] or [RecoveryResult.NotActionable].
 */
@Singleton
class IntentRecoveryOrchestrator @Inject constructor(
    private val registry: IntentContractRegistry,
    private val slotFillerManager: SlotFillerManager,
    private val skillRegistry: SkillRegistry,
    private val extractors: Set<@JvmSuppressWildcards IntentSlotExtractor>,
) {
    /**
     * Attempt to recover a deterministic action from a [FallThrough][com.kernel.ai.core.skills.QuickIntentRouter.RouteResult.FallThrough] bestGuess.
     *
     * @param conversationId The current conversation ID (for slot-fill state tracking).
     * @param input The raw user input text.
     * @param candidate The best-guess intent from the fallthrough.
     * @return A [RecoveryResult] indicating what to do next.
     */
    fun recover(
        conversationId: String,
        input: String,
        candidate: IntentCandidate,
    ): RecoveryResult {
        val contract = registry.get(candidate.intentName) ?: return RecoveryResult.NotActionable

        if (candidate.confidence < IntentContractRegistry.SOFT_FALLBACK_THRESHOLD) {
            return RecoveryResult.NotActionable
        }

        // Step 1: Check a deterministic extractor exists for this intent. Without one,
        // we cannot safely fill slots — return NotActionable so Gemma handles it.
        val extractor = extractors.firstOrNull { it.supports(candidate.intentName) }
            ?: return RecoveryResult.NotActionable
        val canonicalIntentName = contract.intentName

        // Step 2: Run the matching deterministic extractor.
        // If the extractor signals NotActionable (e.g. capability query), return it.
        val extractionResult = extractor.extract(input, contract)
        val extractedParams = when (extractionResult) {
            is ExtractionResult.NotActionable -> return RecoveryResult.NotActionable
            is ExtractionResult.Extracted -> extractionResult.params
        }

        // Step 2: Check for missing required slots
        val firstMissingSlot = registry.nextMissingSlot(canonicalIntentName, extractedParams)
        if (firstMissingSlot != null) {
            return RecoveryResult.AskSlot(
                intentName = canonicalIntentName,
                existingParams = extractedParams,
                missingSlot = firstMissingSlot,
            )
        }

        // Step 3: All required slots are present — check risk level
        return when (contract.riskLevel) {
            IntentRiskLevel.HIGH, IntentRiskLevel.MEDIUM -> {
                RecoveryResult.AskConfirmation(
                    intentName = canonicalIntentName,
                    params = extractedParams,
                    message = buildConfirmationMessage(canonicalIntentName, extractedParams),
                )
            }
            IntentRiskLevel.LOW -> {
                RecoveryResult.Execute(
                    intentName = canonicalIntentName,
                    params = extractedParams,
                )
            }
        }
    }

    private fun buildConfirmationMessage(
        intentName: String,
        params: Map<String, String>,
    ): String = when (intentName) {
        "send_sms" -> {
            val contact = params["contact"] ?: "someone"
            val message = params["message"]?.take(60)?.let { " saying \"$it\"" } ?: ""
            "I can send a message to $contact$message. Shall I go ahead?"
        }
        "send_email" -> {
            val contact = params["contact"] ?: "someone"
            val subject = params["subject"]?.let { " about \"$it\"" } ?: ""
            "I can send an email to $contact$subject. Shall I send it?"
        }
        "make_call" -> {
            val contact = params["contact"] ?: "someone"
            "I can call $contact. Shall I go ahead?"
        }
        "remove_important_date" -> {
            val label = params["label"] ?: "an important date"
            "Shall I remove $label?"
        }
        "create_calendar_event" -> {
            val title = params["title"] ?: "an event"
            val date = params["date"] ?: ""
            val time = params["time"] ?: ""
            val whenStr = buildList {
                if (date.isNotBlank()) add(date)
                if (time.isNotBlank()) add(time)
            }.joinToString(" at ")
            val detail = if (whenStr.isNotBlank()) " for $whenStr" else ""
            "I can schedule $title$detail. Shall I add it to your calendar?"
        }
        "add_reminder" -> {
            val item = params["item"] ?: "something"
            val day = params["day"] ?: ""
            val time = params["time"] ?: ""
            val whenStr = buildList {
                if (day.isNotBlank()) add(day)
                if (time.isNotBlank()) add(time)
            }.joinToString(" at ")
            val detail = if (whenStr.isNotBlank()) " for $whenStr" else ""
            "I can set a reminder to $item$detail. Shall I?"
        }
        "save_important_date" -> {
            val label = params["label"] ?: "an important date"
            val date = params["date"] ?: ""
            val detail = if (date.isNotBlank()) " for $date" else ""
            "I can save $label$detail. Shall I?"
        }
        else -> "I can help with that. Shall I go ahead?"
    }
}
