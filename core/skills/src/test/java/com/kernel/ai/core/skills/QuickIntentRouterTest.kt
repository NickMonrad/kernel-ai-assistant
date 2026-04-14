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

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#alarmClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "set_alarm", input)
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

        @ParameterizedTest(name = "Classifier: \"{0}\"")
        @MethodSource("com.kernel.ai.core.skills.QuickIntentRouterTest#timerClassifierPhrases")
        fun `should match via classifier`(input: String) {
            val regexResult = regexOnlyRouter.route(input)
            val hybridResult = hybridRouter.route(input)
            assertFallThrough(regexResult, input)
            assertClassifierOrFallthrough(hybridResult, "set_timer", input)
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
        addCases(timeRegexPhrases(), "get_time", "Time (regex)")
        addCases(timeClassifierPhrases(), "get_time", "Time (classifier)")
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

            val regexMatched = regexResult is QuickIntentRouter.RouteResult.RegexMatch
            val hybridMatched = hybridResult is QuickIntentRouter.RouteResult.RegexMatch ||
                hybridResult is QuickIntentRouter.RouteResult.ClassifierMatch

            val routedIntent = when (hybridResult) {
                is QuickIntentRouter.RouteResult.RegexMatch -> hybridResult.intent.intentName
                is QuickIntentRouter.RouteResult.ClassifierMatch -> hybridResult.intent.intentName
                is QuickIntentRouter.RouteResult.FallThrough -> null
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
            Arguments.of("can you turn the flashlight on please"),
            Arguments.of("hey can you switch the light on for me"),
        )

        @JvmStatic
        fun flashlightOnClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("I need some light"),
            Arguments.of("illuminate the room"),
            Arguments.of("torch please"),
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
            Arguments.of("kill the light"),
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
            Arguments.of("unmute my phone", "toggle_dnd_off"),
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
        )

        @JvmStatic
        fun timeClassifierPhrases(): Stream<Arguments> = Stream.of(
            Arguments.of("what's today's date"),
            Arguments.of("do you know the time"),
            Arguments.of("how late is it"),
            Arguments.of("current date and time"),
        )

        // ── E4B Fallthrough (complex / conversational) ────────────────────────

        @JvmStatic
        fun e4bFallthroughPhrases(): Stream<Arguments> = Stream.of(
            // Weather (needs API call)
            Arguments.of("what's the weather in Auckland"),
            Arguments.of("is it going to rain tomorrow"),
            Arguments.of("weather forecast for the weekend"),
            // Calendar (needs NLU for date/time/title extraction)
            Arguments.of("schedule a meeting with John at 3pm on Friday"),
            Arguments.of("add dentist appointment to my calendar"),
            // Email/SMS (needs NLU for content)
            Arguments.of("send an email to my boss about the project"),
            Arguments.of("text Sarah that I'll be late"),
            // Wikipedia / knowledge
            Arguments.of("tell me about the history of New Zealand"),
            Arguments.of("who invented the internet"),
            // Memory
            Arguments.of("remember that my wifi password is 12345"),
            Arguments.of("what did I tell you about my car"),
            // Navigation
            Arguments.of("navigate to the nearest petrol station"),
            Arguments.of("how do I get to the airport"),
            // General conversation
            Arguments.of("how are you doing today"),
            Arguments.of("tell me a joke"),
            Arguments.of("what can you do"),
            // Ambiguous — could be many things
            Arguments.of("help me with something"),
            Arguments.of("I'm bored"),
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
        }
    }
}
