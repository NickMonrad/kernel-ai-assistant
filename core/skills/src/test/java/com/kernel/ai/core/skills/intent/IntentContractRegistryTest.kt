package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.slot.SlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IntentContractRegistryTest {

    private val registry = IntentContractRegistry()

    @Test
    fun `calendarContractIncludesRequiredTitleAndDate`() {
        val contract = registry.get("create_calendar_event")
        assertNotNull(contract)
        assertEquals(IntentRiskLevel.MEDIUM, contract!!.riskLevel)
        assertEquals(
            setOf("title", "date"),
            contract.requiredSlots.keys,
        )
    }

    @Test
    fun `smsContractRequiresContactAndMessage`() {
        val contract = registry.get("send_sms")
        assertNotNull(contract)
        assertEquals(IntentRiskLevel.HIGH, contract!!.riskLevel)
        assertEquals(
            setOf("contact", "message"),
            contract.requiredSlots.keys,
        )
    }

    @Test
    fun `highRiskIntentsRequireConfirmation`() {
        for (name in listOf("make_call", "send_sms", "send_email", "remove_important_date")) {
            val contract = registry.get(name)
            assertNotNull(contract, "Contract for $name should exist")
            assertEquals(
                IntentRiskLevel.HIGH,
                contract!!.riskLevel,
                "Expected HIGH risk for $name",
            )
        }
    }

    @Test
    fun `allQuickIntentSlotContractsHaveRegistryEquivalent`() {
        // These are the slot contracts that existed in QuickIntentRouter.slotContracts
        val expectedContracts = setOf(
            "make_call", "send_sms", "send_email",
            "create_calendar_event", "add_to_list", "create_list",
            "save_important_date", "remove_important_date",
            "save_memory", "add_reminder", "create_note",
        )
        for (name in expectedContracts) {
            val contract = registry.get(name)
            assertNotNull(contract, "Missing contract for $name")
        }
    }

    @Test
    fun `nextMissingSlotReturnsNullWhenAllRequiredPresent`() {
        val params = mapOf("contact" to "Nick", "message" to "Running late")
        val result = registry.nextMissingSlot("send_sms", params)
        assertNull(result)
    }

    @Test
    fun `nextMissingSlotReturnsFirstMissingSlot`() {
        val params = mapOf("contact" to "Nick")
        val result = registry.nextMissingSlot("send_sms", params)
        assertNotNull(result)
        assertEquals("message", result!!.name)
    }

    @Test
    fun `requiredSlotsReturnsEmptyForUnknownIntent`() {
        val result = registry.requiredSlots("nonexistent_intent")
        assertEquals(emptyMap<String, com.kernel.ai.core.skills.slot.SlotSpec>(), result)
    }

    @Test
    fun `riskLevelReturnsLowForUnknownIntent`() {
        assertEquals(IntentRiskLevel.LOW, registry.riskLevel("nonexistent_intent"))
    }

    @Test
    fun `aliasesMapToSameContract`() {
        // IntentContractRegistry currently doesn't define aliases,
        // but verify intentName lookup works for all registered contracts
        val contract = registry.get("save_memory")
        assertNotNull(contract)
        assertEquals("content", contract!!.requiredSlots.keys.first())
    }

    @Test
    fun `SOFT_FALLBACK_THRESHOLD is 0_55`() {
        assertEquals(0.55f, IntentContractRegistry.SOFT_FALLBACK_THRESHOLD, 0.001f)
    }

    @Test
    fun `createNoteContractExists`() {
        val contract = registry.get("create_note")
        assertNotNull(contract)
        assertEquals(IntentRiskLevel.LOW, contract!!.riskLevel)
        assertEquals("content", contract.requiredSlots.keys.single())
    }

    @Test
    fun `addReminderContractHasAllSlots`() {
        val contract = registry.get("add_reminder")
        assertNotNull(contract)
        assertEquals(IntentRiskLevel.MEDIUM, contract!!.riskLevel)
        assertEquals(setOf("item", "day", "time"), contract.requiredSlots.keys)
    }
}
