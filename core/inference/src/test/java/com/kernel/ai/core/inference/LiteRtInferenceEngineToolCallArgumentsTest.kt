package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.JsonParser
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteRtInferenceEngineToolCallArgumentsTest {
    @Test
    fun `integer tool-call arguments remain JSON integers`() {
        // LiteRT-LM converts JSON numeric tool arguments to Number values before exposing ToolCall.
        val parsedArguments = JsonParser.parseString(
            """{"zero":0,"negative":-5,"positive":1000,"fraction":21.5}""",
        ).asJsonObject.entrySet().associate { (name, value) ->
            name to value.asJsonPrimitive.asNumber
        }.toMutableMap().apply {
            put("nullable", null)
        }
        val call = ToolCall("set_values", parsedArguments)

        val serialized = serializeToolCallArguments(call.arguments)
        val json = JSONObject(serialized)

        assertEquals(0, json.get("zero"))
        assertEquals(-5, json.get("negative"))
        assertEquals(1000, json.get("positive"))
        assertTrue(json.get("zero") is Int)
        assertTrue(json.get("negative") is Int)
        assertTrue(json.get("positive") is Int)
        assertEquals(21.5, json.getDouble("fraction"), 0.0)
        assertTrue(json.has("nullable"))
        assertTrue(json.isNull("nullable"))
    }
}
