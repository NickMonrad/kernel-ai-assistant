package com.kernel.ai.core.skills.natives

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import com.kernel.ai.core.skills.ToolPresentation

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

/**
 * What a read may return. [fromDate, toDateExclusive) is the local-day range used for all-day events,
 * whose stored BEGIN is UTC midnight. [fromMillis, toMillis) is the instant range used for timed events.
 */
data class ReadWindow(
    val fromDate: LocalDate,
    val toDateExclusive: LocalDate,
    val fromMillis: Long,
    val toMillis: Long,
)

/** Pure calendar read/create logic: windows, keyword filtering and voice-friendly wording. No Android types. */
object CalendarEventReplies {

    /** Keyword searches without a range look this far ahead for the next match. */
    const val KEYWORD_LOOKAHEAD_DAYS = 365L

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    private const val MAX_LISTED_EVENTS = 8

    /** Spoken replies name at most this many events; the card lists the rest. */
    private const val MAX_SPOKEN_EVENTS = 8

    /** Spoken and displayed times never use dotted "a.m."; a full stop inside a time would split the sentence for TTS. */
    private val CLOCK_TIME = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH)
    private val MERIDIEM = DateTimeFormatter.ofPattern("a", Locale.ENGLISH)

    /**
     * Window for a read. "Upcoming" and keyword searches start at [now], so events that already
     * ended are not listed as the next match. Explicit day ranges start at local midnight.
     */
    fun readWindow(
        range: CalendarRange?,
        hasKeyword: Boolean,
        today: LocalDate,
        zone: ZoneId,
        now: Instant,
    ): ReadWindow {
        val startsNow = range == CalendarRange.UPCOMING || (range == null && hasKeyword)
        val (fromDate, toDate) = when {
            range == null && hasKeyword -> today to today.plusDays(KEYWORD_LOOKAHEAD_DAYS)
            else -> when (range) {
                CalendarRange.TODAY, null -> today to today.plusDays(1)
                CalendarRange.TOMORROW -> today.plusDays(1) to today.plusDays(2)
                CalendarRange.THIS_WEEK -> today to nextMonday(today)
                CalendarRange.NEXT_WEEK -> nextMonday(today) to nextMonday(today).plusWeeks(1)
                CalendarRange.UPCOMING -> today to today.plusDays(7)
            }
        }
        val fromMillis = if (startsNow) now.toEpochMilli() else fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
        return ReadWindow(
            fromDate = fromDate,
            toDateExclusive = toDate,
            fromMillis = fromMillis,
            toMillis = toDate.atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    /**
     * One row per calendar instance: the same event id at the same start is listed once. Repeats happen
     * when the same event is returned twice for one instance (for example a calendar synced into two
     * accounts under one event id). Input order is kept.
     */
    fun dedupeInstances(events: List<CalendarEventRecord>): List<CalendarEventRecord> =
        events.distinctBy { it.eventId to it.beginMillis }

    /** True when [event] falls inside [window]. Exact for both all-day rows (by day) and timed rows (by instant). */
    fun isInWindow(event: CalendarEventRecord, window: ReadWindow): Boolean =
        if (event.allDay) {
            // All-day rows store UTC midnights; [begin, end) overlaps the window's [fromDate, toDateExclusive).
            val beginDay = Instant.ofEpochMilli(event.beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val endDay = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate()
                .takeIf { it.isAfter(beginDay) } ?: beginDay.plusDays(1)
            beginDay.isBefore(window.toDateExclusive) && endDay.isAfter(window.fromDate)
        } else {
            event.beginMillis < window.toMillis && event.endMillis > window.fromMillis
        }

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

    /**
     * Speech for a read: one sentence per event, shaped "7:30 to 7:35 AM, <title>." so TTS speaks the
     * time and the title as one unit. Calendar names and locations stay on the card.
     */
    fun formatSpokenRead(
        range: CalendarRange?,
        keyword: String?,
        events: List<CalendarEventRecord>,
        zone: ZoneId,
    ): String {
        val cleanKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanKeyword != null) {
            val matches = events.filter { matchesKeyword(it, cleanKeyword) }
            val windowPhrase = range?.phrase ?: "the next year"
            if (matches.isEmpty()) return "Nothing matching $cleanKeyword on your calendar in $windowPhrase."
            val next = matches.first()
            val laterCount = matches.size - 1
            val tail = when (laterCount) {
                0 -> ""
                1 -> " There is 1 more after that."
                else -> " There are $laterCount more after that."
            }
            return "Your next match is ${spokenSentence(next, zone, withDay = true)}$tail"
        }
        val windowPhrase = (range ?: CalendarRange.TODAY).phrase
        if (events.isEmpty()) return "Nothing on your calendar $windowPhrase."
        val withDay = range != null && range != CalendarRange.TODAY && range != CalendarRange.TOMORROW
        val header = "You have ${if (events.size == 1) "1 event" else "${events.size} events"} $windowPhrase."
        val spoken = events.take(MAX_SPOKEN_EVENTS).joinToString(" ") { spokenSentence(it, zone, withDay) }
        val more = events.size - MAX_SPOKEN_EVENTS
        val moreSentence = if (more > 0) " And $more more on your screen." else ""
        return "$header $spoken$moreSentence"
    }

    /**
     * Card content for a read over a window. Multi-day windows group rows by day; single-day windows
     * have one unlabelled group. [events] must be de-duplicated, inside the window and sorted by start.
     */
    fun agendaPresentation(
        range: CalendarRange?,
        events: List<CalendarEventRecord>,
        zone: ZoneId,
    ): ToolPresentation.CalendarAgenda {
        val effective = range ?: CalendarRange.TODAY
        val title = effective.phrase.replaceFirstChar { it.uppercaseChar() }
        val grouped = effective == CalendarRange.THIS_WEEK ||
            effective == CalendarRange.NEXT_WEEK ||
            effective == CalendarRange.UPCOMING
        val rows = events.map { event -> event to dayOf(event, zone) }
        val days = if (grouped) {
            rows.groupBy { it.second }.toSortedMap().map { (day, dayRows) ->
                ToolPresentation.CalendarAgendaDay(
                    label = day.format(DAY_FORMAT),
                    rows = dayRows.map { (event, _) -> agendaRow(event, zone) },
                )
            }
        } else {
            listOf(ToolPresentation.CalendarAgendaDay(label = null, rows = rows.map { agendaRow(it.first, zone) }))
        }
        return ToolPresentation.CalendarAgenda(
            title = title,
            days = days.filter { it.rows.isNotEmpty() },
            emptyMessage = "Nothing on your calendar ${effective.phrase}.",
        )
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
        val calendarPart = spokenCalendarName(calendarName)?.let { " to $it" } ?: ""
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

    /** Local day an instance is listed under: all-day rows use their UTC-midnight day, timed rows the local day. */
    private fun dayOf(event: CalendarEventRecord, zone: ZoneId): LocalDate =
        if (event.allDay) {
            Instant.ofEpochMilli(event.beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(event.beginMillis).atZone(zone).toLocalDate()
        }

    private fun agendaRow(event: CalendarEventRecord, zone: ZoneId): ToolPresentation.CalendarAgendaRow {
        val timeText = if (event.allDay) {
            "All day"
        } else {
            val start = Instant.ofEpochMilli(event.beginMillis).atZone(zone)
            val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
            timeRange(start, end, separator = "\u2013")
        }
        val detail = listOfNotNull(
            spokenCalendarName(event.calendarName),
            event.location,
        ).joinToString(" \u00b7 ").takeIf { it.isNotEmpty() }
        return ToolPresentation.CalendarAgendaRow(
            timeText = timeText,
            title = event.title.ifBlank { "Untitled event" },
            detail = detail,
        )
    }

    /**
     * "7:30 to 7:35 AM" (meridiem once when both ends share it), or "11:30 AM to 12:15 PM".
     * [separator] is "to" for speech and an en dash for the card.
     */
    private fun timeRange(start: java.time.ZonedDateTime, end: java.time.ZonedDateTime, separator: String): String {
        val sameMeridiem = start.format(MERIDIEM) == end.format(MERIDIEM)
        val startText = if (sameMeridiem) start.format(CLOCK_TIME) else "${start.format(CLOCK_TIME)} ${start.format(MERIDIEM)}"
        return "$startText $separator ${end.format(CLOCK_TIME)} ${end.format(MERIDIEM)}"
    }

    /** Spoken sentence for one event: "<times>, <title>." or "all day, <title>.", with the day first for multi-day reads. */
    private fun spokenSentence(event: CalendarEventRecord, zone: ZoneId, withDay: Boolean): String {
        val title = event.title.ifBlank { "Untitled event" }
        val dayPart = if (withDay) "On ${dayOf(event, zone).format(DAY_FORMAT)}, " else ""
        val whenPart = if (event.allDay) {
            "all day"
        } else {
            val start = Instant.ofEpochMilli(event.beginMillis).atZone(zone)
            val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
            timeRange(start, end, separator = "to")
        }
        return "$dayPart$whenPart, $title."
    }

    private fun describe(event: CalendarEventRecord, zone: ZoneId, withDay: Boolean): String {
        val title = event.title.ifBlank { "Untitled event" }
        val calendarPart = spokenCalendarName(event.calendarName)?.let { ", on $it" } ?: ""
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

    /**
     * Calendar name for speech and display, or null when it is blank or looks like an email address.
     * Google accounts default the calendar name to the account email; that must not be read aloud.
     */
    fun spokenCalendarName(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() && !EMAIL_LIKE.containsMatchIn(it) }

    private val EMAIL_LIKE = Regex("""[^\s@]+@[^\s@]+""")

    private fun nextMonday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
}
