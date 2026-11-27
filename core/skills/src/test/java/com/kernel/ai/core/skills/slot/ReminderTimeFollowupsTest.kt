package com.kernel.ai.core.skills.slot

import com.kernel.ai.core.skills.BARE_TIME_PARAM
import com.kernel.ai.core.skills.TIME_OF_DAY_SLOT
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.pendingTimeOfDaySlot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** #2146 review follow-ups, checked at four clocks: 00:30, 06:30, 14:00, 23:50. */
class ReminderTimeFollowupsTest {

    private val day = LocalDate.of(2026, 11, 27)
    private val clocks = listOf("00:30", "06:30", "14:00", "23:50")
    private val manager = SlotFillerManager(IntentContractRegistry())

    private fun now(clock: String): LocalDateTime {
        val (hour, minute) = clock.split(":").map { it.toInt() }
        return day.atTime(hour, minute)
    }

    /** One expected value per clock, in [clocks] order. */
    private fun assertScheduleAtClocks(
        text: String,
        missingSlot: String,
        key: String,
        expected: List<String?>,
        existing: Map<String, String> = emptyMap(),
    ) {
        clocks.forEachIndexed { i, clock ->
            val reply = parseReminderSlotReply(text, missingSlot, existing, now(clock))
            assertEquals(expected[i], reply.schedule[key], "'$text' at $clock ($key)")
        }
    }

    @Test
    fun `item keeps its number words when a schedule follows`() {
        clocks.forEach { clock ->
            val buy = parseReminderSlotReply("buy one more tomorrow", "item", now = now(clock))
            assertEquals("buy one more", buy.value, "at $clock")
            assertEquals("tomorrow", buy.schedule["day"], "at $clock")

            val tablets = parseReminderSlotReply("take two tablets tomorrow", "item", now = now(clock))
            assertEquals("take two tablets", tablets.value, "at $clock")
            assertEquals("tomorrow", tablets.schedule["day"], "at $clock")

            val call = parseReminderSlotReply("call three people at 5 pm", "item", now = now(clock))
            assertEquals("call three people", call.value, "at $clock")
            assertEquals("17:00", call.schedule["time"], "at $clock")
        }
    }

    @Test
    fun `hour oh minutes and hour minute words read as clock minutes`() {
        assertScheduleAtClocks("seven oh five pm", "time", "time", List(4) { "19:05" })
        assertScheduleAtClocks("seven ten pm", "time", "time", List(4) { "19:10" })
        assertScheduleAtClocks("nine oh five in the morning", "time", "time", List(4) { "9:05" })
        assertScheduleAtClocks("at seven oh five pm", "time", "time", List(4) { "19:05" })
        assertScheduleAtClocks("ten oh five pm", "time", "time", List(4) { "22:05" })
    }

    @Test
    fun `this evening at 2 asks the morning question today and morning or evening tomorrow`() {
        val today = parseReminderSlotReply("this evening at 2", "time", now = now("14:00"))
        assertEquals("02:00", today.schedule[BARE_TIME_PARAM])
        assertEquals("Do you mean 2 in the morning? Yes or no.", pendingTimeOfDaySlot(today.schedule)?.promptTemplate)
        val tomorrow = parseReminderSlotReply("this evening at 2", "time", mapOf("day" to "tomorrow"), now("14:00"))
        assertEquals("tomorrow", tomorrow.schedule["day"])
        assertEquals("Morning or evening?", pendingTimeOfDaySlot(tomorrow.schedule)?.promptTemplate)
    }

    @Test
    fun `item keeps a number word that sits right before a day word`() {
        clocks.forEach { clock ->
            val buy = parseReminderSlotReply("buy one tomorrow", "item", now = now(clock))
            assertEquals("buy one", buy.value, "at $clock")
            assertEquals("tomorrow", buy.schedule["day"], "at $clock")
            val take = parseReminderSlotReply("take two tonight", "item", now = now(clock))
            assertEquals("take two", take.value, "at $clock")
        }
    }

    @Test
    fun `at 7 to 8 is not a minute phrase so the bare hour rule applies`() {
        assertScheduleAtClocks("at 7 to 8", "time", "time", listOf("07:00", "07:00", "19:00", "07:00"))
    }

    @Test
    fun `tens word plus a unit word is the whole minute`() {
        assertScheduleAtClocks("seven twenty one pm", "time", "time", List(4) { "19:21" })
        assertScheduleAtClocks("seven forty three pm", "time", "time", List(4) { "19:43" })
        assertScheduleAtClocks("seven fifty nine pm", "time", "time", List(4) { "19:59" })
    }

    @Test
    fun `tens word plus a unit word answers the time after a day is given`() {
        val reply = parseReminderSlotReply("seven twenty one pm", "time", mapOf("day" to "tomorrow"), now("14:00"))
        assertEquals("tomorrow", reply.schedule["day"])
        assertEquals("19:21", reply.schedule["time"])
    }

    @Test
    fun `spoken item with number words keeps them when the time is a spoken half past`() {
        assertScheduleAtClocks(
            "take two tablets at half past seven", "item", "time",
            listOf("07:30", "07:30", "19:30", "07:30"),
        )
        val reply = parseReminderSlotReply("take two tablets at half past seven", "item", now = now("14:00"))
        assertEquals("take two tablets", reply.value)
    }

    @Test
    fun `ten to eight is 7 50 and half seven is 7 30`() {
        assertScheduleAtClocks("ten to eight", "time", "time", listOf("07:50", "07:50", "19:50", "07:50"))
        assertScheduleAtClocks("half seven", "time", "time", listOf("07:30", "07:30", "19:30", "07:30"))
        assertScheduleAtClocks("half past seven", "time", "time", listOf("07:30", "07:30", "19:30", "07:30"))
    }

    @Test
    fun `spoken minute words before past and to are read as clock minutes`() {
        assertScheduleAtClocks("twenty five past seven", "time", "time", listOf("07:25", "07:25", "19:25", "07:25"))
        assertScheduleAtClocks("twenty to eight", "time", "time", listOf("07:40", "07:40", "19:40", "07:40"))
    }

    @Test
    fun `quarter to 1 with no day is 00 45 or 12 45 by the next occurrence`() {
        assertScheduleAtClocks("quarter to 1", "time", "time", listOf("00:45", "12:45", "00:45", "00:45"))
    }

    @Test
    fun `tonight at quarter to 1 asks the 1 in the morning question`() {
        clocks.forEach { clock ->
            val reply = parseReminderSlotReply("tonight at quarter to 1", "time", now = now(clock))
            assertEquals("00:45", reply.schedule[BARE_TIME_PARAM], "at $clock")
            assertEquals("tonight", reply.schedule["day"], "at $clock")
            val question = pendingTimeOfDaySlot(reply.schedule)
            assertEquals("Do you mean quarter to 1 in the morning? Yes or no.", question?.promptTemplate, "at $clock")
        }
    }

    @Test
    fun `yes to the quarter to 1 question is the next 00 45`() {
        val asked = parseReminderSlotReply("tonight at quarter to 1", "time", now = now("14:00")).schedule
        clocks.forEach { clock ->
            val answer = parseReminderSlotReply("yes", TIME_OF_DAY_SLOT, asked, now(clock))
            assertEquals("00:45", answer.schedule["time"], "at $clock")
            val expectedDay = if (clock == "00:30") "today" else "tomorrow"
            assertEquals(expectedDay, answer.schedule["day"], "at $clock")
        }
    }

    @Test
    fun `no to the quarter to 1 question is 12 45 today`() {
        val asked = parseReminderSlotReply("tonight at quarter to 1", "time", now = now("14:00")).schedule
        clocks.forEach { clock ->
            val answer = parseReminderSlotReply("no", TIME_OF_DAY_SLOT, asked, now(clock))
            assertEquals("12:45", answer.schedule["time"], "at $clock")
            assertEquals("today", answer.schedule["day"], "at $clock")
        }
    }

    @Test
    fun `quarter to 1 on another day asks morning or evening and evening is 12 45`() {
        val asked = parseReminderSlotReply("quarter to 1 tomorrow", "time", now = now("14:00")).schedule
        assertEquals("Morning or evening?", pendingTimeOfDaySlot(asked)?.promptTemplate)
        val evening = parseReminderSlotReply("evening", TIME_OF_DAY_SLOT, asked, now("14:00")).schedule
        assertEquals("12:45", evening["time"])
        assertEquals("tomorrow", evening["day"])
        val morning = parseReminderSlotReply("morning", TIME_OF_DAY_SLOT, asked, now("14:00")).schedule
        assertEquals("00:45", morning["time"])
        assertEquals("tomorrow", morning["day"])
    }

    @Test
    fun `tonight at ten to eight is 19 50 tonight`() {
        assertScheduleAtClocks("tonight at ten to eight", "time", "time", List(4) { "19:50" })
    }

    @Test
    fun `evening at 7 answering morning or evening is 19 00`() {
        val pending = mapOf(BARE_TIME_PARAM to "07:00")
        assertScheduleAtClocks("evening at 7", TIME_OF_DAY_SLOT, "time", List(4) { "19:00" }, pending)
        assertScheduleAtClocks("in the evening at 7", TIME_OF_DAY_SLOT, "time", List(4) { "19:00" }, pending)
    }

    @Test
    fun `day part before the hour sets pm on the reminder time`() {
        assertScheduleAtClocks("tomorrow evening at 7", "time", "time", List(4) { "19:00" })
        assertScheduleAtClocks("friday evening at 7", "time", "time", List(4) { "19:00" })
        assertScheduleAtClocks("7 tomorrow evening", "time", "time", List(4) { "19:00" })
        val reply = parseReminderSlotReply("tomorrow evening at 7", "time", now = now("14:00"))
        assertEquals("tomorrow", reply.schedule["day"])
    }

    @Test
    fun `at night pms only hours 5 to 11`() {
        assertScheduleAtClocks("at 7 at night", "time", "time", List(4) { "19:00" })
        assertScheduleAtClocks("at 2 at night", "time", "time", List(4) { "2:00" })
        assertScheduleAtClocks("1 at night", "time", "time", List(4) { "1:00" })
    }

    @Test
    fun `tonight at 2 at night asks which`() {
        clocks.forEach { clock ->
            val reply = parseReminderSlotReply("tonight at 2 at night", "time", now = now(clock))
            assertEquals("02:00", reply.schedule[BARE_TIME_PARAM], "at $clock")
            assertEquals("tonight", reply.schedule["day"], "at $clock")
        }
    }

    @Test
    fun `7 30 pm typed with and without dots reads as 19 30`() {
        assertScheduleAtClocks("7 30 p.m.", "time", "time", List(4) { "19:30" })
        assertScheduleAtClocks("7 30 pm", "time", "time", List(4) { "19:30" })
    }

    @Test
    fun `chat route reads spoken half past seven like the typed route`() {
        clocks.forEach { clock ->
            manager.startSlotFill(
                "chat-$clock",
                PendingSlotRequest(
                    intentName = "add_reminder",
                    existingParams = mapOf("item" to "take two tablets"),
                    missingSlot = SlotSpec(name = "time", promptTemplate = "When?"),
                ),
            )
            val result = manager.onUserReply("chat-$clock", "half past seven", now = now(clock))
            val params = assertInstanceOf(SlotFillResult.Completed::class.java, result, "at $clock").params
            val expected = if (clock == "14:00") "19:30" else "07:30"
            assertEquals(expected, params["time"], "at $clock")
            assertEquals("take two tablets", params["item"], "at $clock")
        }
    }

    @Test
    fun `unclear answer to morning or evening is asked again with an apology`() {
        clocks.forEach { clock ->
            val conversation = "reask-$clock"
            manager.startSlotFill(
                conversation,
                PendingSlotRequest(
                    intentName = "add_reminder",
                    existingParams = mapOf("item" to "ring mum", "day" to "tomorrow"),
                    missingSlot = SlotSpec(name = "time", promptTemplate = "When?"),
                ),
            )
            manager.onUserReply(conversation, "6", now = now(clock))
            val again = assertInstanceOf(
                SlotFillResult.NeedsMore::class.java,
                manager.onUserReply(conversation, "maybe", now = now(clock)),
                "at $clock",
            ).request
            assertEquals("time_of_day", again.missingSlot.name, "at $clock")
            assertTrue(
                again.missingSlot.promptTemplate.startsWith("Sorry, I didn't catch that. "),
                "at $clock: ${again.missingSlot.promptTemplate}",
            )
        }
    }
}
