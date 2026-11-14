package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One calendar instance (recurring events are expanded) as returned by CalendarContract.Instances. */
data class CalendarEventRecord(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String,
    val location: String?,
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
 * Query shape follows [CalendarBirthdayLookup]: a permission check, then ContentResolver with explicit projections.
 */
@Singleton
class CalendarEventStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasReadPermission(): Boolean = granted(Manifest.permission.READ_CALENDAR)

    fun hasWritePermission(): Boolean = granted(Manifest.permission.WRITE_CALENDAR)

    /** Expanded event instances overlapping [beginMillis, endMillis), sorted by start. Empty without READ_CALENDAR. */
    fun queryInstances(beginMillis: Long, endMillis: Long): List<CalendarEventRecord> {
        if (!hasReadPermission()) return emptyList()
        val uri = Uri.withAppendedPath(CalendarContract.Instances.CONTENT_URI, "$beginMillis/$endMillis")
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val results = mutableListOf<CalendarEventRecord>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            while (cursor.moveToNext()) {
                results += CalendarEventRecord(
                    title = cursor.getString(titleIndex)?.trim().orEmpty(),
                    beginMillis = cursor.getLong(beginIndex),
                    endMillis = cursor.getLong(endIndex),
                    allDay = cursor.getInt(allDayIndex) != 0,
                    calendarName = cursor.getString(calendarIndex)?.trim().orEmpty(),
                    location = cursor.getString(locationIndex)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return results
    }

    /**
     * The calendar new events go into: a visible calendar the user can write to, primary first.
     * Null when there is none (or READ_CALENDAR is missing, which the query needs too).
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
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
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

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
