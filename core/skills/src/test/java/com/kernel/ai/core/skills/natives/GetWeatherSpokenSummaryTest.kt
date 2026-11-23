package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for GetWeatherSkill spoken-summary top-level functions.
 *
 * These functions are top-level internal so they can be tested directly in JVM unit tests.
 */
class GetWeatherSpokenSummaryTest {

    @Test
    fun `locationForSpeech strips country code from reverse-geocoded label`() {
        assertEquals("Brisbane", locationForSpeech("Brisbane, AU"))
    }

    @Test
    fun `locationForSpeech returns bare city name unchanged`() {
        assertEquals("Auckland", locationForSpeech("Auckland"))
    }

    @Test
    fun `locationForSpeech returns placeholder for null`() {
        assertEquals("your location", locationForSpeech(null))
    }

    @Test
    fun `locationForSpeech returns placeholder for blank`() {
        assertEquals("your location", locationForSpeech("   "))
    }

    @Test
    fun `locationForSpeech handles multi-part label by taking only first segment`() {
        assertEquals("Murrumba Downs", locationForSpeech("Murrumba Downs, QLD, AU"))
    }

    @Test
    fun `buildCurrentWeatherSpoken produces natural current-conditions sentence`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Brisbane, AU",
            description = "Partly cloudy",
            temp = 25.0,
            feelsLike = 24.0,
            tempMax = 28.0,
            tempMin = 18.0,
        )
        assertEquals(
            "In Brisbane, it's 25 degrees, partly cloudy, feeling like 24 degrees. Today's high is 28 degrees, low is 18 degrees.",
            spoken,
        )
    }

    @Test
    fun `buildCurrentWeatherSpoken omits feels-like when NaN`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Sydney",
            description = "Clear sky",
            temp = 22.0,
            feelsLike = Double.NaN,
            tempMax = 24.0,
            tempMin = 16.0,
        )
        assertFalse(spoken.contains("feeling"))
        assertTrue(spoken.contains("22 degrees"))
    }

    @Test
    fun `buildCurrentWeatherSpoken omits high-low when both null`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Melbourne",
            description = "Rain",
            temp = 18.0,
            feelsLike = 16.0,
            tempMax = null,
            tempMin = null,
        )
        assertFalse(spoken.contains("high"))
        assertFalse(spoken.contains("low"))
    }

    @Test
    fun `buildCurrentWeatherSpoken omits high when only min available`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Canberra",
            description = "Fog",
            temp = 10.0,
            feelsLike = 9.0,
            tempMax = null,
            tempMin = 6.0,
        )
        assertFalse(spoken.contains("high"))
        assertTrue(spoken.contains("low is 6 degrees"))
    }

    @Test
    fun `buildCurrentWeatherSpoken skips unknown description`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "GPS location",
            description = "Unknown",
            temp = 20.0,
            feelsLike = 19.0,
            tempMax = 22.0,
            tempMin = 14.0,
        )
        assertFalse(spoken.contains("unknown"))
    }

    @Test
    fun `buildCurrentWeatherSpoken rounds temperature to nearest integer`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Perth",
            description = "Clear sky",
            temp = 25.6,
            feelsLike = 24.3,
            tempMax = 28.9,
            tempMin = 18.1,
        )
        assertTrue(spoken.contains("26 degrees"))
        assertTrue(spoken.contains("24 degrees"))
        assertTrue(spoken.contains("29 degrees"))
        assertTrue(spoken.contains("18 degrees"))
    }

    @Test
    fun `buildCurrentWeatherSpoken does not contain degree symbol`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Darwin",
            description = "Overcast",
            temp = 31.0,
            feelsLike = 35.0,
            tempMax = 33.0,
            tempMin = 25.0,
        )
        assertFalse(spoken.contains("°"))
    }

    @Test
    fun `buildMultiDayForecastSpoken produces natural forecast sentence`() {
        val days = listOf(
            Triple("Monday", "Partly cloudy", Pair(25.0, 18.0)),
            Triple("Tuesday", "Rain", Pair(22.0, 15.0)),
        )
        val spoken = buildMultiDayForecastSpoken("Brisbane, AU", days)
        assertEquals(
            "Brisbane forecast for the next 2 days. Monday, partly cloudy, high 25 degrees, low 18 degrees. Tuesday, rain, high 22 degrees, low 15 degrees.",
            spoken,
        )
    }

    @Test
    fun `buildMultiDayForecastSpoken speaks every day given, not a cut-down three`() {
        val days = (23..29).map { day ->
            Triple(formatForecastWeekdayForSpeech("2026-11-$day"), "Clear sky", Pair(25.0, 18.0))
        }
        val spoken = buildMultiDayForecastSpoken("Sydney", days)
        assertTrue(spoken.startsWith("Sydney forecast for the next 7 days."))
        assertTrue(spoken.contains("Sunday, clear sky"))
        assertFalse(spoken.contains("-day"))
        assertFalse(spoken.contains(":"))
    }

    @Test
    fun `buildMultiDayForecastSpoken speaks 3 and 5 days in full`() {
        val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")
        for (count in listOf(3, 5)) {
            val days = weekdays.take(count).map { Triple(it, "Clear sky", Pair(25.0, 18.0)) }
            val spoken = buildMultiDayForecastSpoken("Sydney", days)
            assertTrue(spoken.startsWith("Sydney forecast for the next $count days."), spoken)
            assertEquals(count, Regex("clear sky").findAll(spoken).count(), spoken)
            assertTrue(spoken.contains("${weekdays[count - 1]}, clear sky"), spoken)
        }
    }

    @Test
    fun `buildMultiDayForecastSpoken with one day has no count heading`() {
        val spoken = buildMultiDayForecastSpoken("Sydney", listOf(Triple("Monday", "Clear sky", Pair(25.0, 18.0))))
        assertEquals("Sydney forecast. Monday, clear sky, high 25 degrees, low 18 degrees.", spoken)
    }

    @Test
    fun `buildMultiDayForecastSpoken handles null high or low gracefully`() {
        val days = listOf(
            Triple("Mon", "Clear sky", Pair(null as Double?, null as Double?)),
        )
        val spoken = buildMultiDayForecastSpoken("Hobart", days)
        assertFalse(spoken.contains("high"))
        assertFalse(spoken.contains("low"))
        assertTrue(spoken.contains("clear sky"))
    }

    @Test
    fun `buildSingleDayForecastSpoken produces natural tomorrow sentence`() {
        val spoken = buildSingleDayForecastSpoken(
            locationLabel = "Brisbane, AU",
            dayLabel = "Tomorrow",
            description = "Rain",
            high = 22.0,
            low = 15.0,
        )
        assertEquals(
            "Tomorrow in Brisbane, rain, high 22 degrees, low 15 degrees.",
            spoken,
        )
    }

    @Test
    fun `formatForecastWeekdayForSpeech gives the full weekday name`() {
        assertEquals("Monday", formatForecastWeekdayForSpeech("2026-11-23"))
        assertEquals("Saturday", formatForecastWeekdayForSpeech("2026-11-21"))
        assertEquals("Tuesday", formatForecastWeekdayForSpeech("2026-12-01"))
    }

    @Test
    fun `formatForecastWeekdayForSpeech returns unparseable input unchanged`() {
        assertEquals("Mon 23 Nov", formatForecastWeekdayForSpeech("Mon 23 Nov"))
    }
}
