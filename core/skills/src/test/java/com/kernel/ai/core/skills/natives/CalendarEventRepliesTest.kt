package com.kernel.ai.core.skills.natives

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CalendarEventRepliesTest {

    private val utc = ZoneId.of("UTC")
    private val nz = ZoneId.of("Pacific/Auckland")
    private val west = ZoneId.of("America/New_York")

    /** All-day rows are stored as UTC midnight of their calendar date (CalendarContract convention). */
    private fun allDay(date: LocalDate) = CalendarEventRecord(
        title = "Holiday",
        beginMillis = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(),
        allDay = true,
        calendarName = "Holidays",
        location = null,
    )

    private fun timed(zone: ZoneId, date: LocalDate, startHour: Int, endHour: Int) = CalendarEventRecord(
        title = "Meeting",
        beginMillis = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli(),
        endMillis = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli(),
        allDay = false,
        calendarName = "Work",
        location = null,
    )

    private fun instant(zone: ZoneId, date: LocalDate, hour: Int): Instant =
        date.atTime(hour, 0).atZone(zone).toInstant()

    @Nested
    @DisplayName("all-day events match the local day in every time zone")
    inner class AllDayDays {

        @Test
        @DisplayName("NZ (UTC+13): an all-day event on the local day is in today's window")
        fun nzTodayIncludesTodaysAllDay() {
            val today = LocalDate.of(2025, 11, 20)
            val window = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, today, nz, instant(nz, today, 9))
            assertTrue(CalendarEventReplies.isInWindow(allDay(today), window))
        }

        @Test
        @DisplayName("NZ: yesterday's all-day event is not in today's window")
        fun nzYesterdayExcluded() {
            val today = LocalDate.of(2025, 11, 20)
            val window = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, today, nz, instant(nz, today, 9))
            assertFalse(CalendarEventReplies.isInWindow(allDay(today.minusDays(1)), window))
        }

        @Test
        @DisplayName("west of UTC: an all-day event for tomorrow is not in today's window, and is in tomorrow's")
        fun westTomorrowAllDay() {
            val today = LocalDate.of(2025, 11, 20)
            val tomorrow = today.plusDays(1)
            val todayWindow = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, today, west, instant(west, today, 22))
            val tomorrowWindow = CalendarEventReplies.readWindow(CalendarRange.TOMORROW, false, today, west, instant(west, today, 22))
            assertFalse(CalendarEventReplies.isInWindow(allDay(tomorrow), todayWindow))
            assertTrue(CalendarEventReplies.isInWindow(allDay(tomorrow), tomorrowWindow))
        }

        @Test
        @DisplayName("UTC: a range end is exclusive, so the day after the window is excluded")
        fun exclusiveEnd() {
            val today = LocalDate.of(2025, 11, 20)
            val thisWeek = CalendarEventReplies.readWindow(CalendarRange.THIS_WEEK, false, today, utc, instant(utc, today, 9))
            assertFalse(CalendarEventReplies.isInWindow(allDay(thisWeek.toDateExclusive), thisWeek))
            assertTrue(CalendarEventReplies.isInWindow(allDay(thisWeek.toDateExclusive.minusDays(1)), thisWeek))
        }
    }

    @Nested
    @DisplayName("timed events use exact instants")
    inner class TimedEvents {

        @Test
        @DisplayName("an event that ends exactly at the window start is excluded")
        fun endingAtStartExcluded() {
            val today = LocalDate.of(2025, 11, 20)
            val window = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, today, utc, instant(utc, today, 12))
            val startOfToday = today.atStartOfDay(utc).toInstant().toEpochMilli()
            val endsAtMidnight = CalendarEventRecord(
                title = "Late",
                beginMillis = startOfToday - 3_600_000L,
                endMillis = startOfToday,
                allDay = false,
                calendarName = "Work",
                location = null,
            )
            assertFalse(CalendarEventReplies.isInWindow(endsAtMidnight, window))
        }

        @Test
        @DisplayName("upcoming starts at now: an event that already ended is excluded, one in progress is included")
        fun upcomingStartsAtNow() {
            val today = LocalDate.of(2025, 11, 20)
            val now = instant(utc, today, 12)
            val window = CalendarEventReplies.readWindow(CalendarRange.UPCOMING, false, today, utc, now)
            assertFalse(CalendarEventReplies.isInWindow(timed(utc, today, 9, 10), window))
            assertTrue(CalendarEventReplies.isInWindow(timed(utc, today, 11, 13), window))
        }

        @Test
        @DisplayName("a multi-day all-day block is in the window of every day it covers")
        fun multiDayAllDayOverlapsWindow() {
            val monday = LocalDate.of(2025, 11, 17)
            val wednesday = monday.plusDays(2)
            val block = allDay(monday).copy(endMillis = monday.plusDays(5).atStartOfDay(utc).toInstant().toEpochMilli())
            val window = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, wednesday, utc, instant(utc, wednesday, 9))
            assertTrue(CalendarEventReplies.isInWindow(block, window))
        }

        @Test
        @DisplayName("an all-day event that ends at the window start day is excluded")
        fun allDayEndingAtWindowStartExcluded() {
            val monday = LocalDate.of(2025, 11, 17)
            val wednesday = monday.plusDays(2)
            val mondayOnly = allDay(monday).copy(endMillis = wednesday.atStartOfDay(utc).toInstant().toEpochMilli())
            val window = CalendarEventReplies.readWindow(CalendarRange.TODAY, false, wednesday, utc, instant(utc, wednesday, 9))
            assertFalse(CalendarEventReplies.isInWindow(mondayOnly, window))
        }

        @Test
        @DisplayName("NZ across DST: upcoming (7 days from 25 Sep 2026) keeps day 6 and drops day 7")
        fun upcomingDstBoundaryDropsDaySeven() {
            val today = LocalDate.of(2026, 9, 25)
            val window = CalendarEventReplies.readWindow(CalendarRange.UPCOMING, false, today, nz, instant(nz, today, 12))
            val daySix = LocalDate.of(2026, 10, 1)
            val daySeven = LocalDate.of(2026, 10, 2)
            assertTrue(CalendarEventReplies.isInWindow(timed(nz, daySix, 23, 23), window))
            assertFalse(CalendarEventReplies.isInWindow(timed(nz, daySeven, 0, 1), window))
            assertFalse(CalendarEventReplies.isInWindow(allDay(daySeven), window))
        }

        @Test
        @DisplayName("keyword search without a range starts at now and looks ahead a year")
        fun keywordWindow() {
            val today = LocalDate.of(2025, 11, 20)
            val now = instant(utc, today, 12)
            val window = CalendarEventReplies.readWindow(null, true, today, utc, now)
            assertEquals(now.toEpochMilli(), window.fromMillis)
            assertEquals(today.plusDays(365), window.toDateExclusive)
        }
    }

    @Nested
    @DisplayName("keyword matching")
    inner class Keywords {

        @Test
        @DisplayName("matches title, calendar name or location, case-insensitively")
        fun matchesAnyField() {
            val event = CalendarEventRecord(
                title = "Dentist check",
                beginMillis = 0,
                endMillis = 1,
                allDay = false,
                calendarName = "Family",
                location = "Riverside Clinic",
            )
            assertTrue(CalendarEventReplies.matchesKeyword(event, "DENTIST"))
            assertTrue(CalendarEventReplies.matchesKeyword(event, "family"))
            assertTrue(CalendarEventReplies.matchesKeyword(event, "riverside"))
            assertFalse(CalendarEventReplies.matchesKeyword(event, "gym"))
        }
    }

    @Nested
    inner class CalendarNames {
        private val day = LocalDate.of(2026, 6, 1)

        private fun timedIn(calendar: String) = CalendarEventRecord(
            title = "Dentist",
            beginMillis = day.atTime(9, 0).atZone(utc).toInstant().toEpochMilli(),
            endMillis = day.atTime(10, 0).atZone(utc).toInstant().toEpochMilli(),
            allDay = false,
            calendarName = calendar,
            location = null,
        )

        @Test
        fun readReplyOmitsGoogleAccountEmailCalendarName() {
            val reply = CalendarEventReplies.formatRead(
                CalendarRange.TODAY, null, listOf(timedIn("nick.monrad@gmail.com")), utc,
            )
            assertFalse(reply.contains("@"), reply)
            assertFalse(reply.contains("gmail"), reply)
            assertFalse(reply.contains(" on "), reply)
            assertTrue(reply.contains("Dentist"), reply)
        }

        @Test
        fun readReplyKeepsPlainCalendarName() {
            val reply = CalendarEventReplies.formatRead(
                CalendarRange.TODAY, null, listOf(timedIn("Work")), utc,
            )
            assertTrue(reply.contains(", on Work"), reply)
        }

        @Test
        fun createdReplyOmitsEmailCalendarName() {
            val begin = day.atTime(9, 0).atZone(utc).toInstant().toEpochMilli()
            val reply = CalendarEventReplies.formatCreated(
                title = "Dentist",
                beginMillis = begin,
                endMillis = begin + 3_600_000L,
                allDay = false,
                calendarName = "nick.monrad@gmail.com",
                zone = utc,
            )
            assertFalse(reply.contains("@"), reply)
            assertFalse(reply.contains(" to nick"), reply)
        }

        @Test
        fun spokenCalendarNameDropsEmailShapedAndBlankNames() {
            assertEquals(null, CalendarEventReplies.spokenCalendarName("  "))
            assertEquals(null, CalendarEventReplies.spokenCalendarName("Work (me@example.com)"))
            assertEquals("Work", CalendarEventReplies.spokenCalendarName(" Work "))
        }
    }
}
