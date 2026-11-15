package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            "reschedule_meeting", "invite_to_meeting", "rsvp_event", "new_event", "draft_email",
            "write_email", "archive_email", "mark_email_read", "clear_inbox",
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
    fun `list-shaped unknown names get a reply that offers the real list action`() {
        listOf("add_add_to_list", "add_list_item", "bulk_list_add", "get_lists").forEach { name ->
            assertEquals(UnknownIntentReplies.LIST_REPLY, UnknownIntentReplies.replyFor(name), name)
        }
        assertTrue(UnknownIntentReplies.LIST_REPLY.contains("add milk to my shopping list"))
    }

    @Test
    fun `calendar reads containing list stay calendar replies`() {
        assertEquals(UnknownIntentReplies.CALENDAR_READ_REPLY, UnknownIntentReplies.replyFor("list_calendar_events"))
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
