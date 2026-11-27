package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderBareHourTest {

    private val day = LocalDate.of(2026, 11, 27)

    private fun at(hour: Int, minute: Int = 0, second: Int = 0): LocalDateTime =
        day.atTime(hour, minute, second)

    private fun nextOccurrence(hour: Int, minute: Int, now: LocalDateTime): LocalTime =
        resolveBareHour(BareHour(hour, minute), evening = false, now = now)

    @Test
    fun `digits and words with no am or pm are bare hours`() {
        assertEquals(BareHour(7, 0), bareHourIn("7"))
        assertEquals(BareHour(7, 0), bareHourIn("at 7"))
        assertEquals(BareHour(7, 0), bareHourIn("7 o'clock"))
        assertEquals(BareHour(6, 30), bareHourIn("6:30"))
        assertEquals(BareHour(6, 0), bareHourIn("six"))
        assertEquals(BareHour(6, 0), bareHourIn("at six"))
    }

    @Test
    fun `am or pm marks a time as not bare`() {
        assertNull(bareHourIn("7pm"))
        assertNull(bareHourIn("5 p.m."))
        assertNull(bareHourIn("9:30 am"))
    }

    @Test
    fun `hour 12 hour 0 and 24-hour times keep their current meaning`() {
        assertNull(bareHourIn("12"))
        assertNull(bareHourIn("12:30"))
        assertNull(bareHourIn("0"))
        assertNull(bareHourIn("19"))
    }

    @Test
    fun `text without a time or a bare word hour is not a bare hour`() {
        assertNull(bareHourIn("tomorrow"))
        assertNull(bareHourIn(""))
        assertNull(bareHourIn("a"))
    }

    @Test
    fun `evening cue means pm for hours 1 to 11`() {
        assertEquals(LocalTime.of(19, 0), resolveBareHour(BareHour(7, 0), evening = true, now = at(14)))
        assertEquals(LocalTime.of(23, 0), resolveBareHour(BareHour(11, 0), evening = true, now = at(9)))
        assertEquals(LocalTime.of(13, 0), resolveBareHour(BareHour(1, 0), evening = true, now = at(0, 30)))
    }

    @Test
    fun `evening cue after the time has passed still gives the pm time for the caller to reject`() {
        assertEquals(LocalTime.of(23, 0), resolveBareHour(BareHour(11, 0), evening = true, now = at(23, 50)))
    }

    @Test
    fun `no cue picks the nearest future occurrence of the clock hour`() {
        assertEquals(LocalTime.of(18, 0), nextOccurrence(6, 0, at(14)))
        assertEquals(LocalTime.of(6, 0), nextOccurrence(6, 0, at(19)))
        assertEquals(LocalTime.of(7, 0), nextOccurrence(7, 0, at(6, 30)))
        assertEquals(LocalTime.of(19, 0), nextOccurrence(7, 0, at(7, 30)))
    }

    @Test
    fun `a clock time equal to now counts as passed`() {
        assertEquals(LocalTime.of(18, 0), nextOccurrence(6, 0, at(6, 0, 0)))
    }

    @Test
    fun `midnight edges resolve to the occurrence on the correct side of midnight`() {
        assertEquals(LocalTime.of(11, 0), nextOccurrence(11, 0, at(0, 30)))
        assertEquals(LocalTime.of(23, 0), nextOccurrence(11, 0, at(11, 30)))
        assertEquals(LocalTime.of(11, 0), nextOccurrence(11, 0, at(23, 30)))
        assertEquals(LocalTime.of(1, 0), nextOccurrence(1, 0, at(0, 30)))
        assertEquals(LocalTime.of(1, 0), nextOccurrence(1, 0, at(23, 30)))
        assertEquals(LocalTime.of(6, 0), nextOccurrence(6, 0, at(23, 59, 59)))
    }

    @Test
    fun `evening cue is detected for tonight and this evening only as whole words`() {
        assertTrue(hasEveningCue("remind me to put the bins out tonight at 7"))
        assertTrue(hasEveningCue("remind me this evening at 6"))
        assertFalse(hasEveningCue("remind me tonightly at 6"))
        assertFalse(hasEveningCue("remind me tomorrow at 7"))
    }

    @Test
    fun `half past, dot, quarter and N past times are bare hours with their minutes`() {
        assertEquals(BareHour(7, 30), bareHourIn("half past 7"))
        assertEquals(BareHour(7, 30), bareHourIn("7.30"))
        assertEquals(BareHour(7, 45), bareHourIn("quarter to 8"))
        assertEquals(BareHour(7, 15), bareHourIn("quarter past 7"))
        assertEquals(BareHour(7, 5), bareHourIn("5 past 7"))
    }

    @Test
    fun `a day part other than the evening cue keeps the hour as written`() {
        assertNull(bareHourIn("7 in the evening"))
        assertNull(bareHourIn("6 in the morning"))
        assertNull(bareHourIn("tonight at 6 in the morning"))
        assertEquals(BareHour(7, 0), bareHourIn("this evening at 7"))
    }
}
