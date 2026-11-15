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
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1921 — a stalled weather service fails fast, serves a labelled cached forecast only inside the
 * 12 h window, and otherwise gives the clear "isn't answering" message. Open-Meteo is faked through
 * an OkHttp interceptor; the bounded-time test uses a real socket that accepts and never answers.
 */
class GetWeatherStaleFallbackTest {
    @TempDir
    lateinit var dir: File

    private val context = mockk<Context>(relaxed = true)
    private val urls = CopyOnWriteArrayList<String>()
    private var now = 1_800_000_000_000L

    /** Open-Meteo forecast status; null throws a timeout, as an unanswered read would. */
    private var forecastStatus: Int? = 200
    private var silentPort: Int? = null
    private var geocodeBody = GEOCODE_JSON
    private var forecastBody = FORECAST_JSON
    private var airQualityFails = false
    private val sockets = mutableListOf<ServerSocket>()
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
        sockets.forEach { it.close() }
    }

    @Test
    fun `timeout inside the 12 h window serves the cached forecast labelled with its time`() {
        val cachedAt = now
        assertInstanceOf(SkillResult.DirectReply::class.java, ask("Wellington"))
        now += 3 * HOUR_MS // past the 2 h fresh window, inside the 12 h stale window
        forecastStatus = null

        val stale = assertInstanceOf(SkillResult.DirectReply::class.java, ask("Wellington"))
        val time = staleForecastTimeText(cachedAt, now, is24Hour = false, zone = ZoneId.systemDefault())

        assertTrue(
            stale.content.startsWith("The weather service isn't answering, so here's the forecast from $time:"),
            "content=${stale.content}",
        )
        assertTrue(
            stale.spokenSummary.orEmpty()
                .startsWith("The weather service isn't answering, so here's the forecast from $time: In "),
            "spoken=${stale.spokenSummary}",
        )
        assertEquals(
            "Forecast from $time. The weather service isn't answering, so it may be out of date.",
            weather(stale).staleNoteText,
        )
    }

    @Test
    fun `cache older than 12 h is not used and gets the clear message`() {
        ask("Wellington")
        now += 12 * HOUR_MS + 1
        forecastStatus = null

        assertEquals(noCacheMessage, direct(ask("Wellington")).content)
    }

    @Test
    fun `cache exactly 12 h old is still served`() {
        val cachedAt = now
        ask("Wellington")
        now += 12 * HOUR_MS
        forecastStatus = null

        assertTrue(
            direct(ask("Wellington")).content.contains(staleForecastTimeText(cachedAt, now, false, ZoneId.systemDefault())),
        )
    }

    @Test
    fun `no cache gives the clear weather-service message and one retry for timeouts`() {
        forecastStatus = null

        assertEquals(noCacheMessage, direct(ask("Palmerston North")).content)
        assertEquals(2, forecastRequests())
    }

    @Test
    fun `stale cache is per place, so another place does not reuse it`() {
        ask("Wellington")
        now += 3 * HOUR_MS
        forecastStatus = null

        assertEquals(noCacheMessage, direct(ask("Auckland")).content)
    }

    @Test
    fun `4xx from the forecast is not retried`() {
        forecastStatus = 404

        assertEquals(noCacheMessage, direct(ask("Palmerston North")).content)
        assertEquals(1, forecastRequests())
    }

    @Test
    fun `stalled service gives up within the 10 s budget and still serves the stale cache`() {
        ask("Wellington")
        now += 3 * HOUR_MS
        silentPort = silentServer()
        val before = forecastRequests()

        val started = System.nanoTime()
        val result = direct(ask("Wellington"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMs < 11_000, "elapsed=${elapsedMs}ms")
        assertTrue(result.content.startsWith("The weather service isn't answering, so here's the forecast from"))
        assertEquals(2, forecastRequests() - before, "one retry after the first read timeout")
    }

    @Test
    fun `malformed 200 forecast is not cached and does not replace the good entry`() {
        val cachedAt = now
        ask("Wellington")
        now += 3 * HOUR_MS
        forecastBody = "{}" // HTTP 200 without a current block: fails to parse
        val stale = direct(ask("Wellington"))
        forecastBody = FORECAST_JSON
        forecastStatus = null

        val time = staleForecastTimeText(cachedAt, now, is24Hour = false, zone = ZoneId.systemDefault())
        assertTrue(stale.content.contains(time), "content=${stale.content}")
        assertTrue(direct(ask("Wellington")).content.contains(time))
    }

    @Test
    fun `failed air quality keeps the live forecast and omits air quality`() {
        airQualityFails = true

        val result = weather(ask("Palmerston North"))

        assertNull(result.airQualityText)
        assertNull(result.staleNoteText)
    }

    @Test
    fun `named place that does not geocode does not serve stale cache`() {
        ask("Wellington")
        now += 3 * HOUR_MS
        geocodeBody = "[]"

        val content = direct(ask("Wellington")).content

        assertFalse(content.contains("isn't answering"), "content=$content")
    }

    @Test
    fun `tomorrow entry cached just before midnight is not served just after it`() {
        now = localMillis(2027, 1, 15, 23, 59)
        forecastBody = multiDayBody(listOf("2027-01-16", "2027-01-17"))
        direct(askWith("location" to "Wellington", "day" to "tomorrow"))
        now = localMillis(2027, 1, 16, 0, 1)
        forecastStatus = null

        assertEquals(noCacheMessage, direct(askWith("location" to "Wellington", "day" to "tomorrow")).content)
    }

    @Test
    fun `three day entry from yesterday drops the past day and keeps today onward`() {
        now = localMillis(2027, 1, 15, 14, 0)
        forecastBody = multiDayBody(listOf("2027-01-15", "2027-01-16", "2027-01-17"))
        direct(askWith("location" to "Wellington", "forecast_days" to "3"))
        now = localMillis(2027, 1, 16, 0, 30)
        forecastStatus = null

        val content = direct(askWith("location" to "Wellington", "forecast_days" to "3")).content

        assertTrue(content.contains("isn't answering"), "content=$content")
        assertFalse(content.contains("\n${formatForecastDate("2027-01-15")}:"), "content=$content")
        assertTrue(content.contains("\n${formatForecastDate("2027-01-16")}:"), "content=$content")
        assertTrue(content.contains("\n${formatForecastDate("2027-01-17")}:"), "content=$content")
    }

    private fun localMillis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun askWith(vararg args: Pair<String, String>): SkillResult =
        runBlocking { skill.execute(SkillCall(skill.name, args.toMap())) }

    private fun multiDayBody(dates: List<String>): String {
        fun row(value: (String) -> String) = dates.joinToString(",", "[", "]", transform = value)
        val daily = listOf(
            "\"time\":" + row { "\"$it\"" },
            "\"temperature_2m_max\":" + row { "15.0" },
            "\"temperature_2m_min\":" + row { "9.0" },
            "\"precipitation_sum\":" + row { "0.0" },
            "\"weather_code\":" + row { "3" },
            "\"uv_index_max\":" + row { "3.0" },
            "\"sunrise\":" + row { "\"${it}T06:10\"" },
            "\"sunset\":" + row { "\"${it}T20:40\"" },
        ).joinToString(",")
        return "{\"latitude\":-41.29,\"longitude\":174.78,\"daily\":{$daily}}"
    }

    private fun forecastRequests(): Int = urls.count { it.startsWith(FORECAST_PREFIX) }

    private fun ask(location: String): SkillResult =
        runBlocking { skill.execute(SkillCall(skill.name, mapOf("location" to location))) }

    private fun direct(result: SkillResult): SkillResult.DirectReply =
        assertInstanceOf(SkillResult.DirectReply::class.java, result)

    private fun weather(result: SkillResult): ToolPresentation.Weather =
        assertInstanceOf(ToolPresentation.Weather::class.java, direct(result).presentation)

    private fun silentServer(): Int = ServerSocket(0).also { sockets += it }.localPort

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            urls.add(url)
            val port = silentPort
            when {
                port != null && request.url.host == "api.open-meteo.com" -> {
                    val redirected = request.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
                    chain.proceed(request.newBuilder().url(redirected).build())
                }
                url.startsWith(GEOCODE_PREFIX) -> respond(chain, 200, geocodeBody)
                url.startsWith(FORECAST_PREFIX) -> {
                    val status = forecastStatus ?: throw SocketTimeoutException("read timed out")
                    respond(chain, status, if (status == 200) forecastBody else "")
                }
                url.startsWith(AIR_QUALITY_PREFIX) && airQualityFails ->
                    throw SocketTimeoutException("air quality read timed out")
                else -> respond(chain, 404, "")
            }
        }
        .build()

    private fun respond(chain: Interceptor.Chain, status: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val GEOCODE_PREFIX = "https://nominatim.openstreetmap.org/search?"
        const val FORECAST_PREFIX = "https://api.open-meteo.com/v1/forecast"
        const val AIR_QUALITY_PREFIX = "https://air-quality-api.open-meteo.com/v1/air-quality"
        const val GEOCODE_JSON = """[{"lat":"-41.29","lon":"174.78","importance":0.9,"address":{"country_code":"nz"}}]"""
        const val FORECAST_JSON = """
            {"latitude":-41.29,"longitude":174.78,
             "current":{"temperature_2m":13.1,"apparent_temperature":11.0,"relative_humidity_2m":70,
                        "weather_code":3,"wind_speed_10m":5.0,"precipitation":0.0,"precipitation_probability":20},
             "daily":{"temperature_2m_max":[15.0],"temperature_2m_min":[9.0],"uv_index_max":[3.0],
                      "sunrise":["2026-11-15T06:10"],"sunset":["2026-11-15T20:40"]}}
        """
    }
}
