package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThinkingStreamStateMachineTest {
    @Test
    fun `pre-close traffic only emits thinking deltas`() {
        val stateMachine = ThinkingStreamStateMachine()

        val first = stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nThinking step 1",
            rawMessage = "",
        )
        val second = stateMachine.consume(
            channelDelta = "\nThinking step 2",
            rawMessage = "",
        )

        assertEquals(listOf("Thinking step 1"), first.thinkingDeltas)
        assertEquals(emptyList<String>(), first.responseDeltas)
        assertEquals(listOf("\nThinking step 2"), second.thinkingDeltas)
        assertEquals(emptyList<String>(), second.responseDeltas)
    }

    @Test
    fun `marker observed in raw replay splits buffered thinking and response once`() {
        val stateMachine = ThinkingStreamStateMachine()

        val first = stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nPlan carefully",
            rawMessage = "",
        )
        val second = stateMachine.consume(
            channelDelta = null,
            rawMessage = "$THINKING_CHANNEL_HEADER\nPlan carefully${THINKING_CLOSE_MARKER}Final answer",
        )

        assertEquals(listOf("Plan carefully"), first.thinkingDeltas)
        assertEquals(emptyList<String>(), first.responseDeltas)
        assertEquals(emptyList<String>(), second.thinkingDeltas)
        assertEquals(listOf("Final answer"), second.responseDeltas)
    }

    @Test
    fun `post-close thought channel traffic is emitted as visible response`() {
        val stateMachine = ThinkingStreamStateMachine()

        stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nThink${THINKING_CLOSE_MARKER}Answer",
            rawMessage = "",
        )
        val next = stateMachine.consume(
            channelDelta = " continued",
            rawMessage = "",
        )

        assertEquals(emptyList<String>(), next.thinkingDeltas)
        assertEquals(listOf(" continued"), next.responseDeltas)
    }

    @Test
    fun `marker detection tolerates newline inside closing marker`() {
        val stateMachine = ThinkingStreamStateMachine()

        val first = stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nReasoning${
                THINKING_CLOSE_MARKER.removeSuffix("|>")
            }",
            rawMessage = "",
        )
        val second = stateMachine.consume(
            channelDelta = "|\n>Visible reply",
            rawMessage = "",
        )

        assertEquals(listOf("Reasoning"), first.thinkingDeltas)
        assertEquals(emptyList<String>(), first.responseDeltas)
        assertEquals(emptyList<String>(), second.thinkingDeltas)
        assertEquals(listOf("Visible reply"), second.responseDeltas)
    }

    @Test
   fun `marker detection tolerates space before close bracket`() {
        val stateMachine = ThinkingStreamStateMachine()

        val first = stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nReasoning<channel|",
            rawMessage = "",
        )
        val second = stateMachine.consume(
            channelDelta = " >Visible reply",
            rawMessage = "",
        )

        assertEquals(listOf("Reasoning"), first.thinkingDeltas)
        assertEquals(emptyList<String>(), first.responseDeltas)
        assertEquals(emptyList<String>(), second.thinkingDeltas)
        assertEquals(listOf("Visible reply"), second.responseDeltas)
    }
    fun `post-close raw wrapper unwraps non-thought channel body`() {
        val stateMachine = ThinkingStreamStateMachine()

        stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nThink$THINKING_CLOSE_MARKER",
            rawMessage = "",
        )
        val next = stateMachine.consume(
            channelDelta = null,
            rawMessage = "<|channel>assistant\nFinal answer$THINKING_CLOSE_MARKER",
        )

        assertEquals(emptyList<String>(), next.thinkingDeltas)
        assertEquals(listOf("Final answer"), next.responseDeltas)
    }

    @Test
    fun `post-close raw wrapper keeps visible suffix after close marker`() {
        val stateMachine = ThinkingStreamStateMachine()

        stateMachine.consume(
            channelDelta = "$THINKING_CHANNEL_HEADER\nThink$THINKING_CLOSE_MARKER",
            rawMessage = "",
        )
        val next = stateMachine.consume(
            channelDelta = null,
            rawMessage = "<|channel>assistant\nFinal answer$THINKING_CLOSE_MARKER continued",
        )

        assertEquals(emptyList<String>(), next.thinkingDeltas)
        assertEquals(listOf("Final answer continued"), next.responseDeltas)
    }
}
