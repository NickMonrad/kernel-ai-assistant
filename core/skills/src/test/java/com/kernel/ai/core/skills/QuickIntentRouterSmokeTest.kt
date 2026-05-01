package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Phase 4 smoke tests — one or two representative phrasings per intent, covering
 * the full breadth of [QuickIntentRouter] regex patterns.
 *
 * Each case calls [QuickIntentRouter.matchQuickIntent] and asserts the returned
 * [QuickIntentRouter.QuickIntent.action] equals the expected intent name.
 *
 * Run with: ./gradlew :core:skills:testDebugUnitTest --tests "*.QuickIntentRouterSmokeTest"
 */
class QuickIntentRouterSmokeTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
    @MethodSource("smokeInputs")
    fun `matchQuickIntent maps phrasing to expected intent`(input: String, expectedAction: String) {
        val intent = QuickIntentRouter.matchQuickIntent(input)
        assertNotNull(intent, "Expected intent '$expectedAction' but got null for: \"$input\"")
        assertEquals(expectedAction, intent!!.action, "Wrong intent for: \"$input\"")
    }

    companion object {
        @JvmStatic
        fun smokeInputs(): Stream<Arguments> = Stream.of(
            // get_time — time queries
            Arguments.of("what time is it", "get_time"),
            Arguments.of("what's the time", "get_time"),

            // get_time — date/day queries (router uses get_time for all temporal queries)
            Arguments.of("what day is it", "get_time"),
            Arguments.of("what's today's date", "get_time"),

            // get_weather
            Arguments.of("what's the weather", "get_weather"),
            Arguments.of("what's the forecast for the next seven days", "get_weather"),

            // set_alarm
            Arguments.of("set an alarm for 7am", "set_alarm"),
            Arguments.of("wake me up at 6:30", "set_alarm"),

            // set_timer
            Arguments.of("set a timer for 5 minutes", "set_timer"),
            Arguments.of("5 minute timer", "set_timer"),

            // cancel_alarm
            Arguments.of("cancel my alarm", "cancel_alarm"),
            Arguments.of("turn off my alarm", "cancel_alarm"),

            // cancel_timer
            Arguments.of("stop the timer", "cancel_timer"),
            Arguments.of("cancel the timer", "cancel_timer"),

            // get_battery
            Arguments.of("how's my battery", "get_battery"),
            Arguments.of("battery level", "get_battery"),

            // toggle_dnd_on
            Arguments.of("silence my phone", "toggle_dnd_on"),
            Arguments.of("mute my notifications", "toggle_dnd_on"),

            // make_call
            Arguments.of("call mum", "make_call"),
            Arguments.of("ring dad", "make_call"),

            // send_sms
            Arguments.of("text Sarah hey are you free", "send_sms"),
            Arguments.of("send a message to John", "send_sms"),

            // create_calendar_event
            Arguments.of("set a dental appointment for 5pm tomorrow", "create_calendar_event"),
            Arguments.of("add meeting on friday at 2pm", "create_calendar_event"),

            // open_app
            Arguments.of("open spotify", "open_app"),
            Arguments.of("launch settings", "open_app"),

            // add_to_list
            Arguments.of("add milk to my shopping list", "add_to_list"),
            Arguments.of("add eggs to my grocery list", "add_to_list"),
        )
    }
}
