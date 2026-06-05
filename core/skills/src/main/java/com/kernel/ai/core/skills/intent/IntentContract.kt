package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.slot.SlotSpec

/**
 * Describes a known intent that can be recovered or executed by the [IntentRecoveryOrchestrator].
 *
 * Mirrors the slot contracts that were previously private to [QuickIntentRouter] so that the
 * orchestrator, slot extractors, and [IntentContractRegistry] can validate and fill missing
 * parameters without coupling to the router internals.
 *
 * @property intentName The canonical intent name (e.g. "send_sms", "create_calendar_event").
 * @property capability A human-readable label for the capability (e.g. "Send SMS").
 * @property aliases Alternative intent names that map to the same contract.
 * @property requiredSlots Slots that must be filled before the intent can be executed.
 * @property optionalSlots Slots that may be filled but are not required.
 * @property riskLevel How risky the action is — used to decide whether confirmation is required.
 */
data class IntentContract(
    val intentName: String,
    val capability: String,
    val aliases: List<String> = emptyList(),
    val requiredSlots: Map<String, SlotSpec>,
    val optionalSlots: Map<String, SlotSpec> = emptyMap(),
    val riskLevel: IntentRiskLevel,
)

enum class IntentRiskLevel { LOW, MEDIUM, HIGH }
