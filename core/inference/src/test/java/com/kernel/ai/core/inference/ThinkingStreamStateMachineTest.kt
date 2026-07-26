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
        // Lossless replay of the exact physical callback sequence captured from
        // S23U (SM-S918B, SDK 36) Gemma-4 E-4B GPU on 2026-07-26.
        //
        // Router: fallthrough (result=fallthrough, best_guess=null, confidence=0.0)
        // thinkingEnabled: true (confirmed by log: currentConfig?.thinkingEnabled=true)
        //
        // Event sequence:
        //   1. load_skill(run_intent) -> SkillResult.Success, directReply=false, returned_to_gemma=true
        //   2. run_intent(setvolume, value=37, is_percent=true) -> SkillResult.Success, directReply=false, returned_to_gemma=true
        //   3. Gemma resumed after each tool result (callbacks continued)
        //   4. Final visible output confirmed
        //
        // Proves:
        //   - Initial thought content is emitted only as GenerationResult.Thinking
        //   - Tool/control content is never emitted as visible prose
        //   - Callbacks after tool-result boundaries are replayed
        //   - Later thought content after tool results remains Thinking
        //   - No protocol marker or fragment appears in any visible delta
        //   - Final visible answer matches the captured exact text
        val resource = javaClass.classLoader!!.getResource("physical_callback_fixture.json")
            ?: throw IllegalStateException("Missing test resource: physical_callback_fixture.json")
        val json = org.json.JSONObject(resource.readText())
        val callbacks = json.getJSONArray("callbacks")
        val finalVisible = json.getString("final_visible")

        val pairs = mutableListOf<Pair<String?, String>>()
        for (i in 0 until callbacks.length()) {
            val cb = callbacks.getJSONObject(i)
            val thought = cb.optString("thought", null).takeIf { it.isNotEmpty() }
            val raw = cb.optString("raw", "")
            pairs.add(thought to raw)
        }

        val result = collect(ThinkingStreamStateMachine(), pairs)

        // Not a single visible delta carries a protocol marker
        assertVisibleDeltasAreSafe(result.responseDeltas)

        // The final visible output equals the captured answer
        assertEquals(finalVisible, result.response, "visible output must match captured final answer")

        // No protocol markers in visible output
        assertFalse(result.response.contains("<|channel>"), "visible must not contain channel wrapper")
        assertFalse(result.response.contains("<|think|>"), "visible must not contain think tags")
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
