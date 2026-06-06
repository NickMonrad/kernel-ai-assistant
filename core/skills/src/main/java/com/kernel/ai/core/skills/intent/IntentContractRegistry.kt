package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.slot.SlotSpec

/**
 * Central registry of [IntentContract]s that defines the required and optional slots
 * for every intent known to the system.
 *
 * This replaces the private [QuickIntentRouter.slotContracts] map so that the
 * [IntentRecoveryOrchestrator], [SlotFillerManager], and slot extractors can all
 * reference the same slot specifications without coupling to router internals.
 *
 * The registry is a `@Singleton` provided via Hilt.
 */
class IntentContractRegistry {

    private val contracts: Map<String, IntentContract> = CONTRACTS
        .flatMap { listOf(it.intentName to it) + it.aliases.map { alias -> alias to it } }
        .toMap()

    /** Returns the [IntentContract] for [intentName], or null if unknown. */
    fun get(intentName: String): IntentContract? = contracts[intentName]

    /** Returns just the required [SlotSpec] map for [intentName], or empty map if unknown. */
    fun requiredSlots(intentName: String): Map<String, SlotSpec> =
        contracts[intentName]?.requiredSlots ?: emptyMap()

    /** Returns the [IntentRiskLevel] for [intentName], or LOW if unknown. */
    fun riskLevel(intentName: String): IntentRiskLevel =
        contracts[intentName]?.riskLevel ?: IntentRiskLevel.LOW

    /**
     * Finds the first required slot that is missing (null or blank) in [params].
     * Returns null when all required slots are present.
     */
    fun nextMissingSlot(
        intentName: String,
        params: Map<String, String>,
    ): SlotSpec? = missingRequiredSlot(requiredSlots(intentName), params)

    /**
     * Returns the names of all intents that have contracts registered.
     */
    val knownIntentNames: Set<String> get() = contracts.keys

    companion object {
        /** Threshold below which a FallThrough bestGuess is not actionable. */
        const val SOFT_FALLBACK_THRESHOLD = 0.55f

        private val CONTRACTS: List<IntentContract> = listOf(
            // ── HIGH RISK (require confirmation before actioning) ─────────────
            IntentContract(
                intentName = "make_call",
                capability = "Make Call",
                requiredSlots = mapOf(
                    "contact" to SlotSpec(
                        name = "contact",
                        promptTemplate = "Who would you like to call?",
                    ),
                ),
                riskLevel = IntentRiskLevel.HIGH,
            ),
            IntentContract(
                intentName = "send_sms",
                capability = "Send SMS",
                requiredSlots = mapOf(
                    "contact" to SlotSpec(
                        name = "contact",
                        promptTemplate = "Who do you want to send a message to?",
                    ),
                    "message" to SlotSpec(
                        name = "message",
                        promptTemplate = "What would you like to say to {contact}?",
                    ),
                ),
                riskLevel = IntentRiskLevel.HIGH,
            ),
            IntentContract(
                intentName = "send_email",
                capability = "Send Email",
                requiredSlots = mapOf(
                    "contact" to SlotSpec(
                        name = "contact",
                        promptTemplate = "Who would you like to email?",
                    ),
                    "subject" to SlotSpec(
                        name = "subject",
                        promptTemplate = "What's the subject of your email to {contact}?",
                    ),
                    "body" to SlotSpec(
                        name = "body",
                        promptTemplate = "What would you like the email to say?",
                    ),
                ),
                riskLevel = IntentRiskLevel.HIGH,
            ),
            IntentContract(
                intentName = "remove_important_date",
                capability = "Remove Important Date",
                requiredSlots = mapOf(
                    "label" to SlotSpec(
                        name = "label",
                        promptTemplate = "Which important date should I remove?",
                    ),
                ),
                riskLevel = IntentRiskLevel.HIGH,
            ),

            // ── MEDIUM RISK ────────────────────────────────────────────────────
            IntentContract(
                intentName = "create_calendar_event",
                aliases = listOf("create_event"),
                capability = "Create Calendar Event",
                requiredSlots = mapOf(
                    "title" to SlotSpec(
                        name = "title",
                        promptTemplate = "What should I call the event?",
                    ),
                    "date" to SlotSpec(
                        name = "date",
                        promptTemplate = "What day is {title} for?",
                    ),
                ),
                riskLevel = IntentRiskLevel.MEDIUM,
            ),
            IntentContract(
                intentName = "add_reminder",
                capability = "Add Reminder",
                requiredSlots = mapOf(
                    "item" to SlotSpec(
                        name = "item",
                        promptTemplate = "What would you like me to remind you about?",
                    ),
                    "day" to SlotSpec(
                        name = "day",
                        promptTemplate = "Which day should I set the reminder for?",
                    ),
                    "time" to SlotSpec(
                        name = "time",
                        promptTemplate = "What time on {day} should I remind you to {item}?",
                    ),
                ),
                riskLevel = IntentRiskLevel.MEDIUM,
            ),
            IntentContract(
                intentName = "save_important_date",
                capability = "Save Important Date",
                requiredSlots = mapOf(
                    "label" to SlotSpec(
                        name = "label",
                        promptTemplate = "What important date should I save?",
                    ),
                    "date" to SlotSpec(
                        name = "date",
                        promptTemplate = "What date is {label}?",
                    ),
                ),
                riskLevel = IntentRiskLevel.MEDIUM,
            ),

            // ── LOW RISK (safe to execute with slots filled) ──────────────────
            IntentContract(
                intentName = "add_to_list",
                capability = "Add to List",
                requiredSlots = mapOf(
                    "item" to SlotSpec(
                        name = "item",
                        promptTemplate = "What would you like to add?",
                    ),
                    "list_name" to SlotSpec(
                        name = "list_name",
                        promptTemplate = "Which list should I add it to?",
                    ),
                ),
                riskLevel = IntentRiskLevel.LOW,
            ),
            IntentContract(
                intentName = "create_list",
                capability = "Create List",
                requiredSlots = mapOf(
                    "list_name" to SlotSpec(
                        name = "list_name",
                        promptTemplate = "What would you like to call the list?",
                    ),
                ),
                riskLevel = IntentRiskLevel.LOW,
            ),
            IntentContract(
                intentName = "save_memory",
                capability = "Save Memory",
                requiredSlots = mapOf(
                    "content" to SlotSpec(
                        name = "content",
                        promptTemplate = "What would you like me to remember?",
                    ),
                ),
                riskLevel = IntentRiskLevel.LOW,
            ),
            IntentContract(
                intentName = "create_note",
                capability = "Create Note",
                requiredSlots = mapOf(
                    "content" to SlotSpec(
                        name = "content",
                        promptTemplate = "What would you like me to write down?",
                    ),
                ),
                riskLevel = IntentRiskLevel.LOW,
            ),
        )
    }

    private fun missingRequiredSlot(
        requiredSlots: Map<String, SlotSpec>,
        params: Map<String, String>,
    ): SlotSpec? =
        requiredSlots.entries.firstOrNull { (key, _) -> params[key].isNullOrBlank() }?.value
}
