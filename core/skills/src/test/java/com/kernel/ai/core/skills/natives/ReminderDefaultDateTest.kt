package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderDefaultDateTest {

    private val day = LocalDate.of(2026, 11, 27)
    private val five = LocalTime.of(17, 0)

    private fun at(hour: Int, minute: Int, second: Int = 0) =
        day.atTime(hour, minute, second)

    @Test
    fun `before five pm defaults a five pm reminder to today`() {
        assertEquals(day, defaultReminderDate(five, at(16, 59, 59)))
    }

    @Test
    fun `exactly at five pm defaults to tomorrow because the time has passed`() {
        assertEquals(day.plusDays(1), defaultReminderDate(five, at(17, 0, 0)))
    }

    @Test
    fun `after five pm defaults to tomorrow`() {
        assertEquals(day.plusDays(1), defaultReminderDate(five, at(17, 0, 1)))
    }

    @Test
    fun `just before midnight a 12-30 am reminder defaults to tomorrow`() {
        assertEquals(day.plusDays(1), defaultReminderDate(LocalTime.of(0, 30), at(23, 59, 59)))
    }

    @Test
    fun `exactly midnight a midnight reminder defaults to tomorrow`() {
        assertEquals(day.plusDays(1), defaultReminderDate(LocalTime.MIDNIGHT, at(0, 0, 0)))
    }

    @Test
    fun `just after midnight a 12-30 am reminder defaults to today`() {
        assertEquals(day, defaultReminderDate(LocalTime.of(0, 30), at(0, 0, 1)))
    }

    @Test
    fun `late evening reminder later tonight defaults to today`() {
        assertEquals(day, defaultReminderDate(LocalTime.of(23, 45), at(23, 30)))
    }
}
