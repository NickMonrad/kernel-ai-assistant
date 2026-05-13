package com.kernel.ai.core.skills.natives

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarBirthdayLookupTest {
    @Test
    fun `findBirthday normalizes birthday titles and caches query results`() {
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val cursor = birthdayCursor(
            title = "Jane Smith's Birthday",
            dtStart = LocalDate.of(2020, 4, 5).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = true,
            calendarDisplayName = "Birthdays",
            accountType = "com.google",
        )
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns contentResolver
        every {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        } returns cursor

        val lookup = CalendarBirthdayLookup(context)

        val first = lookup.findBirthday("Jane's birthday")
        val second = lookup.findBirthday("Jane's birthday")

        assertNotNull(first)
        assertEquals(4, first?.month)
        assertEquals(5, first?.day)
        assertEquals("Jane Smith", first?.label)
        assertEquals(first, second)
        verify(exactly = 1) {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `findBirthday matches a shorter synced birthday label from a mapped full contact name`() {
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val cursor = birthdayCursor(
            title = "Alice Birthday",
            dtStart = LocalDate.of(2020, 4, 5).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = true,
            calendarDisplayName = "Birthdays",
            accountType = "com.google",
        )
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns contentResolver
        every {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        } returns cursor

        val lookup = CalendarBirthdayLookup(context)

        val birthday = lookup.findBirthday("Alice Smith's birthday")

        assertNotNull(birthday)
        assertEquals("Alice", birthday?.label)
        assertEquals(4, birthday?.month)
        assertEquals(5, birthday?.day)
    }

    @Test
    fun `findBirthday matches a taught birthday phrased without an apostrophe`() {
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val cursor = birthdayCursor(
            title = "Lachlan Birthday",
            dtStart = LocalDate.of(2020, 8, 22).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = true,
            calendarDisplayName = "Birthdays",
            accountType = "com.google",
        )
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns contentResolver
        every {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        } returns cursor

        val lookup = CalendarBirthdayLookup(context)

        val birthday = lookup.findBirthday("Lachlans birthday")

        assertNotNull(birthday)
        assertEquals("Lachlan", birthday?.label)
        assertEquals(8, birthday?.month)
        assertEquals(22, birthday?.day)
    }

    @Test
    fun `findBirthday returns null without calendar permission`() {
        val context = mockk<Context>(relaxed = true)
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_DENIED

        val lookup = CalendarBirthdayLookup(context)

        assertNull(lookup.findBirthday("Jane's birthday"))
        verify(exactly = 0) { context.contentResolver }
    }

    // ── Phonetic / fuzzy matching ─────────────────────────────────────────────

    @Test
    fun `soundex produces same code for Emily and Emilie`() {
        assertEquals(CalendarBirthdayLookup.soundex("emily"), CalendarBirthdayLookup.soundex("emilie"))
    }

    @Test
    fun `editDistance is 1 for Freya vs Freyja`() {
        assertEquals(1, CalendarBirthdayLookup.editDistance("freya", "freyja"))
    }

    @Test
    fun `editDistance is 2 for Emily vs Emilie`() {
        assertEquals(2, CalendarBirthdayLookup.editDistance("emily", "emilie"))
    }

    @Test
    fun `labelsPotentiallyMatch returns true for Freya vs Freyja via edit distance`() {
        assertTrue(CalendarBirthdayLookup.labelsPotentiallyMatch("Freya", "Freyja"))
        assertTrue(CalendarBirthdayLookup.labelsPotentiallyMatch("Freyja", "Freya"))
    }

    @Test
    fun `labelsPotentiallyMatch returns true for Emily vs Emilie via Soundex`() {
        assertTrue(CalendarBirthdayLookup.labelsPotentiallyMatch("Emily", "Emilie"))
        assertTrue(CalendarBirthdayLookup.labelsPotentiallyMatch("Emilie", "Emily"))
    }

    @Test
    fun `labelsPotentiallyMatch returns false for very different names`() {
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Nick", "Nicholas"))
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Kate", "Katherine"))
    }

    @Test
    fun `labelsPotentiallyMatch returns false for same-initial 3-letter and 4-letter name variants`() {
        // Names ≤ 4 chars share Soundex codes too easily — guard must block all of them
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Tim", "Tom"))
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Dan", "Don"))
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Ben", "Ban"))
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("John", "Joan"))
        assertFalse(CalendarBirthdayLookup.labelsPotentiallyMatch("Carl", "Karl"))
    }

    @Test
    fun `findBirthday matches phonetically similar calendar name Freyja when query is Freya`() {
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val cursor = birthdayCursor(
            title = "Freyja's Birthday",
            dtStart = LocalDate.of(2020, 8, 22).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = true,
            calendarDisplayName = "Birthdays",
            accountType = "com.google",
        )
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns contentResolver
        every {
            contentResolver.query(CalendarContract.Events.CONTENT_URI, any(), any(), any(), any())
        } returns cursor

        val lookup = CalendarBirthdayLookup(context)

        val birthday = lookup.findBirthday("Freya's birthday")

        assertNotNull(birthday)
        assertEquals("Freyja", birthday?.label)
        assertEquals(8, birthday?.month)
        assertEquals(22, birthday?.day)
    }

    @Test
    fun `findBirthday matches phonetically similar calendar name Emilie when query is Emily`() {
        val context = mockk<Context>(relaxed = true)
        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val cursor = birthdayCursor(
            title = "Emilie's Birthday",
            dtStart = LocalDate.of(2020, 3, 10).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            isAllDay = true,
            calendarDisplayName = "Birthdays",
            accountType = "com.google",
        )
        every { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED
        every { context.contentResolver } returns contentResolver
        every {
            contentResolver.query(CalendarContract.Events.CONTENT_URI, any(), any(), any(), any())
        } returns cursor

        val lookup = CalendarBirthdayLookup(context)

        val birthday = lookup.findBirthday("Emily's birthday")

        assertNotNull(birthday)
        assertEquals("Emilie", birthday?.label)
        assertEquals(3, birthday?.month)
        assertEquals(10, birthday?.day)
    }

    private fun birthdayCursor(
        title: String,
        dtStart: Long,
        isAllDay: Boolean,
        calendarDisplayName: String,
        accountType: String,
    ): Cursor {
        val cursor = mockk<Cursor>()
        val columns = mapOf(
            CalendarContract.Events.TITLE to 0,
            CalendarContract.Events.DTSTART to 1,
            CalendarContract.Events.ALL_DAY to 2,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME to 3,
            CalendarContract.Events.ACCOUNT_TYPE to 4,
        )
        var moved = false

        every { cursor.getColumnIndexOrThrow(any()) } answers {
            columns[firstArg<String>()] ?: error("Unknown column ${firstArg<String>()}")
        }
        every { cursor.moveToNext() } answers {
            if (moved) {
                false
            } else {
                moved = true
                true
            }
        }
        every { cursor.getString(0) } returns title
        every { cursor.getLong(1) } returns dtStart
        every { cursor.getInt(2) } returns if (isAllDay) 1 else 0
        every { cursor.getString(3) } returns calendarDisplayName
        every { cursor.getString(4) } returns accountType
        every { cursor.close() } just runs
        return cursor
    }
}
