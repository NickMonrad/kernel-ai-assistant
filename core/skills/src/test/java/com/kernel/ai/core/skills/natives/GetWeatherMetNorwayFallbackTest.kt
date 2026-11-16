package com.kernel.ai.core.skills.natives

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.ToolPresentation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.TimeZone

/**
 * #1922 — when Open-Meteo fails, weather is answered from MET Norway with attribution; MET's rules
 * (User-Agent, 4-decimal coordinates, Expires, Last-Modified/304, 429 back-off) are honoured. Both
 * providers are faked through an OkHttp interceptor; MET bodies are recorded fixtures.
 */
class GetWeatherMetNorwayFallbackTest {
    @TempDir
    lateinit var dir: File

    private val context = mockk<Context>(relaxed = true)
    private val metRequests = CopyOnWriteArrayList<Request>()
    private var now = Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()

    /** When true, Open-Meteo reads time out, as with the Auckland outage in the issue. */
    private var openMeteoFails = true
    private var openMeteoStatus: Int? = null
    private var metStatus = 200
    private var metBody = MET_FIXTURE
    private var metExpires: String? = "Mon, 16 Nov 2026 01:00:00 GMT"
    private var metLastModified: String? = "Mon, 16 Nov 2026 00:00:00 GMT"
    private var metRetryAfter: String? = null
    private lateinit var skill: GetWeatherSkill

    private val noCacheMessage =
        "The weather service isn't answering right now. Try again in a few minutes."

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { context.applicationContext } returns context
        weatherClockMillis = { now }
        skill = GetWeatherSkill(context, client()).apply {
            weatherStore = PreferenceDataStoreFactory.create { File(dir, "weather.preferences_pb") }
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        weatherClockMillis = System::currentTimeMillis
    }

    @Test
    fun `open-meteo failure is answered from MET Norway with attribution`() {
        val result = weather(ask("Wellington"))

        assertEquals("17°C", result.temperatureText)
        assertEquals(METNO_ATTRIBUTION, result.attributionText)
        assertNull(result.airQualityText)
        assertEquals(1, metRequests.size)
    }

    @Test
    fun `MET request carries the app User-Agent and 4-decimal truncated coordinates`() {
        ask("Wellington")

        val request = metRequests.single()
        assertEquals(METNO_USER_AGENT, request.header("User-Agent"))
        assertTrue(request.header("User-Agent")!!.contains("github.com/NickMonrad/kernel-ai-assistant"))
        assertEquals("-41.2900", request.url.queryParameter("lat"))
        assertEquals("174.7800", request.url.queryParameter("lon"))
    }

    @Test
    fun `both providers failing gives the clear message and no attribution`() {
        metStatus = 503

        assertEquals(noCacheMessage, direct(ask("Wellington")).content)
    }

    @Test
    fun `open-meteo success never calls MET and carries no attribution`() {
        openMeteoFails = false

        val result = weather(ask("Wellington"))

        assertNull(result.attributionText)
        assertTrue(metRequests.isEmpty())
    }

    @Test
    fun `non-metric MET units fail the fallback instead of mislabelling values`() {
        metBody = MET_FIXTURE.replace("\"celsius\"", "\"fahrenheit\"")

        assertEquals(noCacheMessage, direct(ask("Wellington")).content)
    }

    @Test
    fun `fresh MET body within Expires is reused without another MET call`() {
        ask("Wellington")
        now += 30 * MINUTE_MS

        val result = weather(ask("Wellington"))

        assertEquals(METNO_ATTRIBUTION, result.attributionText)
        assertEquals(1, metRequests.size)
    }

    @Test
    fun `expired MET body is revalidated with If-Modified-Since and a 304 reuses it`() {
        ask("Wellington")
        now += 90 * MINUTE_MS
        metStatus = 304

        val result = weather(ask("Wellington"))

        assertEquals(METNO_ATTRIBUTION, result.attributionText)
        assertEquals(2, metRequests.size)
        assertEquals(METNO_LAST_MODIFIED, metRequests.last().header("If-Modified-Since"))
    }

    @Test
    fun `429 backs off for Retry-After before MET is called again`() {
        metStatus = 429
        metRetryAfter = "120"

        assertEquals(noCacheMessage, direct(ask("Wellington")).content)
        now += 30_000L
        assertEquals(noCacheMessage, direct(ask("Wellington")).content)
        assertEquals(1, metRequests.size)

        now += 91_000L
        metStatus = 200
        weather(ask("Wellington"))
        assertEquals(2, metRequests.size)
    }

    @Test
    fun `open-meteo 4xx is answered from MET Norway too`() {
        openMeteoStatus = 400

        assertEquals(METNO_ATTRIBUTION, weather(ask("Wellington")).attributionText)
    }

    @Test
    fun `a 304 after Expires re-reads the stored body at the current clock`() {
        assertEquals("17°C", weather(ask("Wellington")).temperatureText)
        now = Instant.parse("2026-11-16T10:30:00Z").toEpochMilli()
        metStatus = 304

        val result = weather(ask("Wellington"))

        assertEquals("16°C", result.temperatureText)
        assertEquals(METNO_LAST_MODIFIED, metRequests.last().header("If-Modified-Since"))
    }

    @Test
    fun `stale MET body replays with attribution when both providers fail`() {
        weather(ask("Wellington"))
        now += 3 * 60 * MINUTE_MS
        metStatus = 503

        val result = weather(ask("Wellington"))

        assertEquals(METNO_ATTRIBUTION, result.attributionText)
        assertEquals("17°C", result.temperatureText)
    }

    @Test
    fun `MET body without Expires is not reused without another MET call`() {
        metExpires = null
        ask("Wellington")
        now += 30 * MINUTE_MS

        ask("Wellington")

        assertEquals(2, metRequests.size)
    }

    @Test
    fun `MET current text omits feels-like when MET supplies no apparent temperature`() {
        assertFalse(direct(ask("Wellington")).content.contains("feels like"))
    }

    @Test
    fun `sleet codes are described as sleet, not freezing rain`() {
        assertEquals("Sleet", wmoDescription(68))
        assertEquals("Heavy sleet", wmoDescription(69))
    }

    @Test
    fun `after 15h local today has no high, low or rain`() {
        val afternoon = Instant.parse("2026-11-16T16:00:00Z").toEpochMilli()
        val daily = JSONObject(
            metNoDailyJson(MET_FIXTURE, days = 1, zone = ZoneId.of("UTC"), nowMillis = afternoon)!!,
        ).getJSONObject("daily")

        assertTrue(daily.getJSONArray("temperature_2m_max").isNull(0))
        assertTrue(daily.getJSONArray("temperature_2m_min").isNull(0))
        assertTrue(daily.getJSONArray("precipitation_sum").isNull(0))
    }



    @Test
    fun `symbol codes map to WMO codes, ignoring day and night suffixes`() {
        val expected = mapOf(
            "clearsky_day" to 0,
            "fair_night" to 1,
            "partlycloudy_polartwilight" to 2,
            "cloudy" to 3,
            "fog" to 45,
            "lightrain" to 61,
            "rain" to 63,
            "heavyrain" to 65,
            "lightrainshowers_day" to 80,
            "rainshowers_night" to 81,
            "heavyrainshowers" to 82,
            "lightsleetshowers" to 68,
            "heavysleet" to 69,
            "lightsnow" to 71,
            "snow" to 73,
            "heavysnow" to 75,
            "snowshowers_day" to 85,
            "heavysnowshowers" to 86,
            "rainandthunder" to 95,
            "heavyrainshowersandthunder_day" to 95,
            "bogus" to WMO_UNKNOWN,
        )
        expected.forEach { (symbol, code) -> assertEquals(code, metNoWmoCode(symbol), symbol) }
        assertEquals(WMO_UNKNOWN, metNoWmoCode(null))
    }

    @Test
    fun `coordinates are truncated to 4 decimals, not rounded`() {
        assertEquals("-36.8484", metNoCoordinate(-36.848484))
        assertEquals("174.7799", metNoCoordinate(174.7799999))
        assertEquals("174.7800", metNoCoordinate(174.78))
    }

    @Test
    fun `daily conversion groups local days with high low precipitation and midday symbol`() {
        val json = JSONObject(
            metNoDailyJson(MET_FIXTURE, days = 2, zone = ZoneId.of("UTC"), nowMillis = now)!!,
        )
        val daily = json.getJSONObject("daily")

        assertEquals("met.no", json.getString("source"))
        assertEquals("2026-11-16", daily.getJSONArray("time").getString(0))
        assertEquals(22.5, daily.getJSONArray("temperature_2m_max").getDouble(0), 0.001)
        assertEquals(16.1, daily.getJSONArray("temperature_2m_min").getDouble(0), 0.001)
        assertEquals(0.1, daily.getJSONArray("precipitation_sum").getDouble(0), 0.001)
        assertEquals(2, daily.getJSONArray("weather_code").getInt(0))
        assertEquals(2, daily.getJSONArray("time").length())
    }

    @Test
    fun `seven-day conversion sums one 6-hourly day without double counting windows`() {
        val daily = JSONObject(
            metNoDailyJson(MET_FIXTURE, days = 7, zone = ZoneId.of("UTC"), nowMillis = now)!!,
        ).getJSONObject("daily")

        assertEquals("2026-11-19", daily.getJSONArray("time").getString(3))
        assertEquals(17.9, daily.getJSONArray("temperature_2m_max").getDouble(3), 0.001)
        assertEquals(13.4, daily.getJSONArray("temperature_2m_min").getDouble(3), 0.001)
        assertEquals(1.2, daily.getJSONArray("precipitation_sum").getDouble(3), 0.001)
    }

    @Test
    fun `after 15h local MET forecast and weekend cards show no unknown or rain for today`() {
        val savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            now = Instant.parse("2026-11-16T16:00:00Z").toEpochMilli()
            val forecast = direct(askWith("Wellington", "forecast_days" to "3"))
            assertFalse(forecast.content.contains("?°C"))
            assertFalse(forecast.content.contains("Forecast unavailable"))
            assertFalse(forecast.content.lines()[1].contains("mm rain"))
            assertNotEquals("Forecast unavailable", weather(forecast).temperatureText)

            // 2026-11-21 is a Saturday, so today is the first weekend day.
            now = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli()
            val weekend = direct(askWith("Wellington", "period" to "weekend"))
            assertFalse(weekend.content.contains("?°C"))
            assertFalse(weekend.content.contains("Forecast unavailable"))
            assertFalse(weekend.content.lines()[1].contains("mm rain"))
            assertNotEquals("Forecast unavailable", weather(weekend).temperatureText)
        } finally {
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun `a parsed MET body is only attributed when source is met_no`() {
        assertEquals(METNO_ATTRIBUTION, weatherAttributionFor(JSONObject("{\"source\":\"met.no\"}")))
        assertNull(weatherAttributionFor(JSONObject("{\"daily\":{}}")))
    }

    private fun ask(location: String): SkillResult =
        runBlocking { skill.execute(SkillCall(skill.name, mapOf("location" to location))) }

    private fun askWith(location: String, vararg extra: Pair<String, String>): SkillResult =
        runBlocking { skill.execute(SkillCall(skill.name, mapOf("location" to location) + extra)) }

    private fun direct(result: SkillResult): SkillResult.DirectReply =
        assertInstanceOf(SkillResult.DirectReply::class.java, result)

    private fun weather(result: SkillResult): ToolPresentation.Weather =
        assertInstanceOf(ToolPresentation.Weather::class.java, direct(result).presentation)

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            when {
                url.startsWith(GEOCODE_PREFIX) -> respond(chain, 200, GEOCODE_JSON)
                request.url.host == "api.open-meteo.com" && openMeteoFails ->
                    openMeteoStatus?.let { respond(chain, it, "") } ?: throw SocketTimeoutException("read timed out")
                request.url.host == "api.open-meteo.com" -> respond(chain, 200, OPEN_METEO_CURRENT_JSON)
                request.url.host == "air-quality-api.open-meteo.com" -> respond(chain, 404, "")
                request.url.host == "api.met.no" -> {
                    metRequests.add(request)
                    respondMet(chain)
                }
                else -> respond(chain, 404, "")
            }
        }
        .build()

    private fun respondMet(chain: Interceptor.Chain): Response {
        val builder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(metStatus)
            .message("test")
            .body((if (metStatus == 200) metBody else "").toResponseBody("application/json".toMediaType()))
        metExpires?.let { builder.header("Expires", it) }
        metLastModified?.let { builder.header("Last-Modified", it) }
        metRetryAfter?.let { builder.header("Retry-After", it) }
        return builder.build()
    }

    private fun respond(chain: Interceptor.Chain, status: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private companion object {
        const val MINUTE_MS = 60 * 1000L
        const val GEOCODE_PREFIX = "https://nominatim.openstreetmap.org/search?"
        const val METNO_LAST_MODIFIED = "Mon, 16 Nov 2026 00:00:00 GMT"
        const val GEOCODE_JSON = """[{"lat":"-41.29","lon":"174.78","importance":0.9,"address":{"country_code":"nz"}}]"""
        const val OPEN_METEO_CURRENT_JSON = """
            {"latitude":-41.29,"longitude":174.78,
             "current":{"temperature_2m":13.1,"apparent_temperature":11.0,"relative_humidity_2m":70,
                        "weather_code":3,"wind_speed_10m":5.0,"precipitation":0.0,"precipitation_probability":20},
             "daily":{"temperature_2m_max":[15.0],"temperature_2m_min":[9.0],"uv_index_max":[3.0],
                      "sunrise":["2026-11-16T06:10"],"sunset":["2026-11-16T20:40"]}}
        """
        val MET_FIXTURE: String = checkNotNull(
            GetWeatherMetNorwayFallbackTest::class.java.getResource("/met_norway_locationforecast_compact.json"),
        ).readText()
    }
}
