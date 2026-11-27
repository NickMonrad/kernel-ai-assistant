package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class QuickIntentRouterReminderTimeTest {

    private val day = LocalDate.of(2026, 11, 27)

    private fun routerAt(hour: Int, minute: Int = 0): QuickIntentRouter =
        QuickIntentRouter(clock = { day.atTime(hour, minute) as LocalDateTime })

    private fun route(router: QuickIntentRouter, text: String): QuickIntentRouter.MatchedIntent {
        val result = router.route(text)
        return assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result, "route('$text')").intent
    }

    private fun askedSlot(
        router: QuickIntentRouter,
        text: String,
        slot: String = "time",
    ): QuickIntentRouter.RouteResult.NeedsSlot {
        val result = router.route(text)
        val needsSlot = assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result, "route('$text')")
        assertEquals("add_reminder", needsSlot.intent.intentName)
        assertEquals(slot, needsSlot.missingSlot.name)
        return needsSlot
    }

    @Test
    fun `tonight with a bare hour in the item means pm today`() {
        val intent = route(routerAt(14), "remind me to put the bins out tonight at 7")
        assertEquals("add_reminder", intent.intentName)
        assertEquals("19:00", intent.params["time"])
        assertEquals("today", intent.params["day"])
    }

    @Test
    fun `tonight before the time means pm today`() {
        val intent = route(routerAt(14), "remind me tonight at 7 to ring mum")
        assertEquals("19:00", intent.params["time"])
        assertEquals("today", intent.params["day"])
    }

    @Test
    fun `set a reminder for a bare hour then tonight means pm today`() {
        val intent = route(routerAt(14), "set a reminder for 7 tonight to buy milk")
        assertEquals("19:00", intent.params["time"])
        assertEquals("today", intent.params["day"])
    }

    @Test
    fun `tonight at a passed time keeps today so the handler rejects it instead of rolling`() {
        val intent = route(routerAt(23, 50), "remind me to ring mum tonight at 11")
        assertEquals("23:00", intent.params["time"])
        assertEquals("today", intent.params["day"])
    }

    @Test
    fun `bare hour without a day picks the next occurrence after now`() {
        assertEquals("18:00", route(routerAt(14), "remind me to ring mum at 6").params["time"])
        assertEquals("06:00", route(routerAt(19), "remind me to ring mum at 6").params["time"])
        assertNull(route(routerAt(14), "remind me to ring mum at 6").params["day"])
    }

    @Test
    fun `bare hour after midnight picks the earliest occurrence`() {
        assertEquals("06:00", route(routerAt(0, 30), "remind me to ring mum at 6").params["time"])
        assertEquals("01:00", route(routerAt(23, 30), "remind me to ring mum at 1").params["time"])
    }

    @Test
    fun `explicit tomorrow with a bare hour asks for the time and keeps the day`() {
        val needsSlot = askedSlot(routerAt(14), "remind me to ring mum tomorrow at 6", "time_of_day")
        assertEquals("06:00", needsSlot.intent.params["bare_time"])
        assertEquals("tomorrow", needsSlot.intent.params["day"])
        assertEquals("ring mum", needsSlot.intent.params["item"])
        assertNull(needsSlot.intent.params["time"])
    }

    @Test
    fun `day before the task with a bare hour asks for the time and keeps the day`() {
        val needsSlot = askedSlot(routerAt(14), "remind me tomorrow at 6 to ring mum", "time_of_day")
        assertEquals("tomorrow", needsSlot.intent.params["day"])
        assertEquals("ring mum", needsSlot.intent.params["item"])
    }

    @Test
    fun `tonight with no time asks for the time and keeps tonight as the cue`() {
        val needsSlot = askedSlot(routerAt(14), "remind me to put the bins out tonight")
        assertEquals("tonight", needsSlot.intent.params["day"])
    }

    @Test
    fun `am or pm times are unchanged`() {
        assertEquals("17:00", route(routerAt(14), "remind me to ring mum at 5 pm").params["time"])
        assertEquals("17:00", route(routerAt(14), "remind me tonight at 5pm to ring mum").params["time"])
    }

    @Test
    fun `hour 12 keeps its current meaning`() {
        assertEquals("12:00", route(routerAt(14), "remind me to call mum at 12").params["time"])
    }

    @Test
    fun `spoken bare hour in the item reminder resolves like digits`() {
        assertEquals("18:00", route(routerAt(14), "remind me to ring mum at six").params["time"])
    }

    @Test
    fun `tonight at 1 to 4 asks morning or evening with the bare hour`() {
        val needsSlot = askedSlot(routerAt(14), "remind me tonight at 3 to ring mum", "time_of_day")
        assertEquals("03:00", needsSlot.intent.params["bare_time"])
        assertEquals("tonight", needsSlot.intent.params["day"])
    }

    @Test
    fun `day in the time phrase with a bare hour asks morning or evening`() {
        val needsSlot = askedSlot(routerAt(14), "remind me to watch tonight's game at 7 tomorrow", "time_of_day")
        assertEquals("07:00", needsSlot.intent.params["bare_time"])
        assertEquals("tomorrow", needsSlot.intent.params["day"])
    }

    @Test
    fun `minutes are kept for a bare hour without a day`() {
        assertEquals("19:30", route(routerAt(14), "remind me to ring mum at 7 30").params["time"])
        assertEquals("07:30", route(routerAt(20), "remind me to ring mum at 7 30").params["time"])
    }

    @Test
    fun `day part word keeps the hour as written`() {
        assertEquals("7:00", route(routerAt(14), "remind me to ring mum at 7 in the morning").params["time"])
    }
}
