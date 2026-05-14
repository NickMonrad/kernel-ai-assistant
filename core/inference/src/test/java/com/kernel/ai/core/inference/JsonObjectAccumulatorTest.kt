package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonObjectAccumulatorTest {
    @Test
    fun `append returns completed object once root closes`() {
        val accumulator = JsonObjectAccumulator()

        assertNull(accumulator.append("  {\"title\":\"Chicken\","))
        val result = accumulator.append("\"servings\":4}" )

        assertEquals("{\"title\":\"Chicken\",\"servings\":4}", result)
    }

    @Test
    fun `append ignores braces inside quoted strings`() {
        val accumulator = JsonObjectAccumulator()

        assertNull(accumulator.append("{\"title\":\"Chicken {tray bake}\","))
        val result = accumulator.append("\"method_steps\":[{\"step_number\":1,\"text\":\"Stir\"}]}" )

        assertEquals(
            "{\"title\":\"Chicken {tray bake}\",\"method_steps\":[{\"step_number\":1,\"text\":\"Stir\"}]}",
            result,
        )
    }

    @Test
    fun `append skips leading chatter until first json object`() {
        val accumulator = JsonObjectAccumulator()

        val result = accumulator.append("Sure — {\"days\":[{\"day_index\":0,\"title\":\"Chicken\",\"summary\":\"Quick\"}]}")

        assertEquals("{\"days\":[{\"day_index\":0,\"title\":\"Chicken\",\"summary\":\"Quick\"}]}", result)
    }
}
