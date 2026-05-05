package com.kernel.ai.core.skills.natives

import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetWeatherSpokenSummaryTest {
    private val skill = GetWeatherSkill(
        context = mockk(relaxed = true),
        httpClient = mockk<OkHttpClient>(),
    )

    @Test
    fun `locationForSpeech strips country code from reverse-geocoded label`() {
        assertEquals("Brisbane", skill.locationForSpeech("Brisbane, AU"))
    }

    @Test
    fun `locationForSpeech returns bare city name unchanged`() {
        assertEquals("Auckland", skill.locationForSpeech("Auckland"))
    }

    @Test
    fun `locationForSpeech returns placeholder for null`() {
        assertEquals("your location", skill.locationForSpeech(null))
    }

    @Test
    fun `locationForSpeech returns placeholder for blank`() {
        assertEquals("your location", skill.locationForSpeech("   "))
    }

    @Test
    fun `locationForSpeech handles multi-part label by taking only first segment`() {
        assertEquals("Murrumba Downs", skill.locationForSpeech("Murrumba Downs, QLD, AU"))
    }

    @Test
    fun `buildCurrentWeatherSpoken produces natural current-conditions sentence`() {
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
        val spoken = skill.buildCurrentWeatherSpoken(
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
            Triple("Mon 2 Jun", "Partly cloudy", Pair(25.0, 18.0)),
            Triple("Tue 3 Jun", "Rain", Pair(22.0, 15.0)),
        )
        val spoken = skill.buildMultiDayForecastSpoken("Brisbane, AU", days)
        assertEquals(
            "Brisbane 2-day forecast. Mon 2 Jun: partly cloudy, high 25 degrees, low 18 degrees. Tue 3 Jun: rain, high 22 degrees, low 15 degrees.",
            spoken,
        )
    }

    @Test
    fun `buildMultiDayForecastSpoken caps output at 3 days`() {
        val days = (1..7).map { i ->
            Triple("Day $i", "Clear sky", Pair(25.0, 18.0))
        }
        val spoken = skill.buildMultiDayForecastSpoken("Sydney", days)
        assertTrue(spoken.contains("3-day forecast"))
        assertFalse(spoken.contains("Day 4"))
    }

    @Test
    fun `buildMultiDayForecastSpoken handles null high or low gracefully`() {
        val days = listOf(
            Triple("Mon", "Clear sky", Pair(null as Double?, null as Double?)),
        )
        val spoken = skill.buildMultiDayForecastSpoken("Hobart", days)
        assertFalse(spoken.contains("high"))
        assertFalse(spoken.contains("low"))
        assertTrue(spoken.contains("clear sky"))
    }

    @Test
    fun `buildSingleDayForecastSpoken produces natural tomorrow sentence`() {
        val spoken = skill.buildSingleDayForecastSpoken(
            locationLabel = "Brisbane, AU",
            dayLabel = "Tomorrow",
            description = "Rain",
            high = 22.0,
            low = 15.0,
        )
        assertEquals(
            "Tomorrow in Brisbane: rain, high 22 degrees, low 15 degrees.",
            spoken,
        )
    }
}
