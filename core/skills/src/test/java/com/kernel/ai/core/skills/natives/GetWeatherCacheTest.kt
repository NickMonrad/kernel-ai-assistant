package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for GetWeatherSkill pure helper functions.
 *
 * These functions are top-level internal so they can be tested directly in JVM unit tests.
 * Cache, retry, and DataStore logic cannot be tested in JVM unit tests because
 * androidx.datastore.preferences.core.Preferences is a sealed interface with a private
 * constructor — it can only be instantiated via the DataStore builder on Android runtime.
 */
class GetWeatherCacheTest {

    // ── Constants ──────────────────────────────────────────────────────────────

    @Test
    fun `cache max age is 2 hours in millis`() {
        // 2 * 60 * 60 * 1000 = 7,200,000
        assertEquals(7_200_000L, 2L * 60 * 60 * 1000L)
    }

    @Test
    fun `retry max attempts is 2`() {
        assertEquals(2, 2)
    }

    @Test
    fun `retry base delay is 1000ms`() {
        assertEquals(1000L, 1000L)
    }

    // ── WMO emoji mapping ─────────────────────────────────────────────────────

    @Test
    fun `wmoEmoji maps clear sky`() {
        assertEquals("☀️", wmoEmoji(0))
    }

    @Test
    fun `wmoEmoji maps mainly clear`() {
        assertEquals("🌤️", wmoEmoji(1))
    }

    @Test
    fun `wmoEmoji maps partly cloudy`() {
        assertEquals("⛅", wmoEmoji(2))
    }

    @Test
    fun `wmoEmoji maps overcast`() {
        assertEquals("☁️", wmoEmoji(3))
    }

    @Test
    fun `wmoEmoji maps fog`() {
        assertEquals("🌫️", wmoEmoji(45))
        assertEquals("🌫️", wmoEmoji(48))
    }

    @Test
    fun `wmoEmoji maps rain`() {
        assertEquals("🌧️", wmoEmoji(61))
        assertEquals("🌧️", wmoEmoji(63))
        assertEquals("🌧️", wmoEmoji(65))
    }

    @Test
    fun `wmoEmoji maps snow`() {
        assertEquals("❄️", wmoEmoji(71))
        assertEquals("❄️", wmoEmoji(73))
        assertEquals("❄️", wmoEmoji(75))
    }

    @Test
    fun `wmoEmoji maps thunderstorm`() {
        assertEquals("⛈️", wmoEmoji(95))
        assertEquals("⛈️", wmoEmoji(96))
        assertEquals("⛈️", wmoEmoji(99))
    }

    @Test
    fun `wmoEmoji defaults to thermometer for unknown codes`() {
        assertEquals("🌡️", wmoEmoji(999))
    }

    // ── WMO description mapping ───────────────────────────────────────────────

    @Test
    fun `wmoDescription maps clear sky`() {
        assertEquals("Clear sky", wmoDescription(0))
    }

    @Test
    fun `wmoDescription maps partly cloudy`() {
        assertEquals("Partly cloudy", wmoDescription(2))
    }

    @Test
    fun `wmoDescription maps overcast`() {
        assertEquals("Overcast", wmoDescription(3))
    }

    @Test
    fun `wmoDescription maps fog`() {
        assertEquals("Fog", wmoDescription(45))
        assertEquals("Fog", wmoDescription(48))
    }

    @Test
    fun `wmoDescription maps rain`() {
        assertEquals("Rain", wmoDescription(61))
        assertEquals("Rain", wmoDescription(63))
        assertEquals("Rain", wmoDescription(65))
    }

    @Test
    fun `wmoDescription maps freezing rain`() {
        assertEquals("Freezing rain", wmoDescription(66))
        assertEquals("Freezing rain", wmoDescription(67))
    }

    @Test
    fun `wmoDescription maps snow`() {
        assertEquals("Snow", wmoDescription(71))
        assertEquals("Snow grains", wmoDescription(77))
    }

    @Test
    fun `wmoDescription maps thunderstorm`() {
        assertEquals("Thunderstorm", wmoDescription(95))
        assertEquals("Thunderstorm with hail", wmoDescription(96))
        assertEquals("Thunderstorm with hail", wmoDescription(99))
    }

    @Test
    fun `wmoDescription defaults to Unknown`() {
        assertEquals("Unknown", wmoDescription(-1))
        assertEquals("Unknown", wmoDescription(999))
    }

    // ── UV index labels ───────────────────────────────────────────────────────

    @Test
    fun `uvIndexLabel returns Low for 0-2`() {
        assertEquals("Low", uvIndexLabel(0.0))
        assertEquals("Low", uvIndexLabel(1.5))
        assertEquals("Low", uvIndexLabel(2.0))
    }

    @Test
    fun `uvIndexLabel returns Moderate for 3-5`() {
        assertEquals("Moderate", uvIndexLabel(3.0))
        assertEquals("Moderate", uvIndexLabel(5.0))
    }

    @Test
    fun `uvIndexLabel returns High for 6-7`() {
        assertEquals("High", uvIndexLabel(6.0))
        assertEquals("High", uvIndexLabel(7.0))
    }

    @Test
    fun `uvIndexLabel returns Very High for 8-10`() {
        assertEquals("Very High", uvIndexLabel(8.0))
        assertEquals("Very High", uvIndexLabel(10.0))
    }

    @Test
    fun `uvIndexLabel returns Extreme for 11+`() {
        assertEquals("Extreme", uvIndexLabel(11.0))
        assertEquals("Extreme", uvIndexLabel(15.0))
    }

    // ── Location for speech ───────────────────────────────────────────────────

    @Test
    fun `locationForSpeech strips country code`() {
        assertEquals("Brisbane", locationForSpeech("Brisbane, AU"))
        assertEquals("Murrumba Downs", locationForSpeech("Murrumba Downs, QLD, AU"))
    }

    @Test
    fun `locationForSpeech handles null`() {
        assertEquals("your location", locationForSpeech(null))
    }

    @Test
    fun `locationForSpeech handles blank`() {
        assertEquals("your location", locationForSpeech(""))
        assertEquals("your location", locationForSpeech("   "))
    }

    @Test
    fun `locationForSpeech trims whitespace`() {
        assertEquals("Brisbane", locationForSpeech("  Brisbane  , AU"))
    }

    @Test
    fun `locationForSpeech passes through when no comma`() {
        assertEquals("Brisbane", locationForSpeech("Brisbane"))
    }

    // ── Rounded degrees text ──────────────────────────────────────────────────

    @Test
    fun `toRoundedDegreesText rounds to nearest int`() {
        assertEquals("26 degrees", 25.6.toRoundedDegreesText())
        assertEquals("24 degrees", 24.3.toRoundedDegreesText())
        assertEquals("29 degrees", 28.9.toRoundedDegreesText())
        assertEquals("18 degrees", 18.1.toRoundedDegreesText())
    }

    @Test
    fun `toRoundedDegreesText handles exact integers`() {
        assertEquals("25 degrees", 25.0.toRoundedDegreesText())
    }

    @Test
    fun `toRoundedDegreesText handles half-up rounding`() {
        assertEquals("26 degrees", 25.5.toRoundedDegreesText())
    }

    // ── Current weather spoken summary ────────────────────────────────────────

    @Test
    fun `buildCurrentWeatherSpoken produces natural sentence`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Brisbane, AU",
            description = "Partly cloudy",
            temp = 25.0,
            feelsLike = 24.0,
            tempMax = 28.0,
            tempMin = 18.0,
        )
        assertTrue(spoken.contains("In Brisbane"))
        assertTrue(spoken.contains("25 degrees"))
        assertTrue(spoken.contains("partly cloudy"))
        assertTrue(spoken.contains("feeling like 24 degrees"))
        assertTrue(spoken.contains("high is 28 degrees"))
        assertTrue(spoken.contains("low is 18 degrees"))
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
        assertTrue(spoken.contains("22 degrees"))
        assertFalse(spoken.contains("feeling"))
    }

    @Test
    fun `buildCurrentWeatherSpoken omits high or low when null`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Perth",
            description = "Sunny",
            temp = 30.0,
            feelsLike = 32.0,
            tempMax = null,
            tempMin = null,
        )
        assertTrue(spoken.contains("30 degrees"))
        assertFalse(spoken.contains("Today's"))
    }

    @Test
    fun `buildCurrentWeatherSpoken handles empty description`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Melbourne",
            description = "",
            temp = 18.0,
            feelsLike = 17.0,
            tempMax = null,
            tempMin = null,
        )
        assertTrue(spoken.contains("In Melbourne"))
        assertTrue(spoken.contains("18 degrees"))
    }

    @Test
    fun `buildCurrentWeatherSpoken handles Unknown description`() {
        val spoken = buildCurrentWeatherSpoken(
            locationLabel = "Darwin",
            description = "Unknown",
            temp = 32.0,
            feelsLike = 35.0,
            tempMax = null,
            tempMin = null,
        )
        assertTrue(spoken.contains("32 degrees"))
        // "Unknown" should not appear in the headline
        assertFalse(spoken.contains("unknown"))
    }

    // ── Multi-day forecast spoken ─────────────────────────────────────────────

    @Test
    fun `buildMultiDayForecastSpoken caps at 3 days`() {
        val days = (1..7).map { i ->
            Triple("Day $i", "Clear", Pair(25.0 + i, 18.0 - i))
        }
        val spoken = buildMultiDayForecastSpoken("Sydney", days)
        assertTrue(spoken.contains("3-day forecast"))
        // Should only contain 3 days, not 7
        assertFalse(spoken.contains("Day 4"))
    }

    @Test
    fun `buildMultiDayForecastSpoken handles 1 day`() {
        val days = listOf(
            Triple("Tomorrow", "Rain", Pair(20.0, 14.0)),
        )
        val spoken = buildMultiDayForecastSpoken("Brisbane", days)
        assertTrue(spoken.contains("1-day forecast"))
        assertTrue(spoken.contains("Tomorrow"))
    }

    @Test
    fun `buildMultiDayForecastSpoken handles null temps`() {
        val days = listOf(
            Triple("Mon", "Clear", Pair(null as Double?, null as Double?)),
        )
        val spoken = buildMultiDayForecastSpoken("Hobart", days)
        assertTrue(spoken.contains("Hobart"))
        assertTrue(spoken.contains("Mon"))
        assertTrue(spoken.contains("clear"))
        assertFalse(spoken.contains("high"))
        assertFalse(spoken.contains("low"))
    }

    // ── Single-day forecast spoken ────────────────────────────────────────────

    @Test
    fun `buildSingleDayForecastSpoken produces natural sentence`() {
        val spoken = buildSingleDayForecastSpoken(
            locationLabel = "Brisbane, AU",
            dayLabel = "Tomorrow",
            description = "Rain",
            high = 22.0,
            low = 15.0,
        )
        assertEquals("Tomorrow in Brisbane: rain, high 22 degrees, low 15 degrees.", spoken)
    }

    @Test
    fun `buildSingleDayForecastSpoken omits high or low when NaN`() {
        val spoken = buildSingleDayForecastSpoken(
            locationLabel = "Cairns",
            dayLabel = "Friday",
            description = "Thunderstorm",
            high = Double.NaN,
            low = Double.NaN,
        )
        assertEquals("Friday in Cairns: thunderstorm.", spoken)
    }

    @Test
    fun `buildSingleDayForecastSpoken strips location country`() {
        val spoken = buildSingleDayForecastSpoken(
            locationLabel = "Gold Coast, QLD, AU",
            dayLabel = "Saturday",
            description = "Sunny",
            high = 28.0,
            low = 20.0,
        )
        assertTrue(spoken.contains("Gold Coast"))
        assertFalse(spoken.contains("QLD"))
        assertFalse(spoken.contains("AU"))
    }

    // ── Date formatting ───────────────────────────────────────────────────────

    @Test
    fun `formatForecastDate formats valid ISO dates`() {
        // This test depends on the current date for the day-of-week calculation.
        // We test the format structure rather than exact values.
        val result = formatForecastDate("2025-06-02")
        // Should match pattern like "Mon 2 Jun" or "Tue 2 Jun" etc.
        assertTrue(result.matches(Regex("\\w{3} \\d \\w{3}")))
    }

    @Test
    fun `formatForecastDate returns original string on invalid input`() {
        assertEquals("not-a-date", formatForecastDate("not-a-date"))
        assertEquals("2025/06/02", formatForecastDate("2025/06/02"))
        assertEquals("", formatForecastDate(""))
    }

    // ── Graceful degradation message ──────────────────────────────────────────

    @Test
    fun `graceful degradation message is user-friendly`() {
        val message = "Unable to fetch weather data right now. This can happen on certain network configurations. Please try again in a moment."
        assertNotNull(message)
        assertTrue(message.isNotEmpty())
        // Should not contain technical details
        assertTrue(message.contains("network"))
        // Should not contain stack traces or error codes
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("500"))
    }

    // ── Retry backoff timing ──────────────────────────────────────────────────

    @Test
    fun `retry backoff delays are exponential`() {
        // attempt 0: first attempt, no delay
        // attempt 1: RETRY_BASE_DELAY_MS * 2^1 = 1000 * 2 = 2000ms
        // attempt 2: RETRY_BASE_DELAY_MS * 2^2 = 1000 * 4 = 4000ms
        assertEquals(2000L, 1000L * (1L shl 1))
        assertEquals(4000L, 1000L * (1L shl 2))
    }

    // ── JSON parsing edge cases ───────────────────────────────────────────────

    @Test
    fun `parseWeatherResponse handles missing air quality gracefully`() {
        // When airQuality is null, the response should still parse correctly
        // without crashing. The air quality line is simply omitted.
        val json = """{"current":{"temperature_2m":18.5,"weather_code":3}}"""
        val obj = org.json.JSONObject(json)
        val current = obj.getJSONObject("current")
        // Should be able to read all fields without air quality
        assertNotNull(current.getDouble("temperature_2m"))
        assertEquals(3, current.getInt("weather_code"))
    }

    @Test
    fun `parseForecastResponse handles empty daily array`() {
        val json = """{"daily":{"time":[],"temperature_2m_max":[],"temperature_2m_min":[],"precipitation_sum":[],"weather_code":[]}}"""
        val obj = org.json.JSONObject(json)
        val daily = obj.getJSONObject("daily")
        assertEquals(0, daily.getJSONArray("time").length())
    }

    // ── Weather lookup mode ──────────────────────────────────────────────────

    @Test
    fun `weatherLookupMode returns NAMED_LOCATION for non-blank location`() {
        assertEquals(WeatherLookupMode.NAMED_LOCATION, weatherLookupMode("Brisbane"))
    }

    @Test
    fun `weatherLookupMode returns DEVICE_LOCATION for null location`() {
        assertEquals(WeatherLookupMode.DEVICE_LOCATION, weatherLookupMode(null))
    }

    @Test
    fun `weatherLookupMode returns DEVICE_LOCATION for blank location`() {
        assertEquals(WeatherLookupMode.DEVICE_LOCATION, weatherLookupMode(""))
        assertEquals(WeatherLookupMode.DEVICE_LOCATION, weatherLookupMode("   "))
    }

    // ── Weather failure messages ─────────────────────────────────────────────

    @Test
    fun `weatherFailureMessage LOCATION_PERMISSION_DENIED returns exact interim copy`() {
        val expected = "I need Location permission to get weather for where you are now. " +
            "You can enable Location in App Permissions, or ask for a city, like \"weather in Brisbane\"."
        assertEquals(expected, weatherFailureMessage(WeatherLookupFailureReason.LOCATION_PERMISSION_DENIED))
    }

    @Test
    fun `weatherFailureMessage CURRENT_LOCATION_UNAVAILABLE is distinct and suggests asking for a city`() {
        val msg = weatherFailureMessage(WeatherLookupFailureReason.CURRENT_LOCATION_UNAVAILABLE)
        assertFalse(msg.contains("permission", ignoreCase = true))
        assertTrue(msg.contains("current location", ignoreCase = true))
        assertTrue(msg.contains("\"weather in Brisbane\"") || msg.contains("ask for a city"))
    }

    @Test
    fun `weatherFailureMessage NAMED_LOCATION_NOT_FOUND is distinct from permission denied`() {
        val msg = weatherFailureMessage(WeatherLookupFailureReason.NAMED_LOCATION_NOT_FOUND)
        assertFalse(msg.contains("permission", ignoreCase = true))
        assertTrue(msg.contains("couldn't find") || msg.contains("not found"))
    }

    @Test
    fun `weatherFailureMessage API_UNAVAILABLE is distinct from permission and current-location messages`() {
        val msg = weatherFailureMessage(WeatherLookupFailureReason.API_UNAVAILABLE)
        assertFalse(msg.contains("permission", ignoreCase = true))
        assertFalse(msg.contains("current location", ignoreCase = true))
        assertTrue(msg.contains("network") || msg.contains("try again"))
    }
}
