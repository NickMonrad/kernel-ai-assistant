package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CalendarLogRedactionTest {

    private val secretTitle = "Dentist with Dr Secret"

    @Test
    @DisplayName("a run_intent request naming a calendar intent is treated as calendar")
    fun runIntentRequestNamingCalendarIntentIsCalendar() {
        val request = """{"intent_name":"read_calendar_events","parameters":{}}"""
        assertTrue(CalendarLogRedaction.mentionsCalendarIntent("run_intent", request))
    }

    @Test
    @DisplayName("a non-calendar request is not treated as calendar")
    fun nonCalendarRequestPassesThrough() {
        val request = """{"intent_name":"set_timer","parameters":{"minutes":"5"}}"""
        assertFalse(CalendarLogRedaction.mentionsCalendarIntent("run_intent", request))
        assertEquals(request.take(256), CalendarLogRedaction.text(false, request, 256))
    }

    @Test
    @DisplayName("resolved calendar intent name is recognised; the skill name alone is not")
    fun resolvedIntentDecidesCalendar() {
        assertTrue(CalendarLogRedaction.isCalendarIntent("create_calendar_event"))
        assertFalse(CalendarLogRedaction.isCalendarIntent("run_intent"))
        assertFalse(CalendarLogRedaction.isCalendarIntent(null))
    }

    @Test
    @DisplayName("calendar text is replaced by the marker and length, never the content")
    fun calendarTextIsRedacted() {
        val reply = "1. $secretTitle at 9:00 AM"
        val logged = CalendarLogRedaction.text(true, reply, 1000)
        assertFalse(logged.contains("Secret"))
        assertEquals("${CalendarLogRedaction.REDACTED} (${reply.length} chars)", logged)
    }

    @Test
    @DisplayName("calendar params keep keys only; raw calendar params JSON is fully redacted")
    fun calendarParamsRedacted() {
        val params = mapOf("title" to secretTitle, "date" to "2026-09-25")
        val logged = CalendarLogRedaction.params("create_calendar_event", params)
        assertFalse(logged.contains(secretTitle))
        assertTrue(logged.contains("date"))
        assertFalse(CalendarLogRedaction.params("read_calendar_events", """{"query":"$secretTitle"}""").contains("Secret"))
    }

    @Test
    @DisplayName("non-calendar params are logged unchanged")
    fun nonCalendarParamsUnchanged() {
        assertEquals("{minutes=5}", CalendarLogRedaction.params("set_timer", mapOf("minutes" to "5")))
        assertEquals("""{"minutes":"5"}""", CalendarLogRedaction.params("set_timer", """{"minutes":"5"}"""))
    }
}
