package com.kernel.ai.core.skills.natives

import android.content.Context
import android.util.Log
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
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

class GetWeatherUnresolvedLocationTest {
    private val context = mockk<Context>(relaxed = true)

    // Open-Meteo echoes the request coordinates, so a raw-coordinate label would be
    // the first thing to leak into the card or the spoken reply.
    private val weatherJson = """
        {"latitude":-27.24,"longitude":153.01,
         "current":{"temperature_2m":27.1,"apparent_temperature":28.0,"relative_humidity_2m":60,
                    "weather_code":1,"wind_speed_10m":3.2,"precipitation":0.0,"precipitation_probability":10},
         "daily":{"temperature_2m_max":[30.0],"temperature_2m_min":[20.0],"uv_index_max":[7.0],
                  "sunrise":["2026-11-02T05:30"],"sunset":["2026-11-02T18:10"]}}
    """.trimIndent()

    private val rawCoordinate = Regex("""-?\d+\.\d{2,}""")

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `unresolved place name never shows or speaks coordinates`() {
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            createSkill().parseWeatherResponse(weatherJson, displayName = null, airQuality = null),
        )
        val spoken = direct.spokenSummary.orEmpty()

        assertTrue(spoken.startsWith("In your location,"), "spoken=$spoken")
        assertFalse(rawCoordinate.containsMatchIn(spoken), "spoken=$spoken")
        assertFalse(rawCoordinate.containsMatchIn(direct.content), "content=${direct.content}")
        assertEquals("your location", weatherPresentation(direct).locationName)
    }

    @Test
    fun `resolved place name is spoken and shown unchanged`() {
        val direct = assertInstanceOf(
            SkillResult.DirectReply::class.java,
            createSkill().parseWeatherResponse(weatherJson, displayName = "Brisbane, AU", airQuality = null),
        )

        assertTrue(direct.spokenSummary.orEmpty().startsWith("In Brisbane,"), "spoken=${direct.spokenSummary}")
        assertEquals("Brisbane, AU", weatherPresentation(direct).locationName)
    }

    private fun weatherPresentation(direct: SkillResult.DirectReply): ToolPresentation.Weather =
        assertInstanceOf(ToolPresentation.Weather::class.java, direct.presentation)

    private fun createSkill(): GetWeatherSkill = GetWeatherSkill(context, OkHttpClient())
}
