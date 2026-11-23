package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** #1921 — pure cache policy: day-relative keys, pruning, and stale time labels. */
class GetWeatherCachePolicyTest {
    private val auckland = ZoneId.of("Pacific/Auckland")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, auckland).toInstant().toEpochMilli()

    @Test
    fun `tomorrow key changes across local midnight so yesterday's forecast is not reused`() {
        val before = weatherCacheKey(
            WeatherLookupMode.NAMED_LOCATION, "Wellington", false, "tomorrow", 0,
            millis(2027, 1, 15, 23, 59), auckland,
        )
        val after = weatherCacheKey(
            WeatherLookupMode.NAMED_LOCATION, "Wellington", false, "tomorrow", 0,
            millis(2027, 1, 16, 0, 1), auckland,
        )

        assertNotEquals(before, after)
    }

    @Test
    fun `tomorrow keys differ by day count so a one-day answer is not reused for a range`() {
        val one = weatherCacheKey(
            WeatherLookupMode.NAMED_LOCATION, "Wellington", false, "tomorrow", 0,
            millis(2027, 1, 15, 9, 0), auckland,
        )
        val three = weatherCacheKey(
            WeatherLookupMode.NAMED_LOCATION, "Wellington", false, "tomorrow", 3,
            millis(2027, 1, 15, 9, 0), auckland,
        )

        assertNotEquals(one, three)
    }

    @Test
    fun `weekend key changes across local midnight`() {
        val before = weatherCacheKey(WeatherLookupMode.DEVICE_LOCATION, null, true, null, 0, millis(2027, 1, 15, 23, 59), auckland)
        val after = weatherCacheKey(WeatherLookupMode.DEVICE_LOCATION, null, true, null, 0, millis(2027, 1, 16, 0, 1), auckland)

        assertNotEquals(before, after)
    }

    @Test
    fun `current and multi-day keys are not date-keyed, so stale replay filters past days instead`() {
        val first = weatherCacheKey(WeatherLookupMode.NAMED_LOCATION, "Wellington", false, null, 0, millis(2027, 1, 15, 23, 59), auckland)
        val second = weatherCacheKey(WeatherLookupMode.NAMED_LOCATION, "Wellington", false, null, 0, millis(2027, 1, 16, 0, 1), auckland)
        assertEquals(first, second)
        assertEquals("loc:Wellington:current", first)
        val multiDayFirst = weatherCacheKey(WeatherLookupMode.NAMED_LOCATION, "Wellington", false, null, 3, millis(2027, 1, 15, 23, 59), auckland)
        val multiDaySecond = weatherCacheKey(WeatherLookupMode.NAMED_LOCATION, "Wellington", false, null, 3, millis(2027, 1, 16, 0, 1), auckland)
        assertEquals(multiDayFirst, multiDaySecond)
        assertEquals("loc:Wellington:forecast:3", multiDayFirst)
    }

    @Test
    fun `stale time shows only the clock for a cache from today`() {
        val cachedAt = millis(2027, 1, 15, 9, 40)
        val now = millis(2027, 1, 15, 12, 0)

        assertEquals("09:40", staleForecastTimeText(cachedAt, now, is24Hour = true, zone = auckland))
    }

    @Test
    fun `stale time adds the day for a cache from an earlier day`() {
        val cachedAt = millis(2027, 1, 15, 9, 40)
        val now = millis(2027, 1, 16, 9, 0)

        assertEquals("Fri 15 Jan 09:40", staleForecastTimeText(cachedAt, now, is24Hour = true, zone = auckland))
    }

    @Test
    fun `stale time follows the 12-hour device setting`() {
        val cachedAt = millis(2027, 1, 15, 21, 5)

        assertEquals("9:05 PM", staleForecastTimeText(cachedAt, cachedAt, is24Hour = false, zone = auckland))
    }

    @Test
    fun `entries past the stale window or undecodable are prunable, fresh ones are kept`() {
        val now = millis(2027, 1, 15, 12, 0)
        val fresh = encodeWeatherCacheEntry(WeatherCacheEntry(now - 1_000, "Wellington", "{}"))
        val expired = encodeWeatherCacheEntry(WeatherCacheEntry(now - 12 * 60 * 60 * 1000L - 1, "Wellington", "{}"))

        assertFalse(isWeatherEntryPrunable(fresh, now))
        assertTrue(isWeatherEntryPrunable(expired, now))
        assertTrue(isWeatherEntryPrunable("not json", now))
        assertTrue(isWeatherEntryPrunable(42L, now))
    }

    @Test
    fun `stale marker returns null for a non-forecast result`() {
        assertNull(markStaleWeather(SkillResult.Failure("get_weather_gps", "nope"), "09:40"))
        assertNull(markStaleWeather(null, "09:40"))
    }

    @Test
    fun `stale marker sets the note on a weather presentation`() {
        val live = SkillResult.DirectReply(
            content = "Wellington: 13C",
            presentation = ToolPresentation.Weather(
                locationName = "Wellington",
                temperatureText = "13°C",
                feelsLikeText = null,
                description = "Cloudy",
                emoji = "☁",
                humidityText = null,
                windText = null,
                precipText = null,
                airQualityText = null,
            ),
        )

        val stale = markStaleWeather(live, "9:40 AM") as SkillResult.DirectReply

        assertEquals(
            "Forecast from 9:40 AM. The weather service isn't answering, so it may be out of date.",
            (stale.presentation as ToolPresentation.Weather).staleNoteText,
        )
    }
}
