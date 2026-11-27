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
    fun `bare hour answer on a day other than today asks morning or evening`() {
        askFor("c1", "time", mapOf("item" to "ring mum", "day" to "tomorrow"))
        val next = assertInstanceOf(SlotFillResult.NeedsMore::class.java, manager.onUserReply("c1", "6", now = at(14))).request
        assertEquals("time_of_day", next.missingSlot.name)
        assertEquals("Morning or evening?", next.missingSlot.promptTemplate)
        val params = completed(manager.onUserReply("c1", "evening", now = at(14)))
        assertEquals("18:00", params["time"])
        assertEquals("tomorrow", params["day"])
    }

    @Test
    fun `morning answer to the morning or evening question gives the am time`() {
        askFor("c8", "time", mapOf("item" to "ring mum", "day" to "tomorrow"))
        manager.onUserReply("c8", "6", now = at(14))
        assertEquals("06:00", completed(manager.onUserReply("c8", "morning", now = at(14))).getValue("time"))
    }

    @Test
    fun `bare hour with a day in the reply asks morning or evening`() {
        askFor("c9", "time", mapOf("item" to "ring mum"))
        val next = assertInstanceOf(SlotFillResult.NeedsMore::class.java, manager.onUserReply("c9", "at 7 tomorrow", now = at(14))).request
        assertEquals("time_of_day", next.missingSlot.name)
        assertEquals("tomorrow", next.existingParams["day"])
    }

    @Test
    fun `this evening at a bare hour is pm today`() {
        askFor("c10", "time", mapOf("item" to "ring mum"))
        assertEquals("19:00", completed(manager.onUserReply("c10", "this evening at 7", now = at(6, 30))).getValue("time"))
    }

    @Test
    fun `tonight at 1 to 4 asks yes or no and yes means that hour in the morning tomorrow`() {
        askFor("c11", "time", mapOf("item" to "ring mum", "day" to "tonight"))
        val next = assertInstanceOf(SlotFillResult.NeedsMore::class.java, manager.onUserReply("c11", "3", now = at(14))).request
        assertEquals("time_of_day", next.missingSlot.name)
        assertEquals("Do you mean 3 in the morning? Yes or no.", next.missingSlot.promptTemplate)
        val params = completed(manager.onUserReply("c11", "yes", now = at(14)))
        assertEquals("03:00", params["time"])
        assertEquals("tomorrow", params["day"])
    }

    @Test
    fun `tonight at 1 to 4 no means that hour pm today`() {
        askFor("c12", "time", mapOf("item" to "ring mum", "day" to "tonight"))
        manager.onUserReply("c12", "3", now = at(14))
        val params = completed(manager.onUserReply("c12", "no", now = at(14)))
        assertEquals("15:00", params["time"])
        assertEquals("today", params["day"])
    }

    @Test
    fun `bare hour answer with no day uses the next occurrence after now`() {
        askFor("c2", "time", mapOf("item" to "ring mum"))
        assertEquals("06:00", completed(manager.onUserReply("c2", "6", now = at(19))).getValue("time"))
    }

    @Test
    fun `bare hour with a day and no at asks morning or evening`() {
        askFor("c13", "time", mapOf("item" to "ring mum"))
        val next = assertInstanceOf(SlotFillResult.NeedsMore::class.java, manager.onUserReply("c13", "7 tomorrow", now = at(14))).request
        assertEquals("time_of_day", next.missingSlot.name)
        assertEquals("tomorrow", next.existingParams["day"])
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
