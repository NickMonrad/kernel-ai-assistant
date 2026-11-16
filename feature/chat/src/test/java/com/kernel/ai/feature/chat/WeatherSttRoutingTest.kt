package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.voice.TranscriptNormaliser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #1940: STT transcript → [TranscriptNormaliser] → [QuickIntentRouter] must reach the
 * deterministic get_weather regex path (no LLM fall-through) for the misheard weather phrases.
 */
class WeatherSttRoutingTest {

    private val router = QuickIntentRouter()

    private fun routeVoice(transcript: String): QuickIntentRouter.RouteResult =
        router.route(TranscriptNormaliser.normalise(transcript))

    private fun assertWeatherRegex(transcript: String) {
        val result = routeVoice(transcript)
        assertTrue(result is QuickIntentRouter.RouteResult.RegexMatch, "expected RegexMatch for '$transcript', got $result")
        assertEquals("get_weather", (result as QuickIntentRouter.RouteResult.RegexMatch).intent.intentName, transcript)
    }

    private fun assertNotWeather(transcript: String) {
        val result = routeVoice(transcript)
        val weather = (result as? QuickIntentRouter.RouteResult.RegexMatch)?.intent?.intentName == "get_weather"
        assertFalse(weather, "'$transcript' must not route to get_weather, got $result")
    }

    @Test
    fun `issue 1940 transcripts route to get_weather via regex`() {
        assertWeatherRegex("what the whither in Auckland")
        assertWeatherRegex("what the wither in Auckland")
        assertWeatherRegex("what's the whether tomorrow")
        assertWeatherRegex("what's the way tomorrow")
        assertWeatherRegex("how's the wether in wellington")
    }

    @Test
    fun `corrected transcripts keep location and day params`() {
        val result = routeVoice("what the whither in Auckland")
        val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("Auckland", intent.params["location"])

        val tomorrow = (routeVoice("what's the whether tomorrow") as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("tomorrow", tomorrow.params["day"])
    }

    @Test
    fun `non-weather voice transcripts do not route to get_weather`() {
        assertNotWeather("what's the way this works")
        assertNotWeather("whether or not I need a jacket")
    }
}
