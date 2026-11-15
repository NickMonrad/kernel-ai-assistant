package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CalendarEventStoreTest {

    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val store = CalendarEventStore(context)

    private val instanceColumns = listOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        CalendarContract.Instances.STATUS,
    )

    @BeforeEach
    fun setUp() {
        every { context.contentResolver } returns resolver
        grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private fun grant(vararg permissions: String) {
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_DENIED
        permissions.forEach { every { context.checkSelfPermission(it) } returns PackageManager.PERMISSION_GRANTED }
    }

    /** Row-backed cursor: values are looked up by column name, columns are resolved to indexes. */
    private fun cursorOf(columns: List<String>, rows: List<Map<String, Any?>>): Cursor {
        var position = -1
        return mockk<Cursor>(relaxed = true) {
            every { getColumnIndexOrThrow(any()) } answers { columns.indexOf(firstArg<String>()) }
            every { moveToNext() } answers {
                position++
                position < rows.size
            }
            every { getLong(any()) } answers { (rows[position][columns[firstArg()]] as Number).toLong() }
            every { getInt(any()) } answers { (rows[position][columns[firstArg()]] as Number).toInt() }
            every { getString(any()) } answers { rows[position][columns[firstArg()]] as String? }
        }
    }

    /** Routes the instances query and the calendars query to their own cursors by projection. */
    private fun stubQueries(
        instances: Cursor?,
        visibleCalendars: Cursor? = cursorOf(
            listOf(CalendarContract.Calendars._ID),
            listOf(mapOf(CalendarContract.Calendars._ID to 1L)),
        ),
        writableCalendars: Cursor? = null,
    ) {
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val projection = secondArg<Array<String>?>()
            when {
                projection?.contains(CalendarContract.Instances.TITLE) == true -> instances
                projection?.contains(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME) == true -> writableCalendars
                else -> visibleCalendars
            }
        }
    }

    private fun instanceRow(
        title: String,
        calendarId: Long = 1L,
        allDay: Int = 0,
        selfStatus: Int = 1,
        status: Int = 1,
    ) = mapOf(
        CalendarContract.Instances.TITLE to title,
        CalendarContract.Instances.BEGIN to 1_000L,
        CalendarContract.Instances.END to 2_000L,
        CalendarContract.Instances.ALL_DAY to allDay,
        CalendarContract.Instances.CALENDAR_ID to calendarId,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME to "Work",
        CalendarContract.Instances.EVENT_LOCATION to null,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS to selfStatus,
        CalendarContract.Instances.STATUS to status,
    )

    private val from: LocalDate = LocalDate.of(2025, 11, 20)

    @Nested
    @DisplayName("queryInstances")
    inner class Query {

        @Test
        @DisplayName("without READ_CALENDAR: empty and the provider is never queried")
        fun withoutReadReturnsEmpty() {
            grant()
            stubQueries(instances = cursorOf(instanceColumns, listOf(instanceRow("Dentist"))))

            assertTrue(store.queryInstances(from, from.plusDays(1)).isEmpty())
            verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("null cursor from the provider: empty result, no exception")
        fun nullCursorIsEmpty() {
            stubQueries(instances = null)
            assertTrue(store.queryInstances(from, from.plusDays(1)).isEmpty())
        }

        @Test
        @DisplayName("hidden calendars, declined and cancelled instances are dropped")
        fun dropsHiddenDeclinedAndCancelled() {
            stubQueries(
                instances = cursorOf(
                    instanceColumns,
                    listOf(
                        instanceRow("Kept", calendarId = 1L),
                        instanceRow("Hidden calendar", calendarId = 2L),
                        instanceRow("Declined", selfStatus = 2),
                        instanceRow("Cancelled", status = 2),
                    ),
                ),
            )

            val events = store.queryInstances(from, from.plusDays(1))

            assertEquals(listOf("Kept"), events.map { it.title })
        }

        @Test
        @DisplayName("all-day flag and blank location map onto the record")
        fun mapsAllDayAndLocation() {
            stubQueries(
                instances = cursorOf(
                    instanceColumns,
                    listOf(instanceRow("Holiday", allDay = 1)),
                ),
            )

            val event = store.queryInstances(from, from.plusDays(1)).single()

            assertTrue(event.allDay)
            assertNull(event.location)
            assertFalse(event.title.isBlank())
        }
    }

    @Nested
    @DisplayName("findDefaultWritableCalendar")
    inner class DefaultCalendar {

        @Test
        @DisplayName("without READ_CALENDAR: null, no query")
        fun withoutReadIsNull() {
            grant(Manifest.permission.WRITE_CALENDAR)
            assertNull(store.findDefaultWritableCalendar())
        }

        @Test
        @DisplayName("returns the first writable visible calendar the provider ranks")
        fun returnsFirstRanked() {
            stubQueries(
                instances = null,
                writableCalendars = cursorOf(
                    listOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                    listOf(mapOf(CalendarContract.Calendars._ID to 9L, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME to "Primary")),
                ),
            )
            assertEquals(WritableCalendar(9L, "Primary"), store.findDefaultWritableCalendar())
        }
    }

    @Nested
    @DisplayName("insertEvent")
    inner class Insert {

        @Test
        @DisplayName("without WRITE_CALENDAR: null, provider never asked to insert")
        fun withoutWriteReturnsNull() {
            grant(Manifest.permission.READ_CALENDAR)
            val event = NewCalendarEvent("Dentist", 1_000L, 2_000L, false, "UTC", null, null)

            assertNull(store.insertEvent(7L, event))
            verify(exactly = 0) { resolver.insert(any(), any()) }
        }

        @Test
        @DisplayName("provider returns null: null, no exception")
        fun providerRefusalIsNull() {
            every { resolver.insert(any(), any()) } returns null
            val event = NewCalendarEvent("Dentist", 1_000L, 2_000L, false, "UTC", null, null)

            assertNull(store.insertEvent(7L, event))
        }
    }
}
