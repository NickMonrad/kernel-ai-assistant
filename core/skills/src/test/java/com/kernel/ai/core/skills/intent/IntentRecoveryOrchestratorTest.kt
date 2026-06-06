package com.kernel.ai.core.skills.intent

import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IntentRecoveryOrchestratorTest {

    private val registry = IntentContractRegistry()
    private val slotFillerManager = mockk<SlotFillerManager>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)

    @Test
    fun `recoversCalendarCreateFromColloquialInput`() {
        val extractors = setOf(CalendarSlotExtractor())
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        // "create a calendar event for dentist tomorrow at 3pm"
        // → title=Calendar Event (verb-title wins over for-title, #1100), date=tomorrow, time=3pm
        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "create a calendar event for dentist tomorrow at 3pm",
            candidate = IntentCandidate("create_calendar_event", 0.72f, "classifier"),
        )

        val askConfirmation = assertInstanceOf(RecoveryResult.AskConfirmation::class.java, result)
        assertEquals("create_calendar_event", askConfirmation.intentName)
        assertEquals("Calendar Event", askConfirmation.params["title"])
        assertEquals("tomorrow", askConfirmation.params["date"])
    }

    @Test
    fun `asksForMissingCalendarDate`() {
        val extractors = setOf(CalendarSlotExtractor())
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "schedule a meeting",
            candidate = IntentCandidate("create_calendar_event", 0.72f, "classifier"),
        )

        val askSlot = assertInstanceOf(RecoveryResult.AskSlot::class.java, result)
        assertEquals("create_calendar_event", askSlot.intentName)
        assertEquals("title", askSlot.missingSlot.name)
    }

    @Test
    fun `asksForMissingReminderTime`() {
        val extractors = emptySet<IntentSlotExtractor>()
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        // No extractors for add_reminder → orchestrator returns NotActionable
        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "remind me to call mum",
            candidate = IntentCandidate("add_reminder", 0.68f, "classifier"),
        )

        assertInstanceOf(RecoveryResult.NotActionable::class.java, result)
    }

    @Test
    fun `requiresConfirmationForSms`() {
        val smsExtractor = mockk<IntentSlotExtractor>(relaxed = true)
        every { smsExtractor.supports("send_sms") } returns true
        every { smsExtractor.extract(any(), any()) } returns com.kernel.ai.core.skills.intent.ExtractionResult.Extracted(mapOf(
            "contact" to "Sarah",
            "message" to "I'm running late",
        ))

        val extractors = setOf(smsExtractor)
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "tell Sarah I'm running late",
            candidate = IntentCandidate("send_sms", 0.62f, "classifier"),
        )

        val askConfirmation = assertInstanceOf(RecoveryResult.AskConfirmation::class.java, result)
        assertEquals("send_sms", askConfirmation.intentName)
    }

    @Test
    fun `doesNotExecuteWeakNonActionableCandidate`() {
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, emptySet())

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "something about the weather",
            candidate = IntentCandidate("get_weather", 0.48f, "classifier"),
        )

        assertInstanceOf(RecoveryResult.NotActionable::class.java, result)
    }

    @Test
    fun `fallsThroughWhenConfidenceBelowThreshold`() {
        val extractors = setOf(CalendarSlotExtractor())
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "schedule a meeting tomorrow",
            candidate = IntentCandidate("create_calendar_event", 0.30f, "classifier"),
        )

        assertInstanceOf(RecoveryResult.NotActionable::class.java, result)
    }

    @Test
    fun `executesLowRiskIntentWhenSlotsComplete`() {
        val memoryExtractor = mockk<IntentSlotExtractor>(relaxed = true)
        every { memoryExtractor.supports("save_memory") } returns true
        every { memoryExtractor.extract(any(), any()) } returns com.kernel.ai.core.skills.intent.ExtractionResult.Extracted(mapOf(
            "content" to "I parked on level 3",
        ))

        val extractors = setOf(memoryExtractor)
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "I parked on level 3",
            candidate = IntentCandidate("save_memory", 0.75f, "classifier"),
        )

        val execute = assertInstanceOf(RecoveryResult.Execute::class.java, result)
        assertEquals("save_memory", execute.intentName)
        assertEquals("I parked on level 3", execute.params["content"])
    }

    @Test
    fun `usesSlotFillerForMissingRequiredSlot`() {
        val extractors = setOf(CalendarSlotExtractor())
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "put dentist appointment",
            candidate = IntentCandidate("create_calendar_event", 0.72f, "classifier"),
        )

        val askSlot = assertInstanceOf(RecoveryResult.AskSlot::class.java, result)
        assertEquals("date", askSlot.missingSlot.name)
    }

    @Test
    fun `returnsNotActionableForUnknownIntent`() {
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, emptySet())

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "do something weird",
            candidate = IntentCandidate("nonexistent_intent", 0.80f, "classifier"),
        )

        assertInstanceOf(RecoveryResult.NotActionable::class.java, result)
    }

    @Test
    fun `mediumRiskRequiresConfirmationWhenSlotsFilled`() {
        val extractors = setOf(CalendarSlotExtractor())
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, extractors)

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "for dentist next Tuesday at 2pm",
            candidate = IntentCandidate("create_calendar_event", 0.80f, "classifier"),
        )

        val askConfirmation = assertInstanceOf(RecoveryResult.AskConfirmation::class.java, result)
        assertEquals("create_calendar_event", askConfirmation.intentName)
        assertEquals("Dentist", askConfirmation.params["title"])
    }

    @Test
    fun `returnsNotActionableWhenNoExtractorForIntent`() {
        val orchestrator = IntentRecoveryOrchestrator(registry, slotFillerManager, skillRegistry, emptySet())

        val result = orchestrator.recover(
            conversationId = "conv-1",
            input = "send a message to Sarah",
            candidate = IntentCandidate("send_sms", 0.72f, "classifier"),
        )

        assertInstanceOf(RecoveryResult.NotActionable::class.java, result)
    }
}
