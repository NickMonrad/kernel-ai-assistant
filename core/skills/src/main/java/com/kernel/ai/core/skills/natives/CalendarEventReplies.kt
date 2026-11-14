package com.kernel.ai.core.skills.natives

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Windows the calendar read skill understands. Keeps model/router vocabulary in one place. */
enum class CalendarRange(val wireName: String, val phrase: String) {
    TODAY("today", "today"),
    TOMORROW("tomorrow", "tomorrow"),
    THIS_WEEK("this_week", "this week"),
    NEXT_WEEK("next_week", "next week"),
    UPCOMING("upcoming", "in the next 7 days"),
    ;

    companion object {
        fun parse(raw: String?): CalendarRange? {
            val normalized = raw?.trim()?.lowercase(Locale.ENGLISH)?.replace(' ', '_')?.replace('-', '_')
                ?: return null
            return when (normalized) {
                "today", "now" -> TODAY
                "tomorrow" -> TOMORROW
                "this_week", "week", "rest_of_week", "thisweek" -> THIS_WEEK
                "next_week", "nextweek" -> NEXT_WEEK
                "upcoming", "next_7_days", "next_seven_days", "coming_up" -> UPCOMING
                else -> null
            }
        }
    }
}

/** Pure calendar read/create logic: windows, keyword filtering and voice-friendly wording. No Android types. */
object CalendarEventReplies {

    /** Keyword searches without a range look this far ahead for the next match. */
    const val KEYWORD_LOOKAHEAD_DAYS = 365L

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private const val MAX_LISTED_EVENTS = 8

    /** Start (inclusive) and end (exclusive) of [range] in epoch millis, interpreted in [zone]. */
    fun windowMillis(range: CalendarRange?, today: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val (startDate, endDate) = when (range) {
            CalendarRange.TODAY, null -> today to today.plusDays(1)
            CalendarRange.TOMORROW -> today.plusDays(1) to today.plusDays(2)
            CalendarRange.THIS_WEEK -> today to nextMonday(today)
            CalendarRange.NEXT_WEEK -> nextMonday(today) to nextMonday(today).plusWeeks(1)
            CalendarRange.UPCOMING -> today to today.plusDays(7)
        }
        return startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
            endDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Keyword window for searches with no explicit range. */
    fun keywordWindowMillis(today: LocalDate, zone: ZoneId): Pair<Long, Long> =
        today.atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(KEYWORD_LOOKAHEAD_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Case-insensitive match on title, calendar name or location. */
    fun matchesKeyword(event: CalendarEventRecord, keyword: String): Boolean {
        val needle = keyword.trim().lowercase(Locale.ENGLISH)
        if (needle.isEmpty()) return true
        return event.title.lowercase(Locale.ENGLISH).contains(needle) ||
            event.calendarName.lowercase(Locale.ENGLISH).contains(needle) ||
            event.location?.lowercase(Locale.ENGLISH)?.contains(needle) == true
    }

    /**
     * Voice-friendly reply for a read. [events] must already be inside the window and sorted by start.
     * Keyword replies name only the next match.
     */
    fun formatRead(
        range: CalendarRange?,
        keyword: String?,
        events: List<CalendarEventRecord>,
        zone: ZoneId,
    ): String {
        val cleanKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanKeyword != null) {
            val matches = events.filter { matchesKeyword(it, cleanKeyword) }
            val windowPhrase = range?.phrase ?: "the next year"
            if (matches.isEmpty()) {
                return "I couldn't find anything matching '$cleanKeyword' on your calendar in $windowPhrase."
            }
            val next = matches.first()
            val laterCount = matches.size - 1
            val tail = if (laterCount > 0) " There ${if (laterCount == 1) "is 1 more" else "are $laterCount more"} after that." else ""
            return "Your next match for '$cleanKeyword' is ${describe(next, zone, withDay = true)}.$tail"
        }

        val windowPhrase = (range ?: CalendarRange.TODAY).phrase
        if (events.isEmpty()) {
            return "Nothing on your calendar $windowPhrase."
        }
        val withDay = range != CalendarRange.TODAY && range != CalendarRange.TOMORROW && range != null
        val header = "You have ${if (events.size == 1) "1 event" else "${events.size} events"} $windowPhrase."
        val listed = events.take(MAX_LISTED_EVENTS).joinToString(" ") { describe(it, zone, withDay) + "." }
        val more = events.size - MAX_LISTED_EVENTS
        val moreSentence = if (more > 0) " And $more more." else ""
        return "$header $listed$moreSentence"
    }

    /** Confirmation after a successful insert, with the calendar name when known. */
    fun formatCreated(
        title: String,
        beginMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        calendarName: String?,
        zone: ZoneId,
    ): String {
        val calendarPart = calendarName?.takeIf { it.isNotBlank() }?.let { " to $it" } ?: ""
        return if (allDay) {
            val day = Instant.ofEpochMilli(beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
            "Added '$title'$calendarPart for ${day.format(DAY_FORMAT)}, all day."
        } else {
            val start = Instant.ofEpochMilli(beginMillis).atZone(zone)
            val end = Instant.ofEpochMilli(endMillis).atZone(zone)
            val endPart = if (end.toLocalDate() == start.toLocalDate()) {
                "until ${end.format(TIME_FORMAT)}"
            } else {
                "until ${end.format(DAY_FORMAT)} ${end.format(TIME_FORMAT)}"
            }
            "Added '$title'$calendarPart on ${start.format(DAY_FORMAT)} at ${start.format(TIME_FORMAT)}, $endPart."
        }
    }

    private fun describe(event: CalendarEventRecord, zone: ZoneId, withDay: Boolean): String {
        val title = event.title.ifBlank { "Untitled event" }
        val calendarPart = event.calendarName.takeIf { it.isNotBlank() }?.let { ", on $it" } ?: ""
        val locationPart = event.location?.let { ", at $it" } ?: ""
        val whenPart = if (event.allDay) {
            val day = Instant.ofEpochMilli(event.beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
            if (withDay) "${day.format(DAY_FORMAT)}, all day" else "all day"
        } else {
            val start = Instant.ofEpochMilli(event.beginMillis).atZone(zone)
            val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
            val times = "${start.format(TIME_FORMAT)} to ${end.format(TIME_FORMAT)}"
            if (withDay) "${start.format(DAY_FORMAT)}, $times" else times
        }
        return "$whenPart: $title$calendarPart$locationPart".replaceFirstChar { it.uppercaseChar() }
    }

    private fun nextMonday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
}
