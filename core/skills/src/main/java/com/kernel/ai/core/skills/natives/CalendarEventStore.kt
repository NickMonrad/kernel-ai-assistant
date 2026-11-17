package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One calendar instance (recurring events are expanded) as returned by CalendarContract.Instances. */
data class CalendarEventRecord(
    /** CalendarContract event id; with [beginMillis] it identifies one instance for de-duplication. */
    val eventId: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String,
    val location: String?,
    /**
     * iCalendar UID (`Events.UID_2445`), or null. Copies of one meeting in two calendars (organiser and
     * attendee, or two accounts) have different event ids but share this UID.
     */
    val uid: String? = null,
    /** Calendar names of other copies folded into this row by [CalendarEventReplies.dedupeInstances] (#1974). */
    val mergedCalendarNames: List<String> = emptyList(),
)

/** An event to insert. For all-day events, [beginMillis]/[endMillis] are UTC midnights per CalendarContract. */
data class NewCalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZoneId: String,
    val description: String?,
    val location: String?,
)

data class WritableCalendar(val id: Long, val displayName: String)

/**
 * Thin CalendarContract access shared by the calendar read and create paths.
 *
 * Reads use the by-day instances URI with Julian days, which CalendarContract matches against each
 * instance's local day. This is what keeps all-day events on the right day (their stored BEGIN is UTC
 * midnight). Hidden calendars and declined or cancelled instances are dropped here.
 */
@Singleton
class CalendarEventStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasReadPermission(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    fun hasWritePermission(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    /**
     * Instances whose local day falls in [fromDate, toDateExclusive), sorted by start.
     * Empty without READ_CALENDAR. Callers still apply the exact time-window check.
     */
    fun queryInstances(fromDate: LocalDate, toDateExclusive: LocalDate): List<CalendarEventRecord> {
        if (!hasReadPermission() || !toDateExclusive.isAfter(fromDate)) return emptyList()
        val visibleCalendarIds = visibleCalendarIds()
        val testCalendarOnly = debugTestCalendarName()
        if (testCalendarOnly != null) {
            Log.w(TAG, "Debug test-calendar filter is on: calendar reads show one calendar only (#1942)")
        }
        val uri = Uri.withAppendedPath(
            CalendarContract.Instances.CONTENT_BY_DAY_URI,
            "${julianDay(fromDate)}/${julianDay(toDateExclusive.minusDays(1))}",
        )
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
            CalendarContract.Events.UID_2445,
        )
        val results = mutableListOf<CalendarEventRecord>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val calendarIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val selfStatusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
            val statusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
            // Not every provider exposes the iCalendar UID on instances; without it dedupe falls back to the event id.
            val uidIndex = cursor.getColumnIndex(CalendarContract.Events.UID_2445)
            while (cursor.moveToNext()) {
                if (cursor.getLong(calendarIdIndex) !in visibleCalendarIds) continue
                if (testCalendarOnly != null && cursor.getString(calendarIndex)?.trim() != testCalendarOnly) continue
                if (cursor.getInt(statusIndex) == CalendarContract.Events.STATUS_CANCELED) continue
                if (cursor.getInt(selfStatusIndex) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                results += CalendarEventRecord(
                    eventId = cursor.getLong(eventIdIndex),
                    title = cursor.getString(titleIndex)?.trim().orEmpty(),
                    beginMillis = cursor.getLong(beginIndex),
                    endMillis = cursor.getLong(endIndex),
                    allDay = cursor.getInt(allDayIndex) != 0,
                    calendarName = cursor.getString(calendarIndex)?.trim().orEmpty(),
                    location = cursor.getString(locationIndex)?.trim()?.takeIf { it.isNotEmpty() },
                    uid = if (uidIndex >= 0) cursor.getString(uidIndex)?.trim()?.takeIf { it.isNotEmpty() } else null,
                )
            }
        }
        return results
    }

    /**
     * Debug builds only (#1942): the name of the one calendar reads are limited to, so device evidence can use
     * made-up events on a phone that also has real ones. Set and cleared by the debug-only
     * `CalendarTestFilterReceiver`; always null in release builds, which are not debuggable.
     */
    private fun debugTestCalendarName(): String? {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        return context.getSharedPreferences(DEBUG_TEST_CALENDAR_PREFS, Context.MODE_PRIVATE)
            .getString(DEBUG_TEST_CALENDAR_NAME, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * The calendar new events go into: a visible calendar the user can write to, lowest id first so
     * the choice is stable. The provider has no is_primary column on all devices, so it is not sorted on.
     * Null when there is none, or when READ_CALENDAR is missing (the calendars table is not readable without it).
     */
    fun findDefaultWritableCalendar(): WritableCalendar? {
        if (!hasReadPermission()) return null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            "${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            if (cursor.moveToNext()) {
                return WritableCalendar(
                    id = cursor.getLong(idIndex),
                    displayName = cursor.getString(nameIndex)?.trim().orEmpty(),
                )
            }
        }
        return null
    }

    /** Inserts the event into [calendarId]. Returns the new event id, or null if the provider refused. */
    fun insertEvent(calendarId: Long, event: NewCalendarEvent): Long? {
        if (!hasWritePermission()) return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.beginMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.timeZoneId)
            event.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return ContentUris.parseId(uri)
    }

    /** Ids of calendars the user has switched on (VISIBLE = 1). */
    private fun visibleCalendarIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            while (cursor.moveToNext()) ids += cursor.getLong(idIndex)
        }
        return ids
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Days since 1970-01-01 plus this offset gives the Julian day number CalendarContract expects. */
        private const val EPOCH_JULIAN_DAY = 2440588L
        private const val TAG = "KernelAI"

        /** Debug-only test-calendar filter (#1942); written by the debug `CalendarTestFilterReceiver`. */
        const val DEBUG_TEST_CALENDAR_PREFS = "debug_test_calendar_filter"
        const val DEBUG_TEST_CALENDAR_NAME = "calendar_name"

        fun julianDay(date: LocalDate): Long = date.toEpochDay() + EPOCH_JULIAN_DAY
    }
}
