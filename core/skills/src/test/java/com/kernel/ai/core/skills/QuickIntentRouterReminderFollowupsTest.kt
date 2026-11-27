package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** #2152 router follow-ups: day and day part kept, "tonight at 2 at night" asked, bare-hour rule for "to" times. */
class QuickIntentRouterReminderFollowupsTest {

    private val date = LocalDate.of(2026, 11, 27)

    /** The clocks the issue asks for: morning, afternoon, and just before midnight. */
    private val clocks = listOf("09:00", "14:00", "23:50")

    private fun routerAt(clock: String): QuickIntentRouter {
        val (hour, minute) = clock.split(":").map { it.toInt() }
        return QuickIntentRouter(clock = { date.atTime(hour, minute) })
    }

    private fun route(router: QuickIntentRouter, text: String): QuickIntentRouter.MatchedIntent {
        val result = router.route(text)
        return assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result, "route('$text')").intent
    }

    private fun askedSlot(
        router: QuickIntentRouter,
        text: String,
        slot: String,
    ): QuickIntentRouter.RouteResult.NeedsSlot {
        val result = router.route(text)
        val needsSlot = assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result, "route('$text')")
        assertEquals("add_reminder", needsSlot.intent.intentName)
        assertEquals(slot, needsSlot.missingSlot.name)
        return needsSlot
    }

    @Test
    fun `tomorrow evening at 7 keeps the day, is pm, and leaves the item without day words`() {
        clocks.forEach { clock ->
            val intent = route(routerAt(clock), "remind me to ring mum tomorrow evening at 7")
            assertEquals("ring mum", intent.params["item"], "at $clock")
            assertEquals("tomorrow", intent.params["day"], "at $clock")
            assertEquals("19:00", intent.params["time"], "at $clock")
        }
    }

    @Test
    fun `tomorrow morning at 7 keeps the day, is am, and leaves the item without day words`() {
        clocks.forEach { clock ->
            val intent = route(routerAt(clock), "remind me to ring mum tomorrow morning at 7")
            assertEquals("ring mum", intent.params["item"], "at $clock")
            assertEquals("tomorrow", intent.params["day"], "at $clock")
            assertEquals("7:00", intent.params["time"], "at $clock")
        }
    }

    @Test
    fun `day part before the task with a day keeps the day and is pm`() {
        clocks.forEach { clock ->
            val evening = route(routerAt(clock), "remind me tomorrow evening at 7 to ring mum")
            assertEquals("ring mum", evening.params["item"], "at $clock")
            assertEquals("tomorrow", evening.params["day"], "at $clock")
            assertEquals("19:00", evening.params["time"], "at $clock")

            val morning = route(routerAt(clock), "remind me tomorrow morning at 7 to ring mum")
            assertEquals("ring mum", morning.params["item"], "at $clock")
            assertEquals("7:00", morning.params["time"], "at $clock")
        }
    }

    @Test
    fun `tonight at 7 strips the day word from the item`() {
        clocks.forEach { clock ->
            val intent = route(routerAt(clock), "remind me to put the bins out tonight at 7")
            assertEquals("put the bins out", intent.params["item"], "at $clock")
            assertEquals("19:00", intent.params["time"], "at $clock")
            assertEquals("today", intent.params["day"], "at $clock")
            assertEquals("tonight", intent.params["day_cue"], "at $clock")
        }
    }

    @Test
    fun `tonight at 2 at night asks which, like tonight at 2`() {
        clocks.forEach { clock ->
            val needsSlot = askedSlot(routerAt(clock), "remind me to ring mum tonight at 2 at night", "time_of_day")
            assertEquals("ring mum", needsSlot.intent.params["item"], "at $clock")
            assertEquals("02:00", needsSlot.intent.params["bare_time"], "at $clock")
            assertEquals("tonight", needsSlot.intent.params["day"], "at $clock")
            assertEquals("Do you mean 2 in the morning? Yes or no.", needsSlot.missingSlot.promptTemplate, "at $clock")
        }
    }

    @Test
    fun `at 10 to 12 is the next occurrence of 11 50, not a fixed reading`() {
        assertEquals(
            listOf("11:50", "23:50", "11:50"),
            clocks.map { route(routerAt(it), "remind me to ring mum at 10 to 12").params["time"] },
        )
        clocks.forEach { clock ->
            assertNull(route(routerAt(clock), "remind me to ring mum at 10 to 12").params["day"], "at $clock")
        }
    }

    @Test
    fun `quarter to 12 is the next occurrence of 11 45, not a fixed reading`() {
        assertEquals(
            listOf("11:45", "23:45", "11:45"),
            clocks.map { route(routerAt(it), "remind me to ring mum at quarter to 12").params["time"] },
        )
    }

    @Test
    fun `quarter to 12 on another day asks morning or evening and keeps the day`() {
        clocks.forEach { clock ->
            val needsSlot = askedSlot(
                routerAt(clock),
                "remind me to ring mum tomorrow at quarter to 12",
                "time_of_day",
            )
            assertEquals("11:45", needsSlot.intent.params["bare_time"], "at $clock")
            assertEquals("tomorrow", needsSlot.intent.params["day"], "at $clock")
            assertEquals("Morning or evening?", needsSlot.missingSlot.promptTemplate, "at $clock")
        }
    }

    @Test
    fun `quarter to eight pm is 19 45 and the item is ring mum`() {
        clocks.forEach { clock ->
            val intent = route(routerAt(clock), "remind me to ring mum at quarter to eight pm")
            assertEquals("ring mum", intent.params["item"], "at $clock")
            assertEquals("19:45", intent.params["time"], "at $clock")
        }
    }
}
