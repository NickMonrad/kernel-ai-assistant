package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.SkillResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1455 — weekend weather selection and parsing.
 *
 * Deterministic fixture data only; no live Open-Meteo calls. Proves the weekend
 * selection contract for representative calendar positions, that only the intended
 * weekend days are returned, that the location is preserved, and that fresh and
 * stale-cache parses of the same response select identical days.
 */
class GetWeatherWeekendTest {

    // ── Fixture dates (verified weekdays) ─────────────────────────────────────

    private val mondayStart = listOf(
        "2026-08-10", // Mon
        "2026-08-11", // Tue
        "2026-08-12", // Wed
        "2026-08-13", // Thu
        "2026-08-14", // Fri
        "2026-08-15", // Sat
        "2026-08-16", // Sun
    )

    private val fridayStart = listOf(
        "2026-08-14", // Fri
        "2026-08-15", // Sat
        "2026-08-16", // Sun
    )

    private val saturdayStart = listOf(
        "2026-08-08", // Sat
        "2026-08-09", // Sun
    )

    private val sundayStart = listOf(
        "2026-08-09", // Sun
        "2026-08-10", // Mon
    )

    // ── Weekend index selection ───────────────────────────────────────────────

    @Test
    fun `weekday forecast selects the next Saturday and Sunday`() {
        assertEquals(listOf(5, 6), selectWeekendDayIndices(mondayStart))
        assertEquals(listOf(1, 2), selectWeekendDayIndices(fridayStart))
    }

    @Test
    fun `Saturday start treats Saturday and Sunday as the current weekend`() {
        assertEquals(listOf(0, 1), selectWeekendDayIndices(saturdayStart))
    }

    @Test
    fun `Sunday start treats Sunday as the remaining current weekend day`() {
        assertEquals(listOf(0), selectWeekendDayIndices(sundayStart))
    }

    @Test
    fun `horizon ending before Sunday never fabricates a missing day`() {
        // Friday + Saturday only: Saturday is the next weekend day, Sunday is out of
        // horizon and must not be invented.
        assertEquals(listOf(1), selectWeekendDayIndices(listOf("2026-08-14", "2026-08-15")))
        // No weekend inside the horizon at all.
        assertEquals(emptyList<Int>(), selectWeekendDayIndices(listOf("2026-08-10", "2026-08-11")))
    }

    @Test
    fun `empty or unparseable dates select nothing`() {
        assertEquals(emptyList<Int>(), selectWeekendDayIndices(emptyList()))
        assertEquals(emptyList<Int>(), selectWeekendDayIndices(listOf("not-a-date", "2026-08-15")))
    }

    // ── Spoken summary ────────────────────────────────────────────────────────

    @Test
    fun `buildWeekendForecastSpoken names the weekend explicitly`() {
        val days = listOf(
            Triple("Sat 15 Aug", "Clear sky", Pair(28.0, 18.0)),
            Triple("Sun 16 Aug", "Rain", Pair(22.0, 16.0)),
        )
        val spoken = buildWeekendForecastSpoken("Bundaberg", days)
        assertTrue(spoken.startsWith("Bundaberg weekend forecast."))
        assertTrue(spoken.contains("Sat 15 Aug"))
        assertTrue(spoken.contains("Sun 16 Aug"))
        assertFalse(spoken.contains("2-day"))
    }

    @Test
    fun `buildWeekendForecastSpoken handles a single remaining day`() {
        val days = listOf(
            Triple("Sun 9 Aug", "Partly cloudy", Pair(24.0, 17.0)),
        )
        val spoken = buildWeekendForecastSpoken("Brisbane, AU", days)
        assertTrue(spoken.startsWith("Brisbane weekend forecast."))
        assertTrue(spoken.contains("Sun 9 Aug"))
        assertTrue(spoken.contains("high 24 degrees"))
    }

    // ── Response parsing (deterministic fixtures) ─────────────────────────────

    private fun fixtureJson(dates: List<String>, code: Int = 0): String {
        val highs = dates.joinToString(",") { "%.1f".format(24.0 + dates.indexOf(it)) }
        val lows = dates.joinToString(",") { "%.1f".format(16.0 + dates.indexOf(it)) }
        val rains = dates.joinToString(",") { "0.0" }
        val codes = dates.joinToString(",") { "$code" }
        val uvs = dates.joinToString(",") { "6.0" }
        val times = dates.joinToString(",") { "\"$it\"" }
        val sunrises = dates.joinToString(",") { "\"${it}T06:12\"" }
        val sunsets = dates.joinToString(",") { "\"${it}T17:48\"" }
        return """{
            "latitude": -24.87, "longitude": 152.35,
            "daily": {
                "time": [$times],
                "temperature_2m_max": [$highs],
                "temperature_2m_min": [$lows],
                "precipitation_sum": [$rains],
                "weather_code": [$codes],
                "uv_index_max": [$uvs],
                "sunrise": [$sunrises],
                "sunset": [$sunsets]
            }
        }"""
    }

    @Test
    fun `weekday fixture returns only the intended weekend days`() {
        val result = parseWeekendResponse(fixtureJson(mondayStart), "Bundaberg")
        val direct = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertTrue(direct.content.startsWith("Bundaberg weekend forecast:"), "text=${direct.content}")
        // Weekend days present.
        assertTrue(direct.content.contains("Sat 15 Aug"), "text=${direct.content}")
        assertTrue(direct.content.contains("Sun 16 Aug"), "text=${direct.content}")
        // Intervening weekdays must not appear.
        for (weekday in listOf("Mon 10 Aug", "Tue 11 Aug", "Wed 12 Aug", "Thu 13 Aug", "Fri 14 Aug")) {
            assertFalse(direct.content.contains(weekday), "intervening '$weekday' leaked into text=${direct.content}")
        }
        val presentation = direct.presentation as com.kernel.ai.core.skills.ToolPresentation.Weather
        assertEquals(2, presentation.forecast?.size)
        assertEquals("Bundaberg", presentation.locationName)
        assertTrue(direct.spokenSummary?.contains("Bundaberg weekend forecast.") == true)
    }

    @Test
    fun `Saturday-start fixture returns Saturday and Sunday`() {
        val result = parseWeekendResponse(fixtureJson(saturdayStart), "Bundaberg")
        val direct = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertTrue(direct.content.contains("Sat 8 Aug"), "text=${direct.content}")
        assertTrue(direct.content.contains("Sun 9 Aug"), "text=${direct.content}")
    }

    @Test
    fun `Sunday-start fixture returns only the remaining Sunday`() {
        val result = parseWeekendResponse(fixtureJson(sundayStart), "Bundaberg")
        val direct = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertTrue(direct.content.contains("Sun 9 Aug"), "text=${direct.content}")
        assertFalse(direct.content.contains("Mon 10 Aug"), "text=${direct.content}")
    }

    @Test
    fun `parse of the same response is identical for fresh and cached reads`() {
        // The raw Open-Meteo body is what gets cached; both the fresh fetch path and
        // the stale-cache fallback run parseWeekendResponse on that same JSON, so the
        // selection must be byte-identical across parses.
        val json = fixtureJson(mondayStart)
        val first = parseWeekendResponse(json, "Bundaberg")
        val second = parseWeekendResponse(json, "Bundaberg")
        assertEquals(
            (first as SkillResult.DirectReply).content,
            (second as SkillResult.DirectReply).content,
        )
        assertEquals(first.spokenSummary, second.spokenSummary)
    }

    @Test
    fun `parse fails honestly when no weekend exists in the horizon`() {
        val result = parseWeekendResponse(fixtureJson(listOf("2026-08-10", "2026-08-11")), "Bundaberg")
        assertInstanceOf(SkillResult.Failure::class.java, result)
    }

    @Test
    fun `parse fails on empty or mismatched daily data`() {
        val empty = """{"daily":{"time":[],"temperature_2m_max":[],"temperature_2m_min":[],"precipitation_sum":[],"weather_code":[]}}"""
        assertInstanceOf(SkillResult.Failure::class.java, parseWeekendResponse(empty, "Bundaberg"))

        val mismatched = """{"daily":{"time":["2026-08-15","2026-08-16"],"temperature_2m_max":[28.0],"temperature_2m_min":[18.0],"precipitation_sum":[0.0],"weather_code":[0]}}"""
        assertInstanceOf(SkillResult.Failure::class.java, parseWeekendResponse(mismatched, "Bundaberg"))
    }
}
