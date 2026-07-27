package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThinkingStreamStateMachineTest {
    @Test
    fun `structured thought segment emits only thinking`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf("$THINKING_CHANNEL_HEADER\nPlan carefully" to ""),
        )

        assertEquals("Plan carefully", result.thinking)
        assertEquals("", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `clean primary response emits only visible response`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(null to "Kia ora."),
        )

        assertEquals("", result.thinking)
        assertEquals("Kia ora.", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `structured thought sharing a prefix with primary response does not truncate response`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                "$THINKING_CHANNEL_HEADER\nThe answer is" to "",
                null to "The answer is 42.",
            ),
        )

        assertEquals("The answer is", result.thinking)
        assertEquals("The answer is 42.", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `later thought channel traffic remains thinking after close and tool boundary`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                "$THINKING_CHANNEL_HEADER\nReasoning pass 1" to "<ctrl tool_call>",
                "$THINKING_CHANNEL_HEADER\nReasoning pass 1$THINKING_CLOSE_MARKER" to "<ctrl tool_result>",
                "$THINKING_CHANNEL_HEADER\nReasoning pass 2" to "",
                null to "Kia ora. When would you like me to remind you to buy milk?",
            ),
        )

        assertEquals("Reasoning pass 1Reasoning pass 2", result.thinking)
        assertEquals("Kia ora. When would you like me to remind you to buy milk?", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `structured cumulative thought callbacks do not duplicate output`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                "$THINKING_CHANNEL_HEADER\nPlan",
                "$THINKING_CHANNEL_HEADER\nPlan carefully",
                "$THINKING_CHANNEL_HEADER\nPlan carefully$THINKING_CLOSE_MARKER",
            ).map { it to "" },
        )

        assertEquals("Plan carefully", result.thinking)
        assertEquals("", result.response)
    }

    @Test
    fun `cumulative primary callbacks do not duplicate output`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                null to "Kia ora",
                null to "Kia ora. Final answer",
                null to "Kia ora. Final answer",
            ),
        )

        assertEquals("Kia ora. Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `disabled thinking discards thought instead of merging it into response`() {
        val result = collect(
            ThinkingStreamStateMachine(thinkingEnabled = false),
            listOf(
                "$THINKING_CHANNEL_HEADER\nprivate structured reasoning" to "",
                null to "<|think|>private raw reasoning<|/think|>Final answer",
            ),
        )

        assertEquals("", result.thinking)
        assertEquals("Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `raw channel wrapper emits clean thought and visible suffix`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(null to "<|channel>thought\nReasoning<channel|>Final answer"),
        )

        assertEquals("Reasoning", result.thinking)
        assertEquals("Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `raw think wrapper emits clean thought and visible suffix`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(null to "<|think|>Reasoning<|/think|>Final answer"),
        )

        assertEquals("Reasoning", result.thinking)
        assertEquals("Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `opening and closing markers split at every character boundary`() {
        listOf(
            "<|channel>thought\nReasoning<channel|>Final answer",
            "<|think|>Reasoning<|/think|>Final answer",
        ).forEach { raw ->
            val result = collect(
                ThinkingStreamStateMachine(),
                raw.map { null to it.toString() },
            )

            assertEquals("Reasoning", result.thinking, raw)
            assertEquals("Final answer", result.response, raw)
            assertVisibleDeltasAreSafe(result.responseDeltas)
        }
    }

    @Test
    fun `raw fallback supports multiple thought and visible segments`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                null to "<|think|>first thought<|/think|>visible one",
                null to "<|channel>thought second thought<channel|>visible two",
            ),
        )

        assertEquals("first thoughtsecond thought", result.thinking)
        assertEquals("visible onevisible two", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `raw cumulative callbacks do not duplicate thought or response`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                null to "<|think|>Reasoning",
                null to "<|think|>Reasoning<|/think|>Final",
                null to "<|think|>Reasoning<|/think|>Final answer",
            ),
        )

        assertEquals("Reasoning", result.thinking)
        assertEquals("Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `structured thought and raw wrapper do not duplicate thought`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                "$THINKING_CHANNEL_HEADER\nReasoning" to "<|channel>thought\nReasoning<channel|>Final",
            ),
        )

        assertEquals("Reasoning", result.thinking)
        assertEquals("Final", result.response)
    }

    @Test
    fun `non-thought channel wrapper never renders protocol syntax`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(null to "<|channel>assistant\nFinal answer<channel|>"),
        )

        assertEquals("", result.thinking)
        assertEquals("Final answer", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `unterminated thought is never flushed as visible response`() {
        val machine = ThinkingStreamStateMachine()
        val first = machine.consume(null, "<|think|>private reasoning")
        val final = machine.finish()
        val responseDeltas = first.responseDeltas + final.responseDeltas

        assertEquals("", responseDeltas.joinToString(""))
        assertVisibleDeltasAreSafe(responseDeltas)
    }

    @Test
    fun `unresolved marker prefix is withheld and discarded at completion`() {
        val machine = ThinkingStreamStateMachine()
        val first = machine.consume(null, "Final answer<|chan")
        val final = machine.finish()
        val responseDeltas = first.responseDeltas + final.responseDeltas

        assertEquals("Final answer", responseDeltas.joinToString(""))
        assertVisibleDeltasAreSafe(responseDeltas)
    }

    @Test
    fun `ordinary non-thinking streamed text remains unchanged`() {
        val result = collect(
            ThinkingStreamStateMachine(),
            listOf(
                null to "Kia ",
                null to "ora",
                null to "!",
            ),
        )

        assertEquals("Kia ora!", result.response)
        assertVisibleDeltasAreSafe(result.responseDeltas)
    }

    @Test
    fun `physical load_skill tool produces initial and later thinking with one continuous parser`() {
        // Lossless ordered replay of S23U E4B GPU event stream using ONE
        // continuous ThinkingStreamStateMachine instance for the enabled replay.
        // Diagnostic commit: 4bd44227, worktree clean at build.
        // Router: fallthrough (best_guess=open_app, confidence=0.56), thinkingEnabled=true.
        // Prompt: "First load runintent skill. Then open Calculator using native open_app
        //          intent with app_name Calculator and confirm when ready."
        //
        // Flow: router fallthrough -> initial thinking (145 callbacks) ->
        //   load_skill(run_intent) -> SkillResult.Success (directReply=false,
        //   returnedToGemma=true) -> model calls run_intent(open_app) ->
        //   SkillResult.Success (directReply=false, returnedToGemma=true) ->
        //   later thinking (seq 398-450) -> clean final answer (seq 451-458)
        val resource = javaClass.classLoader!!.getResource("physical_callback_fixture.json")
            ?: throw IllegalStateException("Missing test resource: physical_callback_fixture.json")
        val json = org.json.JSONObject(resource.readText())
        val events = json.getJSONArray("events")
        val finalVisible = json.getString("final_visible")
        assertEquals("fallthrough", json.getJSONObject("header").getString("router"),
            "router must be fallthrough for this fixture")

        // Single continuous parser for the enabled replay
        val machine = ThinkingStreamStateMachine()
        val allThinkingDeltas = mutableListOf<String>()
        val allResponseDeltas = mutableListOf<String>()

        // Tool event tracking
        val loadSkillCalls = mutableListOf<org.json.JSONObject>()
        val loadSkillResults = mutableListOf<org.json.JSONObject>()
        var qualifyingResultSeq: Int? = null
        var qualifyingResult: org.json.JSONObject? = null
        var qualifyingCall: org.json.JSONObject? = null
        var firstVisibleSeq: Int? = null
        var visibleBeforeQualifyingResult = false
        val postResultThinking = StringBuilder()
        var qualifyingResultProcessed = false

        for (i in 0 until events.length()) {
            val evt = events.getJSONObject(i)
            when (evt.getString("type")) {
                "callback" -> {
                    val thought = evt.optString("thought", "").takeIf { it.isNotEmpty() }
                    val raw = evt.optString("raw", "")
                    val seq = evt.getInt("seq")
                    val emission = machine.consume(thought, raw)

                    // Record first visible delta seq
                    if (emission.responseDeltas.isNotEmpty() && firstVisibleSeq == null) {
                        firstVisibleSeq = seq
                        if (qualifyingResultSeq == null || seq < qualifyingResultSeq!!) {
                            visibleBeforeQualifyingResult = true
                        }
                    }

                    // Accumulate thinking from after the qualifying result
                    if (qualifyingResultProcessed) {
                        emission.thinkingDeltas.forEach { postResultThinking.append(it) }
                    }

                    allThinkingDeltas += emission.thinkingDeltas
                    allResponseDeltas += emission.responseDeltas
                }
                "tool_call" -> {
                    val name = evt.getString("name")
                    if (name == "load_skill") {
                        loadSkillCalls.add(evt)
                    }
                    // Identify the qualifying run_intent call with valid Calculator arguments
                    if (name == "run_intent" && evt.getString("arguments").contains("Calculator")) {
                        qualifyingCall = evt
                    }
                }
                "tool_result" -> {
                    val name = evt.getString("name")
                    if (name == "load_skill") {
                        loadSkillResults.add(evt)
                    }
                    // Identify the successful run_intent result
                    if (name == "run_intent" && evt.getString("resultType") == "Success") {
                        qualifyingResult = evt
                        qualifyingResultSeq = evt.getInt("seq")
                        qualifyingResultProcessed = true
                    }
                }
            }
        }
        val finalEmission = machine.finish()
        allThinkingDeltas += finalEmission.thinkingDeltas
        allResponseDeltas += finalEmission.responseDeltas

        // ── load_skill assertions ────────────────────────────────────
        assertEquals(1, loadSkillCalls.size, "exactly one load_skill call")
        assertTrue(loadSkillCalls[0].getString("arguments").contains("run_intent"),
            "load_skill arguments must specify run_intent")
        assertEquals(1, loadSkillResults.size, "exactly one load_skill result")
        assertEquals("Success", loadSkillResults[0].getString("resultType"),
            "load_skill result must be Success")
        assertEquals(false, loadSkillResults[0].getBoolean("directReply"),
            "load_skill directReply must be false")
        assertTrue(loadSkillResults[0].getBoolean("returnedToGemma"),
            "load_skill returnedToGemma must be true")

        // ── Qualifying run_intent assertions ────────────────────────
        assertNotNull(qualifyingCall, "qualifying run_intent call must exist")
        assertEquals("run_intent", qualifyingCall!!.getString("name"),
            "qualifying call name must be run_intent")
        assertTrue(qualifyingCall!!.getString("arguments").contains("open_app"),
            "qualifying run_intent must use open_app")
        assertTrue(qualifyingCall!!.getString("arguments").contains("Calculator"),
            "qualifying run_intent must target Calculator")

        assertNotNull(qualifyingResult, "qualifying run_intent result must exist")
        assertEquals(397, qualifyingResultSeq,
            "qualifying run_intent result must be at seq 397")
        assertEquals("Success", qualifyingResult!!.getString("resultType"),
            "qualifying result type must be Success")
        assertEquals(false, qualifyingResult!!.getBoolean("directReply"),
            "qualifying result directReply must be false")
        assertTrue(qualifyingResult!!.getBoolean("returnedToGemma"),
            "qualifying result returnedToGemma must be true")
        val runIntentContent = qualifyingResult!!.optString("content", "")
        assertTrue(runIntentContent.contains("Opening"),
            "qualifying result content must confirm opening")

        // ── Post-result callback assertions ─────────────────────────
        // The first callback after the qualifying result is seq 398
        assertTrue(
            qualifyingResultSeq!! < events.length(),
            "there must be events after the qualifying result"
        )

        // ── Continuous parser: post-result thinking ─────────────────
        assertTrue(
            postResultThinking.isNotEmpty(),
            "the same continuous parser must emit non-empty thinking after the qualifying result"
        )

        // ── Visible output ordering ─────────────────────────────────
        assertNotNull(firstVisibleSeq, "first visible delta must be recorded")
        assertTrue(
            firstVisibleSeq!! > qualifyingResultSeq!!,
            "first visible delta (seq $firstVisibleSeq) must occur after " +
            "qualifying result (seq $qualifyingResultSeq)"
        )
        assertEquals(451, firstVisibleSeq,
            "first visible delta must be at seq 451 (Kia)")
        assertFalse(visibleBeforeQualifyingResult,
            "no visible delta may occur before the successful run_intent result")

        // ── Protocol safety ─────────────────────────────────────────
        allResponseDeltas.forEach { delta ->
            assertFalse(delta.contains("<|channel>"), "no channel wrapper in visible: $delta")
            assertFalse(delta.contains("<|think|>"), "no think tags in visible: $delta")
        }

        // ── Final visible output ────────────────────────────────────
        val totalVisible = allResponseDeltas.joinToString("")
        assertEquals(finalVisible, totalVisible, "visible output must match captured final answer")
        assertVisibleDeltasAreSafe(allResponseDeltas)

        // ── Thinking-disabled replay ─────────────────────────────────
        val disabledMachine = ThinkingStreamStateMachine(thinkingEnabled = false)
        val disabledThinking = mutableListOf<String>()
        val disabledResponseDeltas = mutableListOf<String>()
        for (i in 0 until events.length()) {
            val evt = events.getJSONObject(i)
            if (evt.getString("type") == "callback") {
                val thought = evt.optString("thought", "").takeIf { it.isNotEmpty() }
                val raw = evt.optString("raw", "")
                val em = disabledMachine.consume(thought, raw)
                disabledThinking += em.thinkingDeltas
                disabledResponseDeltas += em.responseDeltas
            }
        }
        val disabledFinal = disabledMachine.finish()
        disabledThinking += disabledFinal.thinkingDeltas
        disabledResponseDeltas += disabledFinal.responseDeltas

        assertTrue(disabledThinking.all { it.isEmpty() }, "disabled thinking must emit no thinking")
        assertVisibleDeltasAreSafe(disabledResponseDeltas)
        assertEquals(finalVisible, disabledResponseDeltas.joinToString(""),
            "disabled thinking must produce same visible output")
    }

    private data class Collected(
        val thinkingDeltas: List<String>,
        val responseDeltas: List<String>,
    ) {
        val thinking: String get() = thinkingDeltas.joinToString("")
        val response: String get() = responseDeltas.joinToString("")
    }

    private fun collect(
        machine: ThinkingStreamStateMachine,
        callbacks: List<Pair<String?, String>>,
    ): Collected {
        val thinking = mutableListOf<String>()
        val response = mutableListOf<String>()
        callbacks.forEach { (channel, raw) ->
            val emission = machine.consume(channel, raw)
            thinking += emission.thinkingDeltas
            response += emission.responseDeltas
        }
        val final = machine.finish()
        thinking += final.thinkingDeltas
        response += final.responseDeltas
        return Collected(thinking, response)
    }

    private fun assertVisibleDeltasAreSafe(deltas: List<String>) {
        deltas.forEach { delta ->
            assertEquals(false, containsProtocolSyntaxOrPrefix(delta), "unsafe visible delta: $delta")
        }
    }
}
