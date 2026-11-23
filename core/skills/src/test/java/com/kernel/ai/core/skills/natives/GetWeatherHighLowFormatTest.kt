package com.kernel.ai.core.skills.natives

import android.content.Context
import android.util.Log
import com.kernel.ai.core.skills.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #2033 — the model-facing forecast text labels high and low explicitly. A bare
 * "30°C / 25°C" was read back as "high 25°C" by Gemma E-2B.
 */
class GetWeatherHighLowFormatTest {

    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /** 2026-11-22 12:00 UTC: the place's date is 22 Nov at utc_offset 0 (dailyJson default). */
    private val NOON_22_NOV_UTC: Long = java.time.Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()

    private fun dailyJson(
        dates: List<String>,
        highs: List<Double>,
        lows: List<Double>,
        utcOffsetSeconds: Int = 0,
    ): String = """
        {"latitude":-13.83,"longitude":-171.76,"utc_offset_seconds":$utcOffsetSeconds,
         "daily":{
            "time":[${dates.joinToString(",") { "\"$it\"" }}],
            "temperature_2m_max":[${highs.joinToString(",")}],
            "temperature_2m_min":[${lows.joinToString(",")}],
            "precipitation_sum":[${dates.joinToString(",") { "0.0" }}],
            "weather_code":[${dates.joinToString(",") { "0" }}],
            "uv_index_max":[${dates.joinToString(",") { "6.0" }}],
            "sunrise":[${dates.joinToString(",") { "\"${it}T06:00\"" }}],
            "sunset":[${dates.joinToString(",") { "\"${it}T18:00\"" }}]
         }}
    """.trimIndent()

    @Test
    fun `forecastHighLowText labels high before low`() {
        assertEquals(", high 30°C, low 25°C", forecastHighLowText(30.0, 25.0))
    }

    @Test
    fun `forecastHighLowText rounds to whole degrees`() {
        assertEquals(", high 31°C, low 24°C", forecastHighLowText(30.6, 24.4))
    }

    @Test
    fun `forecastHighLowText marks a missing value instead of dropping its label`() {
        assertEquals(", high ?°C, low 25°C", forecastHighLowText(Double.NaN, 25.0))
        assertEquals(", high 30°C, low ?°C", forecastHighLowText(30.0, Double.NaN))
    }

    @Test
    fun `forecastHighLowText is empty when both temperatures are missing`() {
        assertEquals("", forecastHighLowText(Double.NaN, Double.NaN))
    }

    @Test
    fun `multi-day forecast text labels each day's high and low`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(30.0, 28.0),
            lows = listOf(25.0, 24.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Apia"),
        )
        assertTrue(direct.content.contains(", high 30°C, low 25°C"), "text=${direct.content}")
        assertTrue(direct.content.contains(", high 28°C, low 24°C"), "text=${direct.content}")
        assertFalse(direct.content.contains("30°C / 25°C"), "text=${direct.content}")
    }

    @Test
    fun `single-day forecast text labels high and low`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(30.0, 28.0),
            lows = listOf(25.0, 24.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastDayResponse(json, "Apia", dayIndex = 1),
        )
        // Tomorrow's row (index 1) must carry its own labelled values, not today's.
        assertTrue(direct.content.contains(", high 28°C, low 24°C"), "text=${direct.content}")
        assertFalse(direct.content.contains("30°C"), "text=${direct.content}")
    }

    @Test
    fun `weekend forecast text labels high and low`() {
        // 2026-11-21 is a Saturday, so the weekend is Saturday plus Sunday.
        val json = dailyJson(
            dates = listOf("2026-11-21", "2026-11-22"),
            highs = listOf(30.0, 29.0),
            lows = listOf(25.0, 23.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            parseWeekendResponse(json, "Apia"),
        )
        assertTrue(direct.content.contains(", high 30°C, low 25°C"), "text=${direct.content}")
        assertTrue(direct.content.contains(", high 29°C, low 23°C"), "text=${direct.content}")
    }

    /** #2056 — the Wellington repro: today 21/13, tomorrow 18/15. The tool does the arithmetic. */
    @Test
    fun `two-day forecast adds a computed comparison of tomorrow against today`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(21.0, 18.0),
            lows = listOf(13.0, 15.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Wellington", compare = true, nowMillis = NOON_22_NOV_UTC),
        )
        assertTrue(
            direct.content.contains(
                "Comparison, tomorrow vs today: high 18°C vs 21°C, 3°C cooler; low 15°C vs 13°C, 2°C warmer.",
            ),
            "text=${direct.content}",
        )
        assertTrue(
            direct.spokenSummary.orEmpty().endsWith(
                "Tomorrow will be 3 degrees cooler in Wellington, with a high of 18 degrees, down from 21.",
            ),
            "spoken=${direct.spokenSummary}",
        )
    }

    /** #2033/#2056 — compare=true speaks only the computed sentence; the model never restates these numbers. */
    @Test
    fun `comparison request speaks a ready-to-speak sentence only`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(31.0, 30.0),
            lows = listOf(25.0, 24.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Apia", compare = true, nowMillis = NOON_22_NOV_UTC),
        )
        assertEquals(
            "Tomorrow will be slightly cooler in Apia, with a high of 30 degrees, down from 31.",
            direct.spokenSummary,
        )
    }

    @Test
    fun `equal temperatures are reported as the same`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(20.0, 20.0),
            lows = listOf(10.0, 10.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Apia", compare = true, nowMillis = NOON_22_NOV_UTC),
        )
        assertTrue(direct.content.contains("high 20°C vs 20°C, same; low 10°C vs 10°C, same."), "text=${direct.content}")
        assertTrue(
            direct.spokenSummary.orEmpty().endsWith("Tomorrow will be about the same as today in Apia, with a high of 20 degrees."),
            "spoken=${direct.spokenSummary}",
        )
    }

    @Test
    fun `comparison is only added for exactly two days`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23", "2026-11-24"),
            highs = listOf(21.0, 18.0, 19.0),
            lows = listOf(13.0, 15.0, 14.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Wellington", compare = true, nowMillis = NOON_22_NOV_UTC),
        )
        assertFalse(direct.content.contains("Comparison,"), "text=${direct.content}")
        assertFalse(direct.spokenSummary.orEmpty().contains("Tomorrow will be"), "spoken=${direct.spokenSummary}")
    }

    /** #2033 — without compare=true a two-day forecast is a plain readback: no computed comparison. */
    @Test
    fun `two-day forecast without compare has no comparison`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(21.0, 18.0),
            lows = listOf(13.0, 15.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Wellington"),
        )
        assertFalse(direct.content.contains("Comparison,"), "text=${direct.content}")
        assertFalse(direct.spokenSummary.orEmpty().contains("Tomorrow will be"), "spoken=${direct.spokenSummary}")
    }

    /** #2033 — a cached reply from before midnight: its first row is yesterday, so no "today vs tomorrow" is spoken. */
    @Test
    fun `comparison is dropped when the first forecast row is already past`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-21", "2026-11-22"),
            highs = listOf(21.0, 18.0),
            lows = listOf(13.0, 15.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "Wellington", compare = true, nowMillis = NOON_22_NOV_UTC),
        )
        assertFalse(direct.content.contains("Comparison,"), "text=${direct.content}")
        assertFalse(direct.spokenSummary.orEmpty().contains("Tomorrow will be"), "spoken=${direct.spokenSummary}")
    }

    /** #2066 — "tomorrow" plus N days keeps rows 1..N of a body that starts today. */
    @Test
    fun `parseForecastRange returns the requested days from tomorrow onward`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23", "2026-11-24", "2026-11-25"),
            highs = listOf(21.0, 18.0, 19.0, 20.0),
            lows = listOf(13.0, 15.0, 14.0, 12.0),
        )
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastRange(json, "Wellington", fromIndex = 1, count = 2),
        )
        assertTrue(direct.content.contains("23 Nov"), "text=${direct.content}")
        assertTrue(direct.content.contains("24 Nov"), "text=${direct.content}")
        assertFalse(direct.content.contains("22 Nov"), "text=${direct.content}")
        assertFalse(direct.content.contains("25 Nov"), "text=${direct.content}")
    }

    /** The place's date decides "today": London asked from NZ on the morning of 23 Nov still has 22 Nov as its today. */
    @Test
    fun `comparison uses the place's date when the phone is ahead of it`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(9.0, 12.0),
            lows = listOf(4.0, 6.0),
            utcOffsetSeconds = 0,
        )
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            // 09:00 on 23 Nov in Auckland (NZDT, UTC+13) = 20:00 UTC on 22 Nov
            val nowMillis = java.time.Instant.parse("2026-11-22T20:00:00Z").toEpochMilli()
            val direct = assertInstanceOf(
                SkillResult.DirectReply::class.java,
                skill.parseForecastResponse(json, "London", compare = true, nowMillis = nowMillis),
            )
            assertTrue(direct.content.contains("Comparison, tomorrow vs today"), "text=${direct.content}")
            assertTrue(
                direct.spokenSummary.orEmpty().startsWith("Tomorrow will be"),
                "spoken=${direct.spokenSummary}",
            )
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    /** A cached reply from before the place's midnight: its first row is yesterday at the place, so it is dropped. */
    @Test
    fun `cached reply from before the place's midnight drops the comparison`() {
        val skill = GetWeatherSkill(context, OkHttpClient())
        val json = dailyJson(
            dates = listOf("2026-11-22", "2026-11-23"),
            highs = listOf(9.0, 12.0),
            lows = listOf(4.0, 6.0),
            utcOffsetSeconds = 0,
        )
        // 00:30 on 23 Nov in London (UTC+0)
        val nowMillis = java.time.Instant.parse("2026-11-23T00:30:00Z").toEpochMilli()
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            skill.parseForecastResponse(json, "London", compare = true, nowMillis = nowMillis),
        )
        assertFalse(direct.content.contains("Comparison,"), "text=${direct.content}")
        assertFalse(direct.spokenSummary.orEmpty().contains("Tomorrow will be"), "spoken=${direct.spokenSummary}")
    }
}
