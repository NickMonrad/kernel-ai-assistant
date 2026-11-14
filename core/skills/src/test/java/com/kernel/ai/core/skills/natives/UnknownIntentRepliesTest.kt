package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnknownIntentRepliesTest {

    @Test
    fun `read-style calendar names get the calendar read reply`() {
        listOf(
            "get_calendar", "read_calendar", "list_events", "check_schedule", "show_agenda",
            "what's_on_calendar", "fetch_appointments", "view-events", "get_meetings", "read_diary",
        ).forEach { name ->
            assertEquals(UnknownIntentReplies.CALENDAR_READ_REPLY, UnknownIntentReplies.replyFor(name), name)
        }
    }

    @Test
    fun `noun-only calendar names are treated as reads`() {
        listOf("calendar_events", "todays_schedule", "upcoming_events", "meetings").forEach { name ->
            assertEquals(UnknownIntentReplies.CALENDAR_READ_REPLY, UnknownIntentReplies.replyFor(name), name)
        }
    }

    @Test
    fun `read-style email names get the email read reply`() {
        listOf("get_email", "read_mail", "list_emails", "search_inbox", "find_mails", "unread_emails")
            .forEach { name ->
                assertEquals(UnknownIntentReplies.EMAIL_READ_REPLY, UnknownIntentReplies.replyFor(name), name)
            }
    }

    @Test
    fun `write-style calendar and email names get the neutral generic reply`() {
        listOf(
            "add_calendar_event", "create_event", "delete_calendar_event", "send_mail",
            "compose_email", "schedule_meeting", "book_appointment", "update_calendar_event",
        ).forEach { name ->
            assertEquals(UnknownIntentReplies.GENERIC_REPLY, UnknownIntentReplies.replyFor(name), name)
        }
    }

    @Test
    fun `names without a calendar or email noun get the generic reply`() {
        listOf("frobnicate_widgets", "", "   ", "get_weather").forEach { name ->
            assertEquals(UnknownIntentReplies.GENERIC_REPLY, UnknownIntentReplies.replyFor(name), name)
        }
    }

    @Test
    fun `loggableName keeps only lowercase letters digits and underscores`() {
        assertEquals("get_calendarnow", UnknownIntentReplies.loggableName("Get_Calendar Now!"))
        assertEquals("whatsonmycalendar", UnknownIntentReplies.loggableName("what's on my calendar?"))
        assertEquals("abscript", UnknownIntentReplies.loggableName("a b\n<script>"))
    }

    @Test
    fun `loggableName truncates long input`() {
        assertEquals(40, UnknownIntentReplies.loggableName("a".repeat(200)).length)
    }
}
