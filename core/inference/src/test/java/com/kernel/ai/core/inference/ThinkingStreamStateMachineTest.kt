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
    fun `physical non-direct tool multi-pass callbacks produce clean output`() {
        // Lossless replay of the exact ordered event stream captured from
        // S23U (SM-S918B, SDK 36) Gemma-4 E-4B GPU on 2026-07-26.
        //
        // Diagnostic commit: 08a592a7, worktree clean at build time.
        // Router: fallthrough, thinkingEnabled=true.
        //
        // Interleaved event sequence (253 events):
        //   callbacks → load_skill(run_intent) → callback → run_intent(open_app, Calculator) → callback → complete
        val resource = javaClass.classLoader!!.getResource("physical_callback_fixture.json")
            ?: throw IllegalStateException("Missing test resource: physical_callback_fixture.json")
        val json = org.json.JSONObject(resource.readText())
        val events = json.getJSONArray("events")
        val finalVisible = json.getString("final_visible")

        // Extract callback events and tool events from the interleaved array
        val callbackEvents = mutableListOf<Pair<String?, String>>()
        val toolCalls = mutableListOf<org.json.JSONObject>()
        val toolResults = mutableListOf<org.json.JSONObject>()
        for (i in 0 until events.length()) {
            val evt = events.getJSONObject(i)
            val type = evt.getString("type")
            when (type) {
                "callback" -> {
                    val thought = evt.optString("thought", null as String?).takeIf { it.isNotEmpty() }
                    val raw = evt.optString("raw", "")
                    callbackEvents.add(thought to raw)
                }
                "tool_call" -> toolCalls.add(evt)
                "tool_result" -> toolResults.add(evt)
            }
        }

        // Verify tool assertions from the event stream
        assertEquals(2, toolCalls.size, "exactly 2 tool calls")
        assertEquals("load_skill", toolCalls[0].getString("name"))
        assertEquals("run_intent", toolCalls[0].getString("arguments").let { a ->
            val s = a.indexOf("run_intent"); if (s >= 0) "run_intent" else "not_found"
        })
        assertEquals(2, toolResults.size, "exactly 2 tool results")
        assertEquals("Success", toolResults[0].getString("resultType"))
        assertFalse(toolResults[0].getBoolean("directReply"))
        assertTrue(toolResults[0].getBoolean("returnedToGemma"))
        assertEquals("Success", toolResults[1].getString("resultType"))
        assertFalse(toolResults[1].getBoolean("directReply"))
        assertTrue(toolResults[1].getBoolean("returnedToGemma"))

        // Find callback indices at tool boundaries using the interleaved event order
        val callbackIndices = mutableListOf<Int>()
        var callbackIndex = 0
        for (i in 0 until events.length()) {
            val evt = events.getJSONObject(i)
            if (evt.getString("type") == "callback") callbackIndex++
            if (evt.getString("type") == "tool_result") callbackIndices.add(callbackIndex)
        }
        require(callbackIndices.size == 2) { "expected 2 tool results, got ${callbackIndices.size}" }

        val callbacksBeforeFirstResult = callbackEvents.take(callbackIndices[0])
        val callbacksBetweenResults = callbackEvents.drop(callbackIndices[0]).take(callbackIndices[1] - callbackIndices[0])
        val callbacksAfterLastResult = callbackEvents.drop(callbackIndices[1])

        // Phase 1: Before any tool result — initial thinking (from <|channel>thought\n wrapper)
        val phase1 = collect(ThinkingStreamStateMachine(), callbacksBeforeFirstResult)
        assertTrue(phase1.thinkingDeltas.any { it.isNotEmpty() }, "phase1 must have non-empty initial thinking")
        assertVisibleDeltasAreSafe(phase1.responseDeltas)

        // Phase 3: After last tool result — callbacks containing final visible answer
        val phase3 = collect(ThinkingStreamStateMachine(), callbacksAfterLastResult)
        assertVisibleDeltasAreSafe(phase3.responseDeltas)

        // Tool results are adjacent (no callbacks between them), so phase2 is empty

        // Full output from all callbacks
        val result = collect(ThinkingStreamStateMachine(), callbackEvents)

        // Full output assertions
        assertVisibleDeltasAreSafe(result.responseDeltas)
        assertEquals(finalVisible, result.response, "visible output must match captured final answer")
        assertFalse(result.response.contains("<|channel>"), "visible must not contain channel wrapper")
        assertFalse(result.response.contains("<|think|>"), "visible must not contain think tags")

        // Thinking-disabled replay
        val disabled = collect(ThinkingStreamStateMachine(thinkingEnabled = false), callbackEvents)
        assertTrue(disabled.thinkingDeltas.all { it.isEmpty() }, "disabled thinking must emit no thinking")
        assertVisibleDeltasAreSafe(disabled.responseDeltas)
        assertEquals(finalVisible, disabled.response, "disabled thinking must produce same visible output")
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
