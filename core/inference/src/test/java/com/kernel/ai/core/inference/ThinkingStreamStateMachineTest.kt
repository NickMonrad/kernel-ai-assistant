package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        //   later thinking -> clean final answer "Kia ora. Calculator is now open."
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

        // Track tool events
        val loadSkillCalls = mutableListOf<org.json.JSONObject>()
        val loadSkillResults = mutableListOf<org.json.JSONObject>()
        var beforeFirstResult = true
        val callbacksBeforeResult = mutableListOf<Pair<String?, String>>()
        val callbacksAfterResult = mutableListOf<Pair<String?, String>>()
        var firstPostResultSeq = -1

        for (i in 0 until events.length()) {
            val evt = events.getJSONObject(i)
            when (evt.getString("type")) {
                "callback" -> {
                    val thought = evt.optString("thought", "").takeIf { it.isNotEmpty() }
                    val raw = evt.optString("raw", "")
                    val emission = machine.consume(thought, raw)
                    allThinkingDeltas += emission.thinkingDeltas
                    allResponseDeltas += emission.responseDeltas
                    if (beforeFirstResult) {
                        callbacksBeforeResult.add(thought to raw)
                    } else {
                        callbacksAfterResult.add(thought to raw)
                        if (firstPostResultSeq < 0) firstPostResultSeq = evt.getInt("seq")
                    }
                }
                "tool_call" -> {
                    if (evt.getString("name") == "load_skill") {
                        loadSkillCalls.add(evt)
                    }
                }
                "tool_result" -> {
                    if (evt.getString("name") == "load_skill") {
                        loadSkillResults.add(evt)
                        beforeFirstResult = false
                    }
                }
            }
        }
        val finalEmission = machine.finish()
        allThinkingDeltas += finalEmission.thinkingDeltas
        allResponseDeltas += finalEmission.responseDeltas

        // ── load_skill Tool assertions ───────────────────────────────
        assertEquals(1, loadSkillCalls.size, "exactly one load_skill call")
        assertTrue(loadSkillCalls[0].getString("arguments").contains("run_intent"),
            "load_skill arguments must specify run_intent")

        assertEquals(1, loadSkillResults.size, "exactly one load_skill result")
        assertEquals("Success", loadSkillResults[0].getString("resultType"),
            "result type must be Success (not DirectReply)")
        assertEquals(false, loadSkillResults[0].getBoolean("directReply"),
            "directReply must be false")
        assertTrue(loadSkillResults[0].getBoolean("returnedToGemma"),
            "result must be returned to Gemma")
        val resultContent = loadSkillResults[0].optString("content", "")
        assertTrue(resultContent.isNotEmpty(), "result content must be non-empty")
        assertTrue(resultContent.contains("run_intent"),
            "result content must contain run_intent skill instructions")

        // ── Continuous parser assertions ────────────────────────────
        // Thinking before the result is non-empty
        val thinkingText = allThinkingDeltas.joinToString("")
        assertTrue(thinkingText.isNotEmpty(),
            "continuous parser must emit non-empty total thinking")
        assertTrue(
            callbacksBeforeResult.isNotEmpty(),
            "there must be callbacks before the first tool result"
        )

        // No protocol markers in any visible delta
        allResponseDeltas.forEach { delta ->
            assertFalse(delta.contains("<|channel>"), "no channel wrapper in visible: $delta")
            assertFalse(delta.contains("<|think|>"), "no think tags in visible: $delta")
        }

        // There must be callbacks after the result
        assertTrue(
            callbacksAfterResult.isNotEmpty(),
            "there must be callbacks after the first tool result"
        )
        assertTrue(
            firstPostResultSeq > 0,
            "first post-result callback must have a positive seq"
        )

        // Thinking before the result (initial reasoning)
        assertTrue(
            allThinkingDeltas.any { it.isNotEmpty() },
            "initial thinking must be non-empty"
        )

        // No protocol or partial marker appears visibly
        val totalVisible = allResponseDeltas.joinToString("")
        assertFalse(totalVisible.contains("<|channel>"), "visible must not contain channel wrapper")
        assertFalse(totalVisible.contains("<|think|>"), "visible must not contain think tags")

        // Final visible output exactly matches captured answer
        assertEquals(finalVisible, totalVisible, "visible output must match captured final answer")
        assertVisibleDeltasAreSafe(allResponseDeltas)

        // ── Thinking-disabled replay ─────────────────────────────────
        // One parser instance, all callbacks, no thinking emitted
        val disabledMachine = ThinkingStreamStateMachine(thinkingEnabled = false)
        val disabledThinking = mutableListOf<String>()
        val disabledResponse = mutableListOf<String>()
        (callbacksBeforeResult + callbacksAfterResult).forEach { (thought, raw) ->
            val em = disabledMachine.consume(thought, raw)
            disabledThinking += em.thinkingDeltas
            disabledResponse += em.responseDeltas
        }
        val disabledFinal = disabledMachine.finish()
        disabledThinking += disabledFinal.thinkingDeltas
        disabledResponse += disabledFinal.responseDeltas

        assertTrue(disabledThinking.all { it.isEmpty() }, "disabled thinking must emit no thinking")
        assertVisibleDeltasAreSafe(disabledResponse)
        assertEquals(finalVisible, disabledResponse.joinToString(""),
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
