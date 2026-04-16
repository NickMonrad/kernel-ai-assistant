package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 4 helper unit tests for [QuickIntentRouter] companion-object utilities:
 * - [QuickIntentRouter.resolveTime]: parses human time strings → "HH:mm"
 * - [QuickIntentRouter.resolveDate]: parses relative date strings → "YYYY-MM-DD"
 * - [QuickIntentRouter.parseTimerDuration]: parses duration strings → seconds
 *
 * Run with: ./gradlew :core:skills:testDebugUnitTest --tests "*.QuickIntentRouterHelperTest"
 */
class QuickIntentRouterHelperTest {

    // ── resolveTime ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveTime")
    inner class ResolveTimeTests {

        @Test
        fun `5pm resolves to 17-00`() {
            assertEquals("17:00", QuickIntentRouter.resolveTime("5pm"))
        }

        @Test
        fun `9-30am resolves to 09-30`() {
            assertEquals("09:30", QuickIntentRouter.resolveTime("9:30am"))
        }

        @Test
        fun `14-00 resolves to 14-00`() {
            assertEquals("14:00", QuickIntentRouter.resolveTime("14:00"))
        }

        @Test
        fun `9 o'clock resolves to 09-00`() {
            assertEquals("09:00", QuickIntentRouter.resolveTime("9 o'clock"))
        }

        @Test
        fun `midnight 12am resolves to 00-00`() {
            assertEquals("00:00", QuickIntentRouter.resolveTime("12am"))
        }

        @Test
        fun `noon 12pm resolves to 12-00`() {
            assertEquals("12:00", QuickIntentRouter.resolveTime("12pm"))
        }
    }

    // ── resolveDate ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDate")
    inner class ResolveDateTests {

        private val isoPattern = Regex("""\d{4}-\d{2}-\d{2}""")

        @Test
        fun `today resolves to today's date in YYYY-MM-DD`() {
            val result = QuickIntentRouter.resolveDate("today")
            assertNotNull(result)
            assertTrue(isoPattern.matches(result!!), "Expected YYYY-MM-DD but got: $result")
            assertEquals(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE), result)
        }

        @Test
        fun `tomorrow resolves to next calendar date in YYYY-MM-DD`() {
            val result = QuickIntentRouter.resolveDate("tomorrow")
            assertNotNull(result)
            assertTrue(isoPattern.matches(result!!), "Expected YYYY-MM-DD but got: $result")
            assertEquals(
                LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
                result,
            )
        }

        @Test
        fun `next monday resolves to a Monday in YYYY-MM-DD`() {
            val result = QuickIntentRouter.resolveDate("next monday")
            assertNotNull(result)
            assertTrue(isoPattern.matches(result!!), "Expected YYYY-MM-DD but got: $result")
            val date = LocalDate.parse(result)
            assertEquals(DayOfWeek.MONDAY, date.dayOfWeek, "Expected a Monday but got: $date")
        }

        @Test
        fun `unknown string returns null`() {
            assertNull(QuickIntentRouter.resolveDate("the day after never"))
        }
    }

    // ── parseTimerDuration (String overload) ──────────────────────────────────

    @Nested
    @DisplayName("parseTimerDuration(String)")
    inner class ParseTimerDurationTests {

        @Test
        fun `5 minutes returns 300 seconds`() {
            assertEquals(300, QuickIntentRouter.parseTimerDuration("5 minutes"))
        }

        @Test
        fun `1 hour 30 minutes returns 5400 seconds`() {
            assertEquals(5400, QuickIntentRouter.parseTimerDuration("1 hour 30 minutes"))
        }

        @Test
        fun `90 seconds returns 90`() {
            assertEquals(90, QuickIntentRouter.parseTimerDuration("90 seconds"))
        }

        @Test
        fun `2 hours returns 7200 seconds`() {
            assertEquals(7200, QuickIntentRouter.parseTimerDuration("2 hours"))
        }

        @Test
        fun `unparseable string returns null`() {
            assertNull(QuickIntentRouter.parseTimerDuration("boil an egg"))
        }
    }
}
