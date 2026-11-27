package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.intent.IntentContractRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderSlotBareHourTest {

    private val day = LocalDate.of(2026, 11, 27)
    private val manager = SlotFillerManager(IntentContractRegistry())

    private fun at(hour: Int, minute: Int = 0): LocalDateTime = day.atTime(hour, minute)

    private fun askFor(
        conversationId: String,
        missingSlot: String,
        existing: Map<String, String>,
    ) {
        manager.startSlotFill(
            conversationId,
            PendingSlotRequest(
                intentName = "add_reminder",
                existingParams = existing,
                missingSlot = SlotSpec(name = missingSlot, promptTemplate = "When?"),
            ),
        )
    }

    private fun completed(result: SlotFillResult): Map<String, String> =
        assertInstanceOf(SlotFillResult.Completed::class.java, result, "expected Completed, got $result").params

    @Test
    fun `bare hour answer to when uses the next occurrence and keeps the day`() {
        askFor("c1", "time", mapOf("item" to "ring mum", "day" to "tomorrow"))
        val params = completed(manager.onUserReply("c1", "6", now = at(14)))
        assertEquals("18:00", params["time"])
        assertEquals("tomorrow", params["day"])
    }

    @Test
    fun `bare hour answer with no day uses the next occurrence after now`() {
        askFor("c2", "time", mapOf("item" to "ring mum"))
        assertEquals("06:00", completed(manager.onUserReply("c2", "6", now = at(19))).getValue("time"))
    }

    @Test
    fun `bare hour answer just before midnight is the morning occurrence`() {
        askFor("c3", "time", mapOf("item" to "ring mum"))
        assertEquals("06:00", completed(manager.onUserReply("c3", "6", now = at(23, 30))).getValue("time"))
    }

    @Test
    fun `spoken bare hour answer resolves like digits`() {
        askFor("c4", "time", mapOf("item" to "ring mum"))
        assertEquals("18:00", completed(manager.onUserReply("c4", "six", now = at(14))).getValue("time"))
    }

    @Test
    fun `tonight stored from the router becomes pm today on completion`() {
        askFor("c5", "time", mapOf("item" to "put the bins out", "day" to "tonight"))
        val params = completed(manager.onUserReply("c5", "7", now = at(14)))
        assertEquals("19:00", params["time"])
        assertEquals("today", params["day"])
    }

    @Test
    fun `tonight in an item answer becomes pm today on completion`() {
        askFor("c6", "item", emptyMap())
        val params = completed(manager.onUserReply("c6", "ring mum tonight at 7", now = at(14)))
        assertEquals("ring mum", params["item"])
        assertEquals("19:00", params["time"])
        assertEquals("today", params["day"])
    }

    @Test
    fun `am or pm answers keep their meaning`() {
        askFor("c7", "time", mapOf("item" to "ring mum", "day" to "tonight"))
        assertEquals("7:00", completed(manager.onUserReply("c7", "7am", now = at(14))).getValue("time"))
    }
}
