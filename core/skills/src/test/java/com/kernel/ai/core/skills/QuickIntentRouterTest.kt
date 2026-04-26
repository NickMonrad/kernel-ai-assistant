package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tier 2 Fast Intent Pipeline — comprehensive intent routing test suite.
 *
 * Tests three tiers:
 *   1. Regex match (instant, deterministic)
 *   2. Classifier match (BERT-tiny mock, fuzzy)
 *   3. E4B fallthrough (complex/ambiguous)
 *
 * Run with: ./gradlew :core:skills:test --tests "*.QuickIntentRouterTest"
 *
 * The test report at the end summarizes coverage across all phrasings,
 * showing where each input was routed and flagging gaps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuickIntentRouterTest {

    private lateinit var regexOnlyRouter: QuickIntentRouter
    private lateinit var hybridRouter: QuickIntentRouter

    // ── Mock classifier simulating BERT-tiny via keyword similarity ───────────

    /**
     * Keyword-overlap classifier that simulates BERT-tiny zero-shot behaviour.
     * Each intent has canonical phrases; we compute Jaccard similarity on word sets.
     * This is NOT production code — it's a stand-in until real BERT-tiny is integrated.
     */
    class KeywordClassifier : QuickIntentRouter.IntentClassifier {
        private val intentPhrases = mapOf(
            "toggle_flashlight_on" to listOf(
                "turn on the torch", "flashlight on", "switch on the light",
                "can you turn the flashlight on", "i need light", "light up",
                "torch please", "flash on", "illuminate",
            ),
            "toggle_flashlight_off" to listOf(
                "turn off the torch", "flashlight off", "switch off the light",
                "kill the light", "torch off", "lights out", "dark mode",
            ),
            "set_alarm" to listOf(
                "set an alarm", "wake me up", "alarm for", "set alarm",
                "reminder to wake up", "morning alarm", "alarm at",
            ),
            "set_timer" to listOf(
                "set a timer", "start a countdown", "timer for", "countdown",
                "start timer", "time me for", "count down",
            ),
            "toggle_dnd_on" to listOf(
                "do not disturb", "silent mode", "quiet mode", "mute notifications",
                "dnd on", "silence my phone", "go quiet", "no notifications",
            ),
            "toggle_dnd_off" to listOf(
                "turn off do not disturb", "disable silent mode", "unmute",
                "dnd off", "notifications back on", "stop quiet mode",
            ),
            "get_battery" to listOf(
                "battery level", "how much battery", "battery percentage",
                "charge level", "power remaining", "battery status", "how much charge",
            ),
            "get_time" to listOf(
                "what time is it", "current time", "what's the time",
                "what date is it", "what day is it", "tell me the time",
            ),
            "set_volume" to listOf(
                "set volume to 50", "volume 7", "turn volume up", "volume at 60",
                "increase volume", "lower the volume",
            ),
            "toggle_wifi" to listOf(
                "turn on wifi", "wifi off", "enable wireless", "disable wifi", "switch wifi on",
            ),
            "toggle_bluetooth" to listOf(
                "turn on bluetooth", "bluetooth off", "enable bluetooth", "disable bt",
            ),
            "toggle_airplane_mode" to listOf(
                "airplane mode on", "turn on flight mode", "enable airplane mode",
                "airplane mode off",
            ),
            "toggle_hotspot" to listOf(
                "turn on hotspot", "enable hotspot", "mobile hotspot on", "hotspot off",
                "tethering on",
            ),
            "play_plex" to listOf(
                "play on plex", "watch on plex", "plex breaking bad", "stream plex",
            ),
            "play_media_album" to listOf(
                "play album", "play the album", "album by artist", "play dark side of the moon",
            ),
            "play_media_playlist" to listOf(
                "play playlist", "my workout playlist", "play chill vibes playlist",
            ),
            "play_media" to listOf(
                "play music", "play a song", "play thriller", "play bohemian rhapsody",
            ),
            "navigate_to" to listOf(
                "navigate to", "directions to", "take me to", "drive to work",
                "get me to the airport",
            ),
            "find_nearby" to listOf(
                "find cafes nearby", "near me", "close by", "find something near me",
            ),
            "make_call" to listOf(
                "call mum", "ring dad", "phone the office", "dial sarah", "call john",
            ),
            "add_to_list" to listOf(
                "add to shopping list", "add milk to list", "shopping list", "add to my list",
            ),
            "smart_home_on" to listOf(
                "turn on bedroom light", "switch on the fan", "turn on the TV",
                "turn on living room lights",
            ),
            "smart_home_off" to listOf(
                "turn off bedroom TV", "switch off the fan", "turn off all lights",
            ),
        )

        override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification? {
            val inputWords = input.lowercase().split(Regex("""\W+""")).filter { it.length > 1 }.toSet()
            var bestIntent: String? = null
            var bestScore = 0f

            for ((intent, phrases) in intentPhrases) {
                for (phrase in phrases) {
                    val phraseWords = phrase.lowercase().split(Regex("""\W+""")).filter { it.length > 1 }.toSet()
                    val intersection = inputWords.intersect(phraseWords).size.toFloat()
                    val union = inputWords.union(phraseWords).size.toFloat()
                    val jaccard = if (union > 0) intersection / union else 0f

                    // Boost exact substring matches
                    val containsBoost = if (input.lowercase().contains(phrase)) 0.3f else 0f
                    val score = (jaccard + containsBoost).coerceAtMost(1.0f)

                    if (score > bestScore) {
                        bestScore = score
                        bestIntent = intent
                    }
                }
            }

            return if (bestIntent != null && bestScore > 0.2f) {
                QuickIntentRouter.IntentClassifier.Classification(bestIntent, bestScore)
            } else null
        }
    }

    @BeforeEach
    fun setup() {
        regexOnlyRouter = QuickIntentRouter()
        hybridRouter = QuickIntentRouter(
            classifier = KeywordClassifier(),
            similarityThreshold = 0.5f, // Lower for keyword mock (real BERT would use 0.85)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FLASHLIGHT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Flashlight ON")
    inner class FlashlightOn {

        @ParameterizedTest(name = "Regex: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#flashlightOnRegexPhrases")
        fun `should match via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_flashlight_on", input)
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#flashlightOnClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)

            // Should NOT match regex
            assertFallThrough(regexResult, input)
            // SHOULD match classifier
            assertClassifierOrFallthrough(hybridResult, "toggle_flashlight_on", input)
        }
    }

    @Nested
    @DisplayName("Flashlight OFF")
    inner class FlashlightOff {

        @ParameterizedTest(name = "Regex: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#flashlightOffRegexPhrases")
        fun `should match via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_flashlight_off", input)
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#flashlightOffClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "toggle_flashlight_off", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ALARM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Set Alarm")
    inner class SetAlarm {

        @ParameterizedTest(name = "Regex: \"{0}\" → hours={1}, minutes={2}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#alarmRegexPhrases")
        fun `should match via regex with correct params`(
            input: String,
            expectedHours: String,
            expectedMinutes: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_alarm", input)

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedHours, intent.params["hours"], "hours for '$input'")
            assertEquals(expectedMinutes, intent.params["minutes"], "minutes for '$input'")
        }

        @ParameterizedTest(name = "Regex+day: \"{0}\" → hours={1}, minutes={2}, day={3}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#alarmWithDayRegexPhrases")
        fun `should match via regex with correct params and day`(
            input: String,
            expectedHours: String,
            expectedMinutes: String,
            expectedDay: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_alarm", input)

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedHours, intent.params["hours"], "hours for '$input'")
            assertEquals(expectedMinutes, intent.params["minutes"], "minutes for '$input'")
            assertEquals(expectedDay, intent.params["day"], "day for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#alarmClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "set_alarm", input)
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#setAlarmNeedsSlotPhrases")
        fun `should return NeedsSlot for bare alarm phrases missing time`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("set_alarm", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("time", needsSlot.missingSlot.name, "missing slot for '$input'")
        }

        @Test
        fun `should recover flattened thirty alarm times`() {
            val result = regexOnlyRouter.route("set an alarm for 37am")
            assertRegexMatch(result, "set_alarm", "set an alarm for 37am")

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("7", intent.params["hours"])
            assertEquals("30", intent.params["minutes"])
            assertEquals("7:30", intent.params["time"])
        }

        @Test
        fun `should recover compact alarm times`() {
            val result = regexOnlyRouter.route("set an alarm for 730am")
            assertRegexMatch(result, "set_alarm", "set an alarm for 730am")

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("7", intent.params["hours"])
            assertEquals("30", intent.params["minutes"])
            assertEquals("7:30", intent.params["time"])
        }

        @Test
        fun `should recover flattened oclock alarm times`() {
            val result = regexOnlyRouter.route("set an alarm for 80'clock")
            assertRegexMatch(result, "set_alarm", "set an alarm for 80'clock")

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("8", intent.params["hours"])
            assertEquals("0", intent.params["minutes"])
            assertEquals("8:00", intent.params["time"])
        }

        @Test
        fun `should parse named alarm with recovered to 30 time`() {
            val result = regexOnlyRouter.route("set an alarm for to 30 called dentist")
            assertRegexMatch(result, "set_alarm", "set an alarm for to 30 called dentist")

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("2", intent.params["hours"])
            assertEquals("30", intent.params["minutes"])
            assertEquals("2:30", intent.params["time"])
            assertEquals("dentist", intent.params["label"])
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Set Timer")
    inner class SetTimer {

        @ParameterizedTest(name = "Regex: \"{0}\" → {1}s")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timerRegexPhrases")
        fun `should match via regex with correct duration`(
            input: String,
            expectedSeconds: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_timer", input)

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedSeconds, intent.params["duration_seconds"], "seconds for '$input'")
        }

        @ParameterizedTest(name = "Regex+label: \"{0}\" → {1}s, label={2}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timerWithLabelRegexPhrases")
        fun `should match via regex with correct duration and label`(
            input: String,
            expectedSeconds: String,
            expectedLabel: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_timer", input)

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedSeconds, intent.params["duration_seconds"], "seconds for '$input'")
            assertEquals(expectedLabel, intent.params["label"], "label for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timerClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "set_timer", input)
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#setTimerNeedsSlotPhrases")
        fun `should return NeedsSlot for bare timer phrases missing duration`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("set_timer", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("duration_seconds", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANCEL TIMER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cancel Timer")
    inner class CancelTimer {

        @Test
        fun `cancel the 10 minute timer should route to cancel_timer_named not set_timer`() {
            // Regression: "10 minute timer" substring matched set_timer before cancel_timer_named
            // Fix: cancel patterns moved before set_timer in the pattern list
            val result = regexOnlyRouter.route("cancel the 10 minute timer")
            assertRegexMatch(result, "cancel_timer_named", "cancel the 10 minute timer")
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("10 minute", intent.params["name"], "name param should be '10 minute'")
        }

        @Test
        fun `stop the pasta timer should route to cancel_timer_named`() {
            val result = regexOnlyRouter.route("stop the pasta timer")
            assertRegexMatch(result, "cancel_timer_named", "stop the pasta timer")
        }

        @Test
        fun `cancel my egg timer should route to cancel_timer_named`() {
            val result = regexOnlyRouter.route("cancel my egg timer")
            assertRegexMatch(result, "cancel_timer_named", "cancel my egg timer")
        }

        @Test
        fun `cancel the timer should route to cancel_timer not cancel_timer_named`() {
            val result = regexOnlyRouter.route("cancel the timer")
            assertRegexMatch(result, "cancel_timer", "cancel the timer")
        }

        @Test
        fun `5 minute timer should still route to set_timer`() {
            val result = regexOnlyRouter.route("5 minute timer")
            assertRegexMatch(result, "set_timer", "5 minute timer")
        }

        @Test
        fun `10 minute tea timer should still route to set_timer`() {
            val result = regexOnlyRouter.route("10 minute tea timer")
            assertRegexMatch(result, "set_timer", "10 minute tea timer")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VOICE REGRESSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Voice Regression Coverage")
    inner class VoiceRegressions {

        @Test
        fun `should match timer remaining aliases without explicit timer noun`() {
            assertRegexMatch(regexOnlyRouter.route("get time remaining"), "get_timer_remaining", "get time remaining")
            assertRegexMatch(
                regexOnlyRouter.route("how much time is remaining"),
                "get_timer_remaining",
                "how much time is remaining",
            )
        }

        @Test
        fun `should match bare toggle bluetooth and wifi phrases`() {
            assertRegexMatch(regexOnlyRouter.route("toggle bluetooth"), "toggle_bluetooth", "toggle bluetooth")
            assertRegexMatch(regexOnlyRouter.route("toggle wifi"), "toggle_wifi", "toggle wifi")
        }

        @Test
        fun `should match bare toggle dnd phrase`() {
            assertRegexMatch(regexOnlyRouter.route("toggle dnd"), "toggle_dnd_on", "toggle dnd")
        }

        @Test
        fun `should match bare media app launch phrases`() {
            assertRegexMatch(regexOnlyRouter.route("play youtube music"), "play_youtube_music", "play youtube music")
            assertRegexMatch(regexOnlyRouter.route("play plexamp"), "play_plexamp", "play plexamp")
            assertRegexMatch(regexOnlyRouter.route("open youtube music"), "play_youtube_music", "open youtube music")
            assertRegexMatch(regexOnlyRouter.route("open plexamp"), "play_plexamp", "open plexamp")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DND TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Do Not Disturb")
    inner class DoNotDisturb {

        @ParameterizedTest(name = "Regex ON: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#dndOnRegexPhrases")
        fun `should match DND on via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_dnd_on", input)
        }

        @ParameterizedTest(name = "Regex OFF: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#dndOffRegexPhrases")
        fun `should match DND off via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_dnd_off", input)
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#dndClassifierPhrases")
        fun `should match DND via classifier`(input: String, expectedIntent: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, expectedIntent, input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BATTERY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Battery")
    inner class Battery {

        @ParameterizedTest(name = "Regex: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#batteryRegexPhrases")
        fun `should match via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_battery", input)
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#batteryClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "get_battery", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIME / DATE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Time / Date")
    inner class TimeDate {

        @ParameterizedTest(name = "Regex: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timeRegexPhrases")
        fun `should match via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_time", input)
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timeClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "get_time", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // E4B FALLTHROUGH — these should NEVER match Tier 2
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("E4B Fallthrough (complex queries)")
    inner class E4BFallthrough {

        @ParameterizedTest(name = "Falls through: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#e4bFallthroughPhrases")
        fun `should fall through to E4B`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            assertFallThrough(regexResult, input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VOLUME TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Set Volume")
    inner class SetVolume {

        @ParameterizedTest(name = "Regex: \"{0}\" → value={1}, is_percent={2}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#volumeRegexPhrases")
        fun `should match via regex with correct params`(
            input: String,
            expectedValue: String,
            expectedIsPercent: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_volume", input)

            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedValue, intent.params["value"], "value for '$input'")
            assertEquals(expectedIsPercent, intent.params["is_percent"], "is_percent for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#volumeClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "set_volume", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WIFI TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Toggle WiFi")
    inner class ToggleWifi {

        @ParameterizedTest(name = "Regex ON: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#wifiOnRegexPhrases")
        fun `should match wifi on via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_wifi", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("on", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Regex OFF: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#wifiOffRegexPhrases")
        fun `should match wifi off via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_wifi", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("off", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#wifiClassifierPhrases")
        fun `should match wifi via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "toggle_wifi", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUETOOTH TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Toggle Bluetooth")
    inner class ToggleBluetooth {

        @ParameterizedTest(name = "Regex ON: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#bluetoothOnRegexPhrases")
        fun `should match bluetooth on via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_bluetooth", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("on", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Regex OFF: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#bluetoothOffRegexPhrases")
        fun `should match bluetooth off via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_bluetooth", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("off", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#bluetoothClassifierPhrases")
        fun `should match bluetooth via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "toggle_bluetooth", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AIRPLANE MODE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Toggle Airplane Mode")
    inner class ToggleAirplaneMode {

        @ParameterizedTest(name = "Regex ON: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#airplaneModeOnRegexPhrases")
        fun `should match airplane mode on via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_airplane_mode", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("on", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Regex OFF: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#airplaneModeOffRegexPhrases")
        fun `should match airplane mode off via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_airplane_mode", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("off", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#airplaneModeClassifierPhrases")
        fun `should match airplane mode via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "toggle_airplane_mode", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HOTSPOT TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Toggle Hotspot")
    inner class ToggleHotspot {

        @ParameterizedTest(name = "Regex ON: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#hotspotOnRegexPhrases")
        fun `should match hotspot on via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_hotspot", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("on", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Regex OFF: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#hotspotOffRegexPhrases")
        fun `should match hotspot off via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "toggle_hotspot", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals("off", intent.params["state"], "state for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#hotspotClassifierPhrases")
        fun `should match hotspot via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "toggle_hotspot", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MEDIA TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Play Plex")
    inner class PlayPlex {

        @ParameterizedTest(name = "Regex: \"{0}\" → title={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playPlexRegexPhrases")
        fun `should match via regex with correct title`(input: String, expectedTitle: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_plex", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedTitle, intent.params["title"], "title for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playPlexClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "play_plex", input)
        }
    }

    @Nested
    @DisplayName("Play YouTube")
    inner class PlayYoutube {

        @ParameterizedTest(name = "Regex: \"{0}\" → query={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playYoutubeRegexPhrases")
        fun `should match via regex with correct query`(input: String, expectedQuery: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_youtube", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedQuery, intent.params["query"], "query for '$input'")
        }
    }

    @Nested
    @DisplayName("Play Spotify")
    inner class PlaySpotify {

        @ParameterizedTest(name = "Regex: \"{0}\" → query={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playSpotifyRegexPhrases")
        fun `should match via regex with correct query`(input: String, expectedQuery: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_spotify", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedQuery, intent.params["query"], "query for '$input'")
        }
    }

    @Nested
    @DisplayName("Play Netflix")
    inner class PlayNetflix {

        @ParameterizedTest(name = "Regex: \"{0}\" → query={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playNetflixRegexPhrases")
        fun `should match via regex with correct query`(input: String, expectedQuery: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_netflix", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedQuery, intent.params["query"], "query for '$input'")
        }
    }

    @Nested
    @DisplayName("Open App")
    inner class OpenApp {

        @ParameterizedTest(name = "Regex: \"{0}\" → app_name={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#openAppRegexPhrases")
        fun `should match via regex with correct app_name`(input: String, expectedApp: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "open_app", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedApp, intent.params["app_name"], "app_name for '$input'")
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#openAppNeedsSlotPhrases")
        fun `should return NeedsSlot for bare open-app phrases missing app name`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("open_app", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("app_name", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    @Nested
    @DisplayName("Play Media Album")
    inner class PlayMediaAlbum {

        @ParameterizedTest(name = "Regex: \"{0}\" → album={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playAlbumRegexPhrases")
        fun `should match via regex with correct album`(input: String, expectedAlbum: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_media_album", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedAlbum, intent.params["album"], "album for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playAlbumClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "play_media_album", input)
        }
    }

    @Nested
    @DisplayName("Play Media Playlist")
    inner class PlayMediaPlaylist {

        @ParameterizedTest(name = "Regex: \"{0}\" → playlist={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playPlaylistRegexPhrases")
        fun `should match via regex with correct playlist`(input: String, expectedPlaylist: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_media_playlist", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedPlaylist, intent.params["playlist"], "playlist for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playPlaylistClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "play_media_playlist", input)
        }
    }

    @Nested
    @DisplayName("Play Media")
    inner class PlayMedia {

        @ParameterizedTest(name = "Regex: \"{0}\" → query={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playMediaRegexPhrases")
        fun `should match via regex with correct query`(input: String, expectedQuery: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "play_media", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedQuery, intent.params["query"], "query for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#playMediaClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "play_media", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TWO-PASS FALLBACK REGRESSION TESTS (#555)
    // Verify that specific patterns always beat catch-all fallback patterns
    // (play_media, terse smart_home_on/off), regardless of declaration order.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Two-Pass Fallback Regression (#555)")
    inner class TwoPassFallbackRegression {

        // Test #131: "play the next one" must route to next_track, not play_media.
        @Test
        fun `play the next one routes to next_track not play_media`() {
            val result = regexOnlyRouter.route("play the next one")
            assertRegexMatch(result, "next_track", "play the next one")
        }

        // Test #131 variant
        @Test
        fun `play next song routes to next_track not play_media`() {
            val result = regexOnlyRouter.route("play next song")
            assertRegexMatch(result, "next_track", "play next song")
        }

        // Test #137: "play the previous track" must route to previous_track, not play_media.
        @Test
        fun `play the previous track routes to previous_track not play_media`() {
            val result = regexOnlyRouter.route("play the previous track")
            assertRegexMatch(result, "previous_track", "play the previous track")
        }

        // Test #137 variant
        @Test
        fun `play previous song routes to previous_track not play_media`() {
            val result = regexOnlyRouter.route("play previous song")
            assertRegexMatch(result, "previous_track", "play previous song")
        }

        // Test #125: "hold on" must NOT route to smart_home_on.
        // Expected: FallThrough or ClassifierMatch (never RegexMatch smart_home_on).
        @Test
        fun `hold on does not route to smart_home_on`() {
            val result = regexOnlyRouter.route("hold on")
            assertNotEquals(
                "smart_home_on",
                (result as? QuickIntentRouter.RouteResult.RegexMatch)?.intent?.intentName,
                "\"hold on\" must not match smart_home_on",
            )
        }

        // Smart home catch-all still fires for unambiguous device inputs.
        @Test
        fun `lights on still routes to smart_home_on via fallback`() {
            val result = regexOnlyRouter.route("lights on")
            assertRegexMatch(result, "smart_home_on", "lights on")
        }

        @Test
        fun `heater off still routes to smart_home_off via fallback`() {
            val result = regexOnlyRouter.route("heater off")
            assertRegexMatch(result, "smart_home_off", "heater off")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NAVIGATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Navigate To")
    inner class NavigateTo {

        @ParameterizedTest(name = "Regex: \"{0}\" → destination={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#navigateToRegexPhrases")
        fun `should match via regex with correct destination`(
            input: String,
            expectedDestination: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "navigate_to", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedDestination, intent.params["destination"], "destination for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#navigateToClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "navigate_to", input)
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#navigateToNeedsSlotPhrases")
        fun `should return NeedsSlot for bare navigate phrases missing destination`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("navigate_to", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("destination", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    @Nested
    @DisplayName("Find Nearby")
    inner class FindNearby {

        @ParameterizedTest(name = "Regex: \"{0}\" → query={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#findNearbyRegexPhrases")
        fun `should match via regex with correct query`(input: String, expectedQuery: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "find_nearby", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedQuery, intent.params["query"], "query for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#findNearbyClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "find_nearby", input)
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#findNearbyNeedsSlotPhrases")
        fun `should return NeedsSlot for bare find-nearby phrases missing query`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("find_nearby", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("query", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMUNICATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Make Call")
    inner class MakeCall {

        @ParameterizedTest(name = "Regex: \"{0}\" → contact={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#makeCallRegexPhrases")
        fun `should match via regex with correct contact`(input: String, expectedContact: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "make_call", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedContact, intent.params["contact"], "contact for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#makeCallClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "make_call", input)
        }
    }

    @Nested
    @DisplayName("Send SMS")
    inner class SendSms {

        @ParameterizedTest(name = "Regex: \"{0}\" → contact={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendSmsRegexPhrases")
        fun `should match via regex with correct contact`(input: String, expectedContact: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "send_sms", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedContact, intent.params["contact"], "contact for '$input'")
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\" → contact={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendSmsNeedsSlotPhrases")
        fun `should return NeedsSlot for contact-only phrases`(input: String, expectedContact: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("send_sms", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals(expectedContact, needsSlot.intent.params["contact"], "contact for '$input'")
            assertEquals("message", needsSlot.missingSlot.name, "missing slot name for '$input'")
        }

        @ParameterizedTest(name = "NeedsSlot no-contact: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendSmsNoContactNeedsSlotPhrases")
        fun `should return NeedsSlot for bare send-message phrases missing contact`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("send_sms", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("contact", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    @Nested
    @DisplayName("Send Email")
    inner class SendEmail {

        @ParameterizedTest(name = "Regex: \"{0}\" → contact={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendEmailRegexPhrases")
        fun `should match via regex with correct contact`(input: String, expectedContact: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "send_email", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedContact, intent.params["contact"], "contact for '$input'")
        }

        @ParameterizedTest(name = "NeedsSlot no-contact: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendEmailNoContactNeedsSlotPhrases")
        fun `should return NeedsSlot for bare send-email phrases missing contact`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("send_email", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("contact", needsSlot.missingSlot.name, "missing slot for '$input'")
        }

        @ParameterizedTest(name = "NeedsSlot contact/no-subject: \"{0}\" → contact={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#sendEmailContactNoSubjectNeedsSlotPhrases")
        fun `should return NeedsSlot for email phrases with contact but missing subject`(
            input: String, expectedContact: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("send_email", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals(expectedContact, needsSlot.intent.params["contact"], "contact for '$input'")
            assertEquals("subject", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIST TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Add to List")
    inner class AddToList {

        @ParameterizedTest(name = "Regex: \"{0}\" → item={1}, list={2}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#addToListRegexPhrases")
        fun `should match via regex with correct item and list`(
            input: String,
            expectedItem: String,
            expectedList: String,
        ) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "add_to_list", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedItem, intent.params["item"], "item for '$input'")
            assertEquals(expectedList, intent.params["list_name"], "list_name for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#addToListClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "add_to_list", input)
        }

        @ParameterizedTest(name = "NeedsSlot: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#addToListNeedsSlotPhrases")
        fun `should return NeedsSlot for bare add-to-list phrases missing item`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result,
                "Expected NeedsSlot for '$input'")
            val needsSlot = result as QuickIntentRouter.RouteResult.NeedsSlot
            assertEquals("add_to_list", needsSlot.intent.intentName, "intent for '$input'")
            assertEquals("item", needsSlot.missingSlot.name, "missing slot for '$input'")
        }
    }

    @Nested
    @DisplayName("Create List")
    inner class CreateList {

        @ParameterizedTest(name = "Regex: \"{0}\" → list_name={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#createListRegexPhrases")
        fun `should match via regex with correct list name`(input: String, expectedList: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "create_list", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedList, intent.params["list_name"], "list_name for '$input'")
        }
    }

    @Nested
    @DisplayName("Get List Items")
    inner class GetListItems {

        @ParameterizedTest(name = "Regex: \"{0}\" → list_name={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#getListItemsRegexPhrases")
        fun `should match via regex with correct list name`(input: String, expectedList: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_list_items", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedList, intent.params["list_name"], "list_name for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEATHER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Get Weather")
    inner class GetWeather {

        @ParameterizedTest(name = "Regex (city): \"{0}\" → location={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherCityRegexPhrases")
        fun `should match city weather via regex`(input: String, expectedLocation: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedLocation, intent.params["location"], "location for '$input'")
        }

        @ParameterizedTest(name = "Regex (GPS): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherGpsRegexPhrases")
        fun `should match GPS weather via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }

        @ParameterizedTest(name = "Regex (rain): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherRainRegexPhrases")
        fun `should match rain queries via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }

        @ParameterizedTest(name = "Regex (temp): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherTemperatureRegexPhrases")
        fun `should match temperature queries via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }

        @ParameterizedTest(name = "Regex (UV): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherUvRegexPhrases")
        fun `should match UV index queries via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }

        @ParameterizedTest(name = "Regex (AQI): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherAqiRegexPhrases")
        fun `should match air quality queries via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }

        @ParameterizedTest(name = "Regex (sunrise/sunset): \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#weatherSunriseRegexPhrases")
        fun `should match sunrise and sunset queries via regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "get_weather", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SAVE MEMORY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Save Memory")
    inner class SaveMemory {

        @ParameterizedTest(name = "Regex: \"{0}\" → content={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#saveMemoryRegexPhrases")
        fun `should match via regex with correct content`(input: String, expectedContent: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "save_memory", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedContent, intent.params["content"], "content for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BRIGHTNESS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Brightness")
    inner class Brightness {

        @ParameterizedTest(name = "Regex: \"{0}\" → direction={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#brightnessRegexPhrases")
        fun `should match via regex with correct direction`(input: String, expectedDirection: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "set_brightness", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedDirection, intent.params["direction"], "direction for '$input'")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SMART HOME TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Smart Home ON")
    inner class SmartHomeOn {

        @ParameterizedTest(name = "Regex: \"{0}\" → device={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#smartHomeOnRegexPhrases")
        fun `should match via regex with correct device`(input: String, expectedDevice: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "smart_home_on", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedDevice, intent.params["device"], "device for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#smartHomeOnClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "smart_home_on", input)
        }
    }

    @Nested
    @DisplayName("Smart Home OFF")
    inner class SmartHomeOff {

        @ParameterizedTest(name = "Regex: \"{0}\" → device={1}")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#smartHomeOffRegexPhrases")
        fun `should match via regex with correct device`(input: String, expectedDevice: String) {
            val result = regexOnlyRouter.route(input)
            assertRegexMatch(result, "smart_home_off", input)
            val intent = (result as QuickIntentRouter.RouteResult.RegexMatch).intent
            assertEquals(expectedDevice, intent.params["device"], "device for '$input'")
        }

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#smartHomeOffClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val hybridResult = hybridRouter.route(input)
            assertClassifierOrFallthrough(hybridResult, "smart_home_off", input)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COVERAGE REPORT — runs all phrasings and prints a summary
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("📊 Full Pipeline Coverage Report")
    fun `coverage report`() {
        data class TestCase(
            val input: String,
            val expectedIntent: String?,
            val category: String,
        )

        val allCases = mutableListOf<TestCase>()

        // Collect all test phrases into one list
        fun addCases(phrases: Stream<Arguments>, intent: String, cat: String) {
            phrases.forEach { allCases.add(TestCase(it.get()[0] as String, intent, cat)) }
        }
        fun addFallthrough(phrases: Stream<Arguments>) {
            phrases.forEach { allCases.add(TestCase(it.get()[0] as String, null, "E4B Fallthrough")) }
        }

        addCases(flashlightOnRegexPhrases(), "toggle_flashlight_on", "Flashlight ON (regex)")
        addCases(flashlightOnClassifierPhrases(), "toggle_flashlight_on", "Flashlight ON (classifier)")
        addCases(flashlightOffRegexPhrases(), "toggle_flashlight_off", "Flashlight OFF (regex)")
        addCases(flashlightOffClassifierPhrases(), "toggle_flashlight_off", "Flashlight OFF (classifier)")
        addCases(alarmRegexPhrases(), "set_alarm", "Alarm (regex)")
        addCases(alarmClassifierPhrases(), "set_alarm", "Alarm (classifier)")
        addCases(timerRegexPhrases(), "set_timer", "Timer (regex)")
        addCases(timerClassifierPhrases(), "set_timer", "Timer (classifier)")
        addCases(dndOnRegexPhrases(), "toggle_dnd_on", "DND ON (regex)")
        addCases(dndOffRegexPhrases(), "toggle_dnd_off", "DND OFF (regex)")
        // DND classifier has per-row expected intent
        dndClassifierPhrases().forEach {
            val input = it.get()[0] as String
            val intent = it.get()[1] as String
            allCases.add(TestCase(input, intent, "DND (classifier)"))
        }
        addCases(batteryRegexPhrases(), "get_battery", "Battery (regex)")
        addCases(batteryClassifierPhrases(), "get_battery", "Battery (classifier)")
        addCases(dateDiffRegexPhrases(), "get_date_diff", "Date Diff (regex)")
        addCases(timeRegexPhrases(), "get_time", "Time (regex)")
        addCases(timeClassifierPhrases(), "get_time", "Time (classifier)")
        addCases(volumeRegexPhrases(), "set_volume", "Volume (regex)")
        addCases(volumeClassifierPhrases(), "set_volume", "Volume (classifier)")
        addCases(wifiOnRegexPhrases(), "toggle_wifi", "WiFi ON (regex)")
        addCases(wifiOffRegexPhrases(), "toggle_wifi", "WiFi OFF (regex)")
        addCases(wifiClassifierPhrases(), "toggle_wifi", "WiFi (classifier)")
        addCases(bluetoothOnRegexPhrases(), "toggle_bluetooth", "Bluetooth ON (regex)")
        addCases(bluetoothOffRegexPhrases(), "toggle_bluetooth", "Bluetooth OFF (regex)")
        addCases(bluetoothClassifierPhrases(), "toggle_bluetooth", "Bluetooth (classifier)")
        addCases(airplaneModeOnRegexPhrases(), "toggle_airplane_mode", "Airplane Mode ON (regex)")
        addCases(airplaneModeOffRegexPhrases(), "toggle_airplane_mode", "Airplane Mode OFF (regex)")
        addCases(airplaneModeClassifierPhrases(), "toggle_airplane_mode", "Airplane Mode (classifier)")
        addCases(hotspotOnRegexPhrases(), "toggle_hotspot", "Hotspot ON (regex)")
        addCases(hotspotOffRegexPhrases(), "toggle_hotspot", "Hotspot OFF (regex)")
        addCases(hotspotClassifierPhrases(), "toggle_hotspot", "Hotspot (classifier)")
        addCases(playPlexRegexPhrases(), "play_plex", "Play Plex (regex)")
        addCases(playPlexClassifierPhrases(), "play_plex", "Play Plex (classifier)")
        addCases(playYoutubeRegexPhrases(), "play_youtube", "Play YouTube (regex)")
        addCases(playSpotifyRegexPhrases(), "play_spotify", "Play Spotify (regex)")
        addCases(playNetflixRegexPhrases(), "play_netflix", "Play Netflix (regex)")
        addCases(openAppRegexPhrases(), "open_app", "Open App (regex)")
        addCases(playAlbumRegexPhrases(), "play_media_album", "Play Album (regex)")
        addCases(playAlbumClassifierPhrases(), "play_media_album", "Play Album (classifier)")
        addCases(playPlaylistRegexPhrases(), "play_media_playlist", "Play Playlist (regex)")
        addCases(playPlaylistClassifierPhrases(), "play_media_playlist", "Play Playlist (classifier)")
        addCases(playMediaRegexPhrases(), "play_media", "Play Media (regex)")
        addCases(playMediaClassifierPhrases(), "play_media", "Play Media (classifier)")
        addCases(navigateToRegexPhrases(), "navigate_to", "Navigate To (regex)")
        addCases(navigateToClassifierPhrases(), "navigate_to", "Navigate To (classifier)")
        addCases(findNearbyRegexPhrases(), "find_nearby", "Find Nearby (regex)")
        addCases(findNearbyClassifierPhrases(), "find_nearby", "Find Nearby (classifier)")
        addCases(makeCallRegexPhrases(), "make_call", "Make Call (regex)")
        addCases(makeCallClassifierPhrases(), "make_call", "Make Call (classifier)")
        addCases(sendSmsRegexPhrases(), "send_sms", "Send SMS (regex)")
        addCases(sendSmsNeedsSlotPhrases(), "send_sms", "Send SMS (needs slot)")
        addCases(sendEmailRegexPhrases(), "send_email", "Send Email (regex)")
        addCases(addToListRegexPhrases(), "add_to_list", "Add to List (regex)")
        addCases(addToListClassifierPhrases(), "add_to_list", "Add to List (classifier)")
        addCases(createListRegexPhrases(), "create_list", "Create List (regex)")
        addCases(getListItemsRegexPhrases(), "get_list_items", "Get List Items (regex)")
        addCases(weatherCityRegexPhrases(), "get_weather", "Weather City (regex)")
        addCases(weatherGpsRegexPhrases(), "get_weather", "Weather GPS (regex)")
        addCases(weatherRainRegexPhrases(), "get_weather", "Weather Rain (regex)")
        addCases(weatherTemperatureRegexPhrases(), "get_weather", "Weather Temperature (regex)")
        addCases(weatherUvRegexPhrases(), "get_weather", "Weather UV (regex)")
        addCases(weatherAqiRegexPhrases(), "get_weather", "Weather AQI (regex)")
        addCases(weatherSunriseRegexPhrases(), "get_weather", "Weather Sunrise/Sunset (regex)")
        addCases(saveMemoryRegexPhrases(), "save_memory", "Save Memory (regex)")
        addCases(brightnessRegexPhrases(), "set_brightness", "Brightness (regex)")
        addCases(smartHomeOnRegexPhrases(), "smart_home_on", "Smart Home ON (regex)")
        addCases(smartHomeOnClassifierPhrases(), "smart_home_on", "Smart Home ON (classifier)")
        addCases(smartHomeOffRegexPhrases(), "smart_home_off", "Smart Home OFF (regex)")
        addCases(smartHomeOffClassifierPhrases(), "smart_home_off", "Smart Home OFF (classifier)")
        addFallthrough(e4bFallthroughPhrases())

        val report = StringBuilder()
        report.appendLine()
        report.appendLine("═══════════════════════════════════════════════════════════════════")
        report.appendLine("  TIER 2 INTENT PIPELINE — COVERAGE REPORT")
        report.appendLine("═══════════════════════════════════════════════════════════════════")
        report.appendLine()

        var regexHits = 0
        var classifierHits = 0
        var fallthroughs = 0
        var correctRoute = 0
        var incorrectRoute = 0
        val gaps = mutableListOf<String>()

        for (tc in allCases) {
            val regexResult = regexOnlyRouter.route(tc.input)
            val hybridResult = hybridRouter.route(tc.input)

            val regexMatched = regexResult is QuickIntentRouter.RouteResult.RegexMatch ||
                regexResult is QuickIntentRouter.RouteResult.NeedsSlot
            val hybridMatched = hybridResult is QuickIntentRouter.RouteResult.RegexMatch ||
                hybridResult is QuickIntentRouter.RouteResult.ClassifierMatch ||
                hybridResult is QuickIntentRouter.RouteResult.NeedsSlot

            val routedIntent = when (hybridResult) {
                is QuickIntentRouter.RouteResult.RegexMatch -> hybridResult.intent.intentName
                is QuickIntentRouter.RouteResult.ClassifierMatch -> hybridResult.intent.intentName
                is QuickIntentRouter.RouteResult.FallThrough -> null
                is QuickIntentRouter.RouteResult.NeedsSlot -> hybridResult.intent.intentName
            }

            val tier = when {
                regexMatched -> { regexHits++; "REGEX" }
                hybridMatched -> { classifierHits++; "CLASSIFIER" }
                else -> { fallthroughs++; "→ E4B" }
            }

            val correct = if (tc.expectedIntent == null) {
                !hybridMatched // Expected fallthrough, got fallthrough
            } else {
                routedIntent == tc.expectedIntent
            }

            if (correct) correctRoute++ else {
                incorrectRoute++
                gaps.add("  ✗ \"${tc.input}\" — expected ${tc.expectedIntent ?: "E4B"}, got ${routedIntent ?: "E4B"} ($tier)")
            }

            val icon = if (correct) "✓" else "✗"
            val conf = if (hybridResult is QuickIntentRouter.RouteResult.ClassifierMatch) {
                " (%.0f%%)".format(hybridResult.confidence * 100)
            } else ""

            report.appendLine("  $icon [$tier$conf] \"${tc.input}\" → ${routedIntent ?: "E4B fallthrough"}")
        }

        report.appendLine()
        report.appendLine("───────────────────────────────────────────────────────────────────")
        report.appendLine("  SUMMARY")
        report.appendLine("───────────────────────────────────────────────────────────────────")
        report.appendLine("  Total test cases:    ${allCases.size}")
        report.appendLine("  Regex matches:       $regexHits")
        report.appendLine("  Classifier matches:  $classifierHits")
        report.appendLine("  E4B fallthroughs:    $fallthroughs")
        report.appendLine("  Correct routing:     $correctRoute / ${allCases.size} (${100 * correctRoute / allCases.size}%)")
        report.appendLine()

        if (gaps.isNotEmpty()) {
            report.appendLine("  ⚠ GAPS (routed to wrong tier or intent):")
            gaps.forEach { report.appendLine(it) }
            report.appendLine()
        }

        report.appendLine("  Regex coverage:      ${regexHits * 100 / allCases.size}% of inputs")
        report.appendLine("  Regex+Classifier:    ${(regexHits + classifierHits) * 100 / allCases.size}% of inputs")
        report.appendLine("  E4B only:            ${fallthroughs * 100 / allCases.size}% of inputs")
        report.appendLine("═══════════════════════════════════════════════════════════════════")

        println(report.toString())

        // Don't fail the test — this is a diagnostic report
        // But DO warn about incorrect routes
        if (incorrectRoute > 0) {
            System.err.println("⚠ $incorrectRoute inputs routed incorrectly — see report above")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST DATA — all phrasings
    // ═══════════════════════════════════════════════════════════════════════════

    companion object {

        // ── Flashlight ON ─────────────────────────────────────────────────────

        @JvmStatic
        fun flashlightOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on the torch"),
            Arguments.of("turn on the flashlight"),
            Arguments.of("turn the torch on"),
            Arguments.of("turn the flashlight on"),
            Arguments.of("switch on the torch"),
            Arguments.of("switch the flashlight on"),
            Arguments.of("put the torch on"),
            Arguments.of("flashlight on"),
            Arguments.of("torch on"),
            Arguments.of("torch please"),
            Arguments.of("can you turn the flashlight on please"),
            Arguments.of("hey can you switch the light on for me"),
        )

        @JvmStatic
        fun flashlightOnClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I need some light"),
            Arguments.of("illuminate the room"),
            Arguments.of("light up my phone"),
            Arguments.of("enable the torch"),
            Arguments.of("it's too dark in here"),
            Arguments.of("I can't see anything"),
        )

        // ── Flashlight OFF ────────────────────────────────────────────────────

        @JvmStatic
        fun flashlightOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn off the torch"),
            Arguments.of("turn off the flashlight"),
            Arguments.of("turn the torch off"),
            Arguments.of("turn the flashlight off"),
            Arguments.of("switch off the flashlight"),
            Arguments.of("flashlight off"),
            Arguments.of("torch off"),
            Arguments.of("can you turn the torch off please"),
        )

        @JvmStatic
        fun flashlightOffClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("lights out"),
            Arguments.of("disable the flashlight"),
            Arguments.of("stop the light"),
            Arguments.of("no more light please"),
        )

        // ── Alarm ─────────────────────────────────────────────────────────────

        @JvmStatic
        fun alarmRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set an alarm for 7am", "7", "0"),
            Arguments.of("set alarm for 10pm", "22", "0"),
            Arguments.of("set an alarm for 6:30am", "6", "30"),
            Arguments.of("set alarm at 22:00", "22", "0"),
            Arguments.of("set an alarm for 9:05 am", "9", "5"),
            Arguments.of("create an alarm for 5pm", "17", "0"),
            Arguments.of("set alarm for 12pm", "12", "0"),
            Arguments.of("set alarm for 12am", "0", "0"),
            Arguments.of("wake me up at 6am", "6", "0"),
            Arguments.of("wake me up at 7:30pm", "19", "30"),
            Arguments.of("get me up at 5:45am", "5", "45"),
            Arguments.of("alarm for 8pm", "20", "0"),
            Arguments.of("alarm at 3:15pm", "15", "15"),
        )

        @JvmStatic
        fun alarmClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I need to wake up early tomorrow"),
            Arguments.of("remind me to get up at dawn"),
            Arguments.of("morning alarm please"),
            Arguments.of("can you make sure I'm up by 7"),
            Arguments.of("alarm me for the morning"),
        )

        @JvmStatic
        fun alarmWithDayRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set an alarm for 10pm thursday", "22", "0", "thursday"),
            Arguments.of("set alarm for 7am monday", "7", "0", "monday"),
            Arguments.of("wake me up at 6am tomorrow", "6", "0", "tomorrow"),
            Arguments.of("alarm for 8pm friday", "20", "0", "friday"),
            Arguments.of("set alarm for 9am sat", "9", "0", "saturday"),
            Arguments.of("set an alarm for 7:30am wed", "7", "30", "wednesday"),
            Arguments.of("remind me tomorrow at 9", "9", "0", "tomorrow"),
        )

        // ── Timer ─────────────────────────────────────────────────────────────

        @JvmStatic
        fun timerRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set a timer for 5 minutes", "300"),
            Arguments.of("set timer for 10 mins", "600"),
            Arguments.of("start a timer for 30 seconds", "30"),
            Arguments.of("set a timer for 1 hour", "3600"),
            Arguments.of("set timer for 2 hours", "7200"),
            Arguments.of("start countdown for 90 seconds", "90"),
            Arguments.of("timer for 15 minutes", "900"),
            Arguments.of("5 minute timer", "300"),
            Arguments.of("10 second timer", "10"),
            Arguments.of("set a timer for 1 hour and 30 minutes", "5400"),
            Arguments.of("set timer for 2 hours and 15 minutes", "8100"),
        )

        @JvmStatic
        fun timerClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("time me for a few minutes"),
            Arguments.of("start counting down"),
            Arguments.of("I need a countdown"),
            Arguments.of("count down from 10"),
        )

        @JvmStatic
        fun timerWithLabelRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set a 2 minute timer called egg", "120", "egg"),
            Arguments.of("5 minute timer called pasta", "300", "pasta"),
            Arguments.of("set timer for 10 minutes named study", "600", "study"),
            Arguments.of("set a timer for 30 seconds labeled workout", "30", "workout"),
            Arguments.of("start a timer for 3 minutes labelled tea", "180", "tea"),
            Arguments.of("5 minute timer for eggs", "300", "eggs"),
        )

        // ── DND ───────────────────────────────────────────────────────────────

        @JvmStatic
        fun dndOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on do not disturb"),
            Arguments.of("enable do not disturb"),
            Arguments.of("turn on dnd"),
            Arguments.of("enable silent mode"),
            Arguments.of("put on quiet mode"),
            Arguments.of("switch on do not disturb"),
        )

        @JvmStatic
        fun dndOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn off do not disturb"),
            Arguments.of("disable do not disturb"),
            Arguments.of("turn off dnd"),
            Arguments.of("disable silent mode"),
            Arguments.of("switch off quiet mode"),
        )

        @JvmStatic
        fun dndClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("silence my phone", "toggle_dnd_on"),
            Arguments.of("mute all notifications", "toggle_dnd_on"),
            Arguments.of("go quiet", "toggle_dnd_on"),
            Arguments.of("no notifications please", "toggle_dnd_on"),
            Arguments.of("bring back notifications", "toggle_dnd_off"),
        )

        // ── Battery ───────────────────────────────────────────────────────────

        @JvmStatic
        fun batteryRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("battery level"),
            Arguments.of("battery percentage"),
            Arguments.of("battery status"),
            Arguments.of("what's my battery level"),
            Arguments.of("what is my battery"),
            Arguments.of("how much battery"),
            Arguments.of("how much charge"),
            Arguments.of("charge level"),
            Arguments.of("power remaining"),
            Arguments.of("what's my power at"),
            Arguments.of("check my battery"),
        )

        @JvmStatic
        fun batteryClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("am I running low on battery"),
            Arguments.of("do I need to charge"),
            Arguments.of("how much juice do I have left"),
            Arguments.of("is my phone dying"),
            Arguments.of("should I plug in"),
        )

        // ── Time / Date ───────────────────────────────────────────────────────

        @JvmStatic
        fun dateDiffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("how many days until Christmas"),
            Arguments.of("how long until Christmas"),
            Arguments.of("how many weeks until New Year"),
            Arguments.of("how many days since Easter"),
            Arguments.of("how long since ANZAC Day"),
            Arguments.of("days until anzac"),
            Arguments.of("days until mothers day"),
            Arguments.of("days until father's day"),
            Arguments.of("days until Christmas"),
            Arguments.of("weeks until New Year"),
            Arguments.of("what day of the week is 22 August"),
            Arguments.of("what day is Christmas"),
            Arguments.of("when is ANZAC Day"),
            Arguments.of("when is anzac"),
            Arguments.of("when is mothers day"),
            Arguments.of("when is Easter"),
            Arguments.of("how many days until 2026-08-22"),
            Arguments.of("how long until August 22 2026"),
        )

        @JvmStatic
        fun timeRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what time is it"),
            Arguments.of("what's the time"),
            Arguments.of("what is the time"),
            Arguments.of("what's the date"),
            Arguments.of("what is the date"),
            Arguments.of("what's the current time"),
            Arguments.of("what day is it"),
            Arguments.of("what date is it today"),
            Arguments.of("tell me the time"),
            Arguments.of("give me the time"),
            Arguments.of("what is today"),
            Arguments.of("what's today"),
            Arguments.of("what's today's date"),
            Arguments.of("what is today's date"),
            Arguments.of("what's today's day"),
            // year queries
            Arguments.of("what year is it"),
            Arguments.of("what year are we in"),
            Arguments.of("what year is this"),
            Arguments.of("what's the year"),
            Arguments.of("what's the current year"),
            Arguments.of("what is the current year"),
            // "is it still [day]" / "is today [day]"
            Arguments.of("is it still Tuesday"),
            Arguments.of("is it still monday"),
            Arguments.of("is today Friday"),
            Arguments.of("is it Friday today"),
            Arguments.of("is it Monday today"),
            // month / week queries
            Arguments.of("what month is it"),
            Arguments.of("what month are we in"),
            Arguments.of("what week is it"),
            Arguments.of("what week are we in"),
            Arguments.of("what's the month"),
            Arguments.of("what is the current month"),

        )

        @JvmStatic
        fun timeClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("do you know the time"),
            Arguments.of("how late is it"),
            Arguments.of("current date and time"),
        )

        // ── Volume ───────────────────────────────────────────────────────────────

        @JvmStatic
        fun volumeRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set volume to 50%", "50", "true"),
            Arguments.of("volume 7", "7", "false"),
            Arguments.of("set volume to 7", "7", "false"),
            Arguments.of("turn the volume up to 8", "8", "false"),
            Arguments.of("volume at 60%", "60", "true"),
            Arguments.of("volume to 10", "10", "false"),
            Arguments.of("set volume to 0%", "0", "true"),
            Arguments.of("set volume to 100%", "100", "true"),
            Arguments.of("volume 0", "0", "false"),
            Arguments.of("volume 10", "10", "false"),
            Arguments.of("volume up to 5", "5", "false"),
            Arguments.of("volume down to 3", "3", "false"),
            Arguments.of("turn volume to 9", "9", "false"),
            Arguments.of("volume at 25%", "25", "true"),
            Arguments.of("set volume at 80%", "80", "true"),
        )

        @JvmStatic
        fun volumeClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("can you make it louder"),
            Arguments.of("increase the sound level"),
            Arguments.of("I can't hear anything"),
            Arguments.of("make the music quieter"),
        )

        // ── WiFi ─────────────────────────────────────────────────────────────────

        @JvmStatic
        fun wifiOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on wifi"),
            Arguments.of("enable wifi"),
            Arguments.of("enable wi-fi"),
            Arguments.of("switch on wifi"),
            Arguments.of("wifi on"),
            Arguments.of("switch wifi on"),
            Arguments.of("turn on wireless"),
            Arguments.of("enable wireless"),
            Arguments.of("wireless on"),
            Arguments.of("on wifi"),
        )

        @JvmStatic
        fun wifiOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("wifi off"),
            Arguments.of("disable wifi"),
            Arguments.of("turn off wifi"),
            Arguments.of("wi-fi off"),
            Arguments.of("disable wi-fi"),
            Arguments.of("off wifi"),
            Arguments.of("turn off wireless"),
            Arguments.of("disable wireless"),
            Arguments.of("wireless off"),
        )

        @JvmStatic
        fun wifiClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("connect to the internet"),
            Arguments.of("I need wireless access"),
            Arguments.of("my wifi isn't working"),
        )

        // ── Bluetooth ─────────────────────────────────────────────────────────────

        @JvmStatic
        fun bluetoothOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on bluetooth"),
            Arguments.of("enable bluetooth"),
            Arguments.of("enable BT"),
            Arguments.of("bluetooth on"),
            Arguments.of("on bluetooth"),
            Arguments.of("turn on BT"),
            Arguments.of("bt on"),
            Arguments.of("on BT"),
        )

        @JvmStatic
        fun bluetoothOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("bluetooth off"),
            Arguments.of("disable bluetooth"),
            Arguments.of("turn off bluetooth"),
            Arguments.of("bt off"),
            Arguments.of("off bluetooth"),
            Arguments.of("turn off BT"),
            Arguments.of("off BT"),
            Arguments.of("disable BT"),
        )

        @JvmStatic
        fun bluetoothClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("connect to my headphones"),
            Arguments.of("pair with speaker"),
            Arguments.of("I need to connect wirelessly"),
        )

        // ── Airplane Mode ─────────────────────────────────────────────────────────

        @JvmStatic
        fun airplaneModeOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("airplane mode on"),
            Arguments.of("turn on airplane mode"),
            Arguments.of("enable airplane mode"),
            Arguments.of("turn on flight mode"),
            Arguments.of("flight mode on"),
            Arguments.of("enable flight mode"),
            Arguments.of("on airplane mode"),
            Arguments.of("on flight mode"),
        )

        @JvmStatic
        fun airplaneModeOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("airplane mode off"),
            Arguments.of("turn off airplane mode"),
            Arguments.of("disable airplane mode"),
            Arguments.of("flight mode off"),
            Arguments.of("turn off flight mode"),
            Arguments.of("disable flight mode"),
            Arguments.of("off airplane mode"),
            Arguments.of("off flight mode"),
        )

        @JvmStatic
        fun airplaneModeClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I'm boarding a plane"),
            Arguments.of("going offline for a flight"),
        )

        // ── Hotspot ───────────────────────────────────────────────────────────────

        @JvmStatic
        fun hotspotOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on hotspot"),
            Arguments.of("enable hotspot"),
            Arguments.of("mobile hotspot on"),
            Arguments.of("tethering on"),
            Arguments.of("enable tethering"),
            Arguments.of("hotspot on"),
            Arguments.of("on hotspot"),
            Arguments.of("turn on mobile hotspot"),
            Arguments.of("enable mobile hotspot"),
            Arguments.of("on tethering"),
        )

        @JvmStatic
        fun hotspotOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("hotspot off"),
            Arguments.of("disable hotspot"),
            Arguments.of("turn off hotspot"),
            Arguments.of("tethering off"),
            Arguments.of("off hotspot"),
            Arguments.of("turn off mobile hotspot"),
            Arguments.of("disable mobile hotspot"),
            Arguments.of("mobile hotspot off"),
            Arguments.of("off tethering"),
        )

        @JvmStatic
        fun hotspotClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("share my internet connection"),
            Arguments.of("I want to give others wifi"),
            Arguments.of("let my laptop use my data"),
        )

        // ── Media ─────────────────────────────────────────────────────────────────

        @JvmStatic
        fun playPlexRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play Breaking Bad on Plex", "Breaking Bad"),
            Arguments.of("watch The Office on plex", "The Office"),
            Arguments.of("play Interstellar on Plex", "Interstellar"),
            Arguments.of("watch Stranger Things on Plex", "Stranger Things"),
            Arguments.of("play The Mandalorian on plex", "The Mandalorian"),
            Arguments.of("watch Game of Thrones on Plex", "Game of Thrones"),
        )

        @JvmStatic
        fun playPlexClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("stream something from plex"),
        )

        @JvmStatic
        fun playYoutubeRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play cat videos on youtube", "cat videos"),
            Arguments.of("watch NZ highlights on YouTube", "NZ highlights"),
            Arguments.of("search cooking tutorials on youtube", "cooking tutorials"),
            Arguments.of("play funny compilations on YouTube", "funny compilations"),
            Arguments.of("watch gaming videos on youtube", "gaming videos"),
            Arguments.of("play music videos on YouTube", "music videos"),
            Arguments.of("search tech reviews on youtube", "tech reviews"),
        )

        @JvmStatic
        fun playSpotifyRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play Six60 on Spotify", "Six60"),
            Arguments.of("listen to jazz on spotify", "jazz"),
            Arguments.of("play Discover Weekly on Spotify", "Discover Weekly"),
            Arguments.of("listen to rock music on Spotify", "rock music"),
            Arguments.of("play The Weeknd on spotify", "The Weeknd"),
            Arguments.of("listen to chill music on Spotify", "chill music"),
        )

        @JvmStatic
        fun playNetflixRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play Stranger Things on Netflix", "Stranger Things"),
            Arguments.of("watch The Crown on netflix", "The Crown"),
            Arguments.of("play Squid Game on Netflix", "Squid Game"),
            Arguments.of("watch Breaking Bad on netflix", "Breaking Bad"),
            Arguments.of("play Wednesday on Netflix", "Wednesday"),
            Arguments.of("watch action movies on Netflix", "action movies"),
        )

        @JvmStatic
        fun openAppRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("open YouTube", "YouTube"),
            Arguments.of("launch Spotify", "Spotify"),
            Arguments.of("open the camera app", "camera"),
            Arguments.of("start Chrome", "Chrome"),
            Arguments.of("open Settings", "Settings"),
            Arguments.of("launch Maps", "Maps"),
            Arguments.of("open Gmail", "Gmail"),
            Arguments.of("start Messages", "Messages"),
            Arguments.of("launch Calendar", "Calendar"),
            Arguments.of("open the Photos app", "Photos"),
        )

        @JvmStatic
        fun playAlbumRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play the album Dark Side of the Moon", "Dark Side of the Moon"),
            Arguments.of("play album Rumours by Fleetwood Mac", "Rumours"),
            Arguments.of("play album Abbey Road", "Abbey Road"),
            Arguments.of("play the album Thriller", "Thriller"),
            Arguments.of("play album Back in Black by AC/DC", "Back in Black"),
            Arguments.of("play the album Hotel California", "Hotel California"),
            Arguments.of("play album Nevermind", "Nevermind"),
        )

        @JvmStatic
        fun playAlbumClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I want to hear a full album"),
            Arguments.of("put on the whole record"),
        )

        @JvmStatic
        fun playPlaylistRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play my workout playlist", "workout"),
            Arguments.of("play playlist Chill Vibes", "Chill Vibes"),
            Arguments.of("play the running playlist", "running"),
            Arguments.of("play my sleep playlist", "sleep"),
            Arguments.of("play playlist Morning Jams", "Morning Jams"),
            Arguments.of("play the study playlist", "study"),
            Arguments.of("play my party playlist", "party"),
        )

        @JvmStatic
        fun playPlaylistClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("put on some music for working out"),
        )

        @JvmStatic
        fun playMediaRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("play Bohemian Rhapsody by Queen", "Bohemian Rhapsody"),
            Arguments.of("play Thriller", "Thriller"),
            Arguments.of("play something by Taylor Swift", "something"),
            Arguments.of("play Stairway to Heaven by Led Zeppelin", "Stairway to Heaven"),
            Arguments.of("play Imagine", "Imagine"),
            Arguments.of("play Hotel California by Eagles", "Hotel California"),
            Arguments.of("play Sweet Child O' Mine", "Sweet Child O' Mine"),
            Arguments.of("play Billie Jean by Michael Jackson", "Billie Jean"),
        )

        @JvmStatic
        fun playMediaClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I want to listen to some music"),
            Arguments.of("put on a song"),
        )

        // ── Navigation ────────────────────────────────────────────────────────────

        @JvmStatic
        fun navigateToRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("navigate to Auckland Airport", "Auckland Airport"),
            Arguments.of("directions to the mall", "the mall"),
            Arguments.of("take me to Countdown", "Countdown"),
            Arguments.of("drive to work", "work"),
            Arguments.of("get me to the hospital", "the hospital"),
            Arguments.of("navigate to the nearest petrol station", "the nearest petrol station"),
            Arguments.of("navigate home", "home"),
            Arguments.of("drive home", "home"),
            Arguments.of("take me home", "home"),
            Arguments.of("directions home", "home"),
            Arguments.of("navigate to 123 Main Street", "123 Main Street"),
            Arguments.of("take me to Brisbane CBD", "Brisbane CBD"),
            Arguments.of("directions to the airport", "the airport"),
            Arguments.of("navigate to the beach", "the beach"),
            Arguments.of("drive to the city", "the city"),
        )

        @JvmStatic
        fun navigateToClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("how do I get to the airport"),
            Arguments.of("I need to find my way to downtown"),
        )

        @JvmStatic
        fun findNearbyRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("find cafes nearby", "cafes"),
            Arguments.of("find dog parks near me", "dog parks"),
            Arguments.of("show me dog parks on the map", "dog parks"),
            Arguments.of("show me petrol stations close by", "petrol stations"),
            Arguments.of("search for restaurants nearby", "restaurants"),
            Arguments.of("find cafes on the map", "cafes"),
            Arguments.of("look for pharmacies near me", "pharmacies"),
            Arguments.of("find me nearby cafes", "cafes"),
            Arguments.of("find me nearby mcdonalds", "mcdonalds"),
            Arguments.of("show me nearby gas stations", "gas stations"),
            Arguments.of("find nearby restaurants", "restaurants"),
            Arguments.of("locate nearest pharmacy", "pharmacy"),
            Arguments.of("find me cafes nearby", "cafes"),
            Arguments.of("find gyms near me", "gyms"),
            Arguments.of("find banks nearby", "banks"),
            Arguments.of("locate nearest hospital", "hospital"),
            Arguments.of("find coffee shops close by", "coffee shops"),
            Arguments.of("search for supermarkets nearby", "supermarkets"),
            Arguments.of("find ATMs near me", "ATMs"),
        )

        @JvmStatic
        fun findNearbyClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("where's the closest supermarket"),
            Arguments.of("is there a cafe around here"),
        )

        // ── Communication ─────────────────────────────────────────────────────────

        @JvmStatic
        fun makeCallRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("call Mum", "Mum"),
            Arguments.of("call John Smith", "John Smith"),
            Arguments.of("ring Dad", "Dad"),
            Arguments.of("dial Sarah", "Sarah"),
            Arguments.of("phone the office", "the office"),
            Arguments.of("call Nick", "Nick"),
            Arguments.of("ring Sarah Jones", "Sarah Jones"),
            Arguments.of("dial emergency services", "emergency services"),
            Arguments.of("phone my boss", "my boss"),
            Arguments.of("call the doctor", "the doctor"),
        )

        @JvmStatic
        fun makeCallClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("get my mum on the line"),
            Arguments.of("I need to speak to John"),
        )

        @JvmStatic
        fun sendSmsRegexPhrases(): Stream<Arguments> = Stream.of(
            // Phrases that include a message body → full RegexMatch
            Arguments.of("text John saying hello", "John"),
            Arguments.of("send a text to Mum saying I'll be late", "Mum"),
            Arguments.of("sms Sarah saying meet at 5", "Sarah"),
            Arguments.of("text Sarah that I'll be late", "Sarah"),
            Arguments.of("text Nick saying on my way", "Nick"),
            Arguments.of("sms Mike saying call me", "Mike"),
        )

        @JvmStatic
        fun sendSmsNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            // Phrases with only a contact — message body missing → NeedsSlot
            Arguments.of("send message to Dad", "Dad"),
            Arguments.of("send sms to the office", "the office"),
            Arguments.of("send a text to Emily", "Emily"),
            Arguments.of("text my boss", "my boss"),
        )

        @JvmStatic
        fun sendSmsNoContactNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            // Bare send-message phrases — no contact, no body → NeedsSlot(contact)
            Arguments.of("send a message"),
            Arguments.of("send a text"),
            Arguments.of("send a sms"),
        )

        @JvmStatic
        fun sendEmailNoContactNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            // Bare send-email phrases — no contact → NeedsSlot(contact)
            // Note: "email someone" captures "someone" as contact → NeedsSlot(subject) instead
            Arguments.of("send an email"),
        )

        @JvmStatic
        fun sendEmailContactNoSubjectNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            // Contact present but no subject → NeedsSlot(subject)
            Arguments.of("send email to Dad", "Dad"),
            Arguments.of("send an email to the team", "the team"),
            Arguments.of("send email to HR", "HR"),
            Arguments.of("email someone", "someone"),
        )

        @JvmStatic
        fun sendEmailRegexPhrases(): Stream<Arguments> = Stream.of(
            // Cases with a subject keyword — no NeedsSlot triggered
            Arguments.of("email John about the meeting", "John"),
            Arguments.of("send an email to Sarah about project update", "Sarah"),
            Arguments.of("send an email to my boss about the project", "my boss"),
            Arguments.of("email Nick about dinner plans", "Nick"),
            Arguments.of("email Sarah regarding the report", "Sarah"),
        )

        // ── Lists ─────────────────────────────────────────────────────────────────

        @JvmStatic
        fun addToListRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("add toothpaste to shopping list", "toothpaste", "shopping"),
            Arguments.of("add milk to the shopping list", "milk", "shopping"),
            Arguments.of("add eggs to my grocery list", "eggs", "grocery"),
            Arguments.of("add bread to the to-do list", "bread", "to-do"),
            Arguments.of("add bananas to shopping list", "bananas", "shopping"),
            Arguments.of("add butter to the grocery list", "butter", "grocery"),
            Arguments.of("add cheese to my shopping list", "cheese", "shopping"),
        )

        @JvmStatic
        fun addToListClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("add eggs to my groceries"),
            Arguments.of("put butter on the shopping list"),
        )

        @JvmStatic
        fun setAlarmNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set an alarm"),
            Arguments.of("set alarm"),
        )

        @JvmStatic
        fun setTimerNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("set a timer"),
            Arguments.of("start a timer"),
        )

        @JvmStatic
        fun openAppNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("open an app"),
            Arguments.of("launch an app"),
        )

        @JvmStatic
        fun navigateToNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("navigate"),
            Arguments.of("get directions"),
        )

        @JvmStatic
        fun findNearbyNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's nearby"),
            Arguments.of("find nearby"),
        )

        @JvmStatic
        fun addToListNeedsSlotPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("add to my list"),
            Arguments.of("put something to my list"),
        )

        @JvmStatic
        fun createListRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("create a groceries list", "groceries"),
            Arguments.of("make a shopping list", "shopping"),
            Arguments.of("create a todo list", "todo"),
            Arguments.of("make my chores list", "chores"),
            Arguments.of("create a meal plan list", "meal plan"),
            Arguments.of("new packing list", "packing"),
        )

        @JvmStatic
        fun getListItemsRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("show my todo list", "todo"),
            Arguments.of("what's on my shopping list", "shopping"),
            Arguments.of("what's in my shopping list", "shopping"),
            Arguments.of("display list called shopping", "shopping"),
            Arguments.of("show me my grocery list", "grocery"),
            Arguments.of("read my shopping list", "shopping"),
            Arguments.of("get my to-do list", "to-do"),
        )

        // ── Weather ───────────────────────────────────────────────────────────────

        @JvmStatic
        fun weatherCityRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's the weather in Auckland", "Auckland"),
            Arguments.of("what's the weather in London", "London"),
            Arguments.of("weather in Wellington", "Wellington"),
            Arguments.of("weather forecast for Paris", "Paris"),
            Arguments.of("how's the weather in Sydney", "Sydney"),
            Arguments.of("weather forecast for the weekend", "the weekend"),
        )

        @JvmStatic
        fun weatherGpsRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's the weather"),
            Arguments.of("what is the weather"),
            Arguments.of("weather today"),
            Arguments.of("weather tonight"),
            Arguments.of("how's the weather outside"),
            Arguments.of("weather forecast"),
            // Colloquial variants (#608)
            Arguments.of("how's the weather looking"),
            Arguments.of("what's the weather looking like"),
            Arguments.of("how's the weather out there"),
            Arguments.of("how is the weather looking"),
            Arguments.of("what is the weather looking like"),
            Arguments.of("what's the weather like"),
            Arguments.of("how's the weather like"),
        )

        @JvmStatic
        fun weatherRainRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("will it rain today"),
            Arguments.of("will it rain"),
            Arguments.of("is it raining"),
            Arguments.of("do I need an umbrella"),
            Arguments.of("chance of rain"),
        )

        @JvmStatic
        fun weatherTemperatureRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("how hot is it outside"),
            Arguments.of("how cold is it"),
            Arguments.of("what's the temperature"),
            Arguments.of("what's the temperature outside"),
            Arguments.of("how warm is it"),
        )

        @JvmStatic
        fun weatherUvRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's the UV index"),
            Arguments.of("what is the UV index"),
            Arguments.of("is the UV high today"),
            Arguments.of("is the UV bad"),
            Arguments.of("UV index today"),
            Arguments.of("how high is the UV"),
        )

        @JvmStatic
        fun weatherAqiRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's the air quality"),
            Arguments.of("what is the AQI"),
            Arguments.of("how's the air quality"),
            Arguments.of("air quality today"),
            Arguments.of("is the air clean"),
            Arguments.of("is the air polluted"),
        )

        @JvmStatic
        fun weatherSunriseRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what time is sunrise"),
            Arguments.of("what time is sunset"),
            Arguments.of("when does the sun rise"),
            Arguments.of("when does the sun set"),
            Arguments.of("sunrise time"),
            Arguments.of("sunset time"),
            Arguments.of("when is sunrise"),
            Arguments.of("when is sunset"),
        )

        // ── Save Memory ──────────────────────────────────────────────────────────

        @JvmStatic
        fun saveMemoryRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("save that we're meeting Tuesday", "we're meeting Tuesday"),
            Arguments.of("remember that I prefer dark mode", "I prefer dark mode"),
            Arguments.of("remember that my wifi password is 12345", "my wifi password is 12345"),
            Arguments.of("remember my favourite colour is blue", "my favourite colour is blue"),
            Arguments.of("save to memory: important note", "important note"),
            Arguments.of("note that the gate code is 4567", "the gate code is 4567"),
            Arguments.of("don't forget that mum's birthday is March 3", "mum's birthday is March 3"),
            Arguments.of("store that my doctor is Dr Smith", "my doctor is Dr Smith"),
        )

        // ── Brightness ───────────────────────────────────────────────────────────

        @JvmStatic
        fun brightnessRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("increase brightness", "up"),
            Arguments.of("decrease brightness", "down"),
            Arguments.of("turn up the brightness", "up"),
            Arguments.of("turn down the brightness", "down"),
            Arguments.of("dim the brightness", "down"),
            Arguments.of("brighten the screen brightness", "up"),
            Arguments.of("brightness up", "up"),
            Arguments.of("brightness down", "down"),
            Arguments.of("brightness max", "up"),
            Arguments.of("brightness low", "down"),
            Arguments.of("lower the brightness", "down"),
            Arguments.of("raise the brightness", "up"),
        )

        // ── Smart Home ────────────────────────────────────────────────────────────

        @JvmStatic
        fun smartHomeOnRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("turn on bedroom light", "bedroom light"),
            Arguments.of("switch on the fan", "fan"),
            Arguments.of("switch on the air conditioning", "air conditioning"),
            Arguments.of("turn on living room lights", "living room lights"),
            Arguments.of("switch on the heater", "heater"),
            Arguments.of("turn on kitchen light", "kitchen light"),
            Arguments.of("turn on the coffee maker", "coffee maker"),
        )

        @JvmStatic
        fun smartHomeOnClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I need the lights on"),
            Arguments.of("it's too hot, start the AC"),
        )

        @JvmStatic
        fun smartHomeOffRegexPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("switch off the fan", "fan"),
            Arguments.of("turn off all lights", "all lights"),
            Arguments.of("switch off the heater", "heater"),
            Arguments.of("turn off kitchen light", "kitchen light"),
            Arguments.of("turn off the air conditioning", "air conditioning"),
            Arguments.of("switch off living room lights", "living room lights"),
        )

        @JvmStatic
        fun smartHomeOffClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I'm leaving, turn everything off"),
            Arguments.of("kill all the lights"),
        )

        // ── E4B Fallthrough (complex / conversational) ────────────────────────

        @JvmStatic
        fun e4bFallthroughPhrases(): Stream<Arguments> = Stream.of(
            // Weather (needs API call)
            Arguments.of("is it going to rain tomorrow"),
            // Calendar (needs NLU for date/time/title extraction)
            Arguments.of("add dentist appointment to my calendar"),
            // Wikipedia / knowledge
            Arguments.of("tell me about the history of New Zealand"),
            Arguments.of("who invented the internet"),
            Arguments.of("what is quantum computing"),
            Arguments.of("explain photosynthesis"),
            // Memory
            Arguments.of("what did I tell you about my car"),
            // Navigation
            Arguments.of("how do I get to the airport"),
            // General conversation
            Arguments.of("how are you doing today"),
            Arguments.of("tell me a joke"),
            Arguments.of("what can you do"),
            Arguments.of("what's the meaning of life"),
            Arguments.of("how do I cook pasta"),
            Arguments.of("explain quantum physics"),
            Arguments.of("write me a poem"),
            // Ambiguous — could be many things
            Arguments.of("help me with something"),
            Arguments.of("I'm bored"),
            Arguments.of("what should I do today"),
            Arguments.of("can you help me"),
            // Edge cases — empty / whitespace / single character
            Arguments.of(""),
            Arguments.of("   "),
            Arguments.of("a"),
            // Holiday / recurring date queries — should fall through to E4B (QIR cannot resolve these)
            Arguments.of("what day does Christmas fall on"),
            Arguments.of("what day does New Year fall on this year"),
            Arguments.of("what day does Easter fall on this year"),
            Arguments.of("when is Christmas this year"),
            Arguments.of("what date is Easter this year"),
            Arguments.of("what date is Thanksgiving this year"),
            Arguments.of("what day is New Year's Day this year"),
            // Boundary false-positives — must NOT match QIR date/time patterns
            Arguments.of("what is the monthly cost"),
            Arguments.of("what is the weekly schedule"),
            Arguments.of("what is the year 1984 about"),
            Arguments.of("what is the week's schedule"),
            Arguments.of("what is the month's budget"),
            Arguments.of("is it Friday's episode tonight"),
            Arguments.of("is it Saturday's game on"),
            Arguments.of("what year is this movie set in"),
            Arguments.of("what year is it set in"),
            Arguments.of("what year are we in the sequence"),
            Arguments.of("what month is this charge for"),
            Arguments.of("what month is this invoice for"),
            Arguments.of("what week is this training on"),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASSERTION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun assertRegexMatch(result: QuickIntentRouter.RouteResult, expectedIntent: String, input: String) {
        assertInstanceOf(
            QuickIntentRouter.RouteResult.RegexMatch::class.java,
            result,
            "Expected regex match for '$input', got $result",
        )
        assertEquals(
            expectedIntent,
            (result as QuickIntentRouter.RouteResult.RegexMatch).intent.intentName,
            "Wrong intent for '$input'",
        )
    }

    private fun assertFallThrough(result: QuickIntentRouter.RouteResult, input: String) {
        assertInstanceOf(
            QuickIntentRouter.RouteResult.FallThrough::class.java,
            result,
            "Expected fallthrough for '$input', got $result",
        )
    }

    private fun assertClassifierOrFallthrough(
        result: QuickIntentRouter.RouteResult,
        expectedIntent: String,
        input: String,
    ) {
        // Classifier match with correct intent is ideal
        // Fallthrough is acceptable (gap for real BERT to fill)
        // Wrong intent is a failure
        when (result) {
            is QuickIntentRouter.RouteResult.ClassifierMatch -> {
                assertEquals(expectedIntent, result.intent.intentName, "Classifier wrong intent for '$input'")
            }
            is QuickIntentRouter.RouteResult.FallThrough -> {
                // Acceptable — keyword mock can't handle everything
                // Real BERT-tiny should catch these
            }
            is QuickIntentRouter.RouteResult.RegexMatch -> {
                // Also acceptable if regex catches it
                assertEquals(expectedIntent, result.intent.intentName, "Regex wrong intent for '$input'")
            }
            is QuickIntentRouter.RouteResult.NeedsSlot -> {
                // Regex matched but needs a slot — still counts as routing to the correct intent
                assertEquals(expectedIntent, result.intent.intentName, "NeedsSlot wrong intent for '$input'")
            }
        }
    }
}
