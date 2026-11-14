package com.kernel.ai.core.skills.natives

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarEventRepliesTest {

    private val zone: ZoneId = ZoneOffset.UTC
    // Wednesday 2025-11-19
    private val today = LocalDate.of(2025, 11, 19)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun timed(
        title: String,
        start: Long,
        end: Long,
        calendar: String = "Work",
        location: String? = null,
    ) = CalendarEventRecord(title, start, end, allDay = false, calendarName = calendar, location = location)

    @Test
    fun rangeParsingAcceptsModelAndSpokenForms() {
        assertEquals(CalendarRange.THIS_WEEK, CalendarRange.parse("this week"))
        assertEquals(CalendarRange.NEXT_WEEK, CalendarRange.parse("next_week"))
        assertEquals(CalendarRange.UPCOMING, CalendarRange.parse("coming up"))
        assertEquals(null, CalendarRange.parse("someday"))
        assertEquals(null, CalendarRange.parse(null))
    }

    @Test
    fun thisWeekRunsFromTodayUntilNextMonday() {
        val (begin, end) = CalendarEventReplies.windowMillis(CalendarRange.THIS_WEEK, today, zone)
        assertEquals(millis(today, 0), begin)
        assertEquals(millis(LocalDate.of(2025, 11, 24), 0), end)
    }

    @Test
    fun nextWeekIsTheSevenDaysFromNextMonday() {
        val (begin, end) = CalendarEventReplies.windowMillis(CalendarRange.NEXT_WEEK, today, zone)
        assertEquals(millis(LocalDate.of(2025, 11, 24), 0), begin)
        assertEquals(millis(LocalDate.of(2025, 12, 1), 0), end)
    }

    @Test
    fun keywordMatchesTitleCalendarOrLocationCaseInsensitively() {
        val event = timed("Annual Check", 0, 1, calendar = "Health", location = "Dental Clinic")
        assertTrue(CalendarEventReplies.matchesKeyword(event, "dentAL"))
        assertTrue(CalendarEventReplies.matchesKeyword(event, "health"))
        assertTrue(CalendarEventReplies.matchesKeyword(event, "check"))
        assertFalse(CalendarEventReplies.matchesKeyword(event, "gym"))
    }

    @Test
    fun emptyTodayReadsAsNothingOnCalendar() {
        assertEquals(
            "Nothing on your calendar today.",
            CalendarEventReplies.formatRead(CalendarRange.TODAY, null, emptyList(), zone),
        )
    }

    @Test
    fun todayListsTimesTitleCalendarAndLocationWithoutDayNames() {
        val events = listOf(
            timed("Standup", millis(today, 9), millis(today, 9, 30), location = "Room 4"),
            CalendarEventRecord(
                "Holiday", millis(today, 0), millis(today, 0),
                allDay = true, calendarName = "Personal", location = null,
            ),
        )
        assertEquals(
            "You have 2 events today. 9:00 AM to 9:30 AM: Standup, on Work, at Room 4. All day: Holiday, on Personal.",
            CalendarEventReplies.formatRead(CalendarRange.TODAY, null, events, zone),
        )
    }

    @Test
    fun multiDayWindowNamesTheDayForEachEvent() {
        val friday = LocalDate.of(2025, 11, 21)
        val events = listOf(timed("Dentist", millis(friday, 10), millis(friday, 11), calendar = "Health"))
        assertEquals(
            "You have 1 event this week. Friday 21 November, 10:00 AM to 11:00 AM: Dentist, on Health.",
            CalendarEventReplies.formatRead(CalendarRange.THIS_WEEK, null, events, zone),
        )
    }

    @Test
    fun keywordReplyNamesNextMatchAndCountsLater() {
        val first = LocalDate.of(2025, 11, 25)
        val second = LocalDate.of(2025, 12, 2)
        val events = listOf(
            timed("Dentist", millis(first, 14), millis(first, 15), calendar = "Health"),
            timed("Dentist cleaning", millis(second, 9), millis(second, 9, 30), calendar = "Health"),
        )
        assertEquals(
            "Your next match for 'dentist' is Tuesday 25 November, 2:00 PM to 3:00 PM: Dentist, on Health. There is 1 more after that.",
            CalendarEventReplies.formatRead(null, "dentist", events, zone),
        )
    }

    @Test
    fun keywordWithNoMatchNamesTheWindow() {
        assertEquals(
            "I couldn't find anything matching 'dentist' on your calendar in the next year.",
            CalendarEventReplies.formatRead(null, "dentist", emptyList(), zone),
        )
    }

    @Test
    fun readReplyCapsListedEventsAndCountsTheRest() {
        val events = (1..10).map { timed("Item $it", millis(today, 8 + it % 10), millis(today, 8 + it % 10, 30)) }
        val reply = CalendarEventReplies.formatRead(CalendarRange.TODAY, null, events, zone)
        assertTrue(reply.endsWith("And 2 more."))
    }

    @Test
    fun createdTimedEventConfirmsTitleCalendarAndTimes() {
        assertEquals(
            "Added 'Dentist' to Work on Wednesday 19 November at 2:00 PM, until 3:00 PM.",
            CalendarEventReplies.formatCreated(
                title = "Dentist",
                beginMillis = millis(today, 14),
                endMillis = millis(today, 15),
                allDay = false,
                calendarName = "Work",
                zone = zone,
            ),
        )
    }

    @Test
    fun createdAllDayEventConfirmsDayWithoutTimes() {
        assertEquals(
            "Added 'Holiday' for Wednesday 19 November, all day.",
            CalendarEventReplies.formatCreated(
                title = "Holiday",
                beginMillis = millis(today, 0),
                endMillis = millis(today.plusDays(1), 0),
                allDay = true,
                calendarName = null,
                zone = zone,
            ),
        )
    }
}
