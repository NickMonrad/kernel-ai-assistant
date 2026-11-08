package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.json.JSONObject

class ToolCallExtractorTest {

    // ─────────────────────────────────────────────────────────────────────────
    // extractToolCallJson
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ExtractToolCallJson {

        @Test
        fun `pure JSON response returned unchanged`() {
            val input = """{"name": "save_memory", "arguments": {"content": "test"}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            val json = JSONObject(result!!)
            assertEquals("save_memory", json.getString("name"))
        }

        @Test
        fun `JSON embedded in prose - extracts inner block`() {
            val input = """Got it! {"name": "save_memory", "arguments": {"content": "test"}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            assertEquals("save_memory", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `JSON at end of prose`() {
            val input = """Sure thing, Nick. {"name": "get_weather", "arguments": {}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            assertEquals("get_weather", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `nested braces in arguments`() {
            val input = """{"name": "x", "arguments": {"data": {"key": "val"}}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            val json = JSONObject(result!!)
            assertEquals("x", json.getString("name"))
            assertEquals("val", json.getJSONObject("arguments").getJSONObject("data").getString("key"))
        }

        @Test
        fun `escaped quotes inside string value`() {
            val input = """{"name": "x", "arguments": {"text": "say \"hello\""}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            assertEquals("x", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `multiple JSON blocks - returns first block containing name`() {
            val input = """{"foo": "bar"} {"name": "save_memory", "arguments": {}} {"name": "other", "arguments": {}}"""
            val result = ToolCallExtractor.extractToolCallJson(input)
            assertNotNull(result)
            assertEquals("save_memory", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `plain text with no JSON returns null`() {
            assertNull(ToolCallExtractor.extractToolCallJson("Hello mate"))
        }

        @Test
        fun `object without name key returns null`() {
            assertNull(ToolCallExtractor.extractToolCallJson("""{"foo": "bar"}"""))
        }

        @Test
        fun `unbalanced braces returns null without crashing`() {
            assertNull(ToolCallExtractor.extractToolCallJson("""{"name": "x" """))
        }

        @Test
        fun `empty string returns null`() {
            assertNull(ToolCallExtractor.extractToolCallJson(""))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractNativeToolCall
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ExtractNativeToolCall {

        private val STR = "<|\"|>"

        @Test
        fun `valid native call with string arg is normalised to JSON`() {
            val input = "<|tool_call>call:get_weather{location:${STR}London${STR}}<tool_call|>"
            val result = ToolCallExtractor.extractNativeToolCall(input)
            assertNotNull(result)
            val json = JSONObject(result!!)
            assertEquals("get_weather", json.getString("name"))
            assertEquals("London", json.getJSONObject("arguments").getString("location"))
        }

        @Test
        fun `camelCase tool name is converted to snake_case`() {
            val input = "<|tool_call>call:saveMemory{content:${STR}test${STR}}<tool_call|>"
            val result = ToolCallExtractor.extractNativeToolCall(input)
            assertNotNull(result)
            assertEquals("save_memory", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `no-arg tool call produces empty arguments object`() {
            val input = "<|tool_call>call:get_system_info<tool_call|>"
            val result = ToolCallExtractor.extractNativeToolCall(input)
            assertNotNull(result)
            val json = JSONObject(result!!)
            assertEquals("get_system_info", json.getString("name"))
            assertTrue(json.getJSONObject("arguments").length() == 0)
        }

        @Test
        fun `native call embedded in prose is extracted`() {
            val input = "Let me check. <|tool_call>call:get_weather{location:${STR}Auckland${STR}}<tool_call|> Done."
            val result = ToolCallExtractor.extractNativeToolCall(input)
            assertNotNull(result)
            assertEquals("get_weather", JSONObject(result!!).getString("name"))
        }

        @Test
        fun `numeric arg is extracted`() {
            val input = "<|tool_call>call:set_timer{duration:5}<tool_call|>"
            val result = ToolCallExtractor.extractNativeToolCall(input)
            assertNotNull(result)
            val args = JSONObject(result!!).getJSONObject("arguments")
            assertEquals(5L, args.getLong("duration"))
        }

        @Test
        fun `text with no native token returns null`() {
            assertNull(ToolCallExtractor.extractNativeToolCall("plain text"))
        }

        @Test
        fun `start tag without end tag returns null`() {
            assertNull(ToolCallExtractor.extractNativeToolCall("<|tool_call>call:get_weather{location:${STR}x${STR}}"))
        }

        @Test
        fun `content without call prefix returns null`() {
            assertNull(ToolCallExtractor.extractNativeToolCall("<|tool_call>get_weather<tool_call|>"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // camelToSnake
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class CamelToSnake {

        @Test
        fun `already snake_case is unchanged`() {
            assertEquals("get_weather", ToolCallExtractor.camelToSnake("get_weather"))
        }

        @Test
        fun `camelCase converts correctly`() {
            assertEquals("save_memory", ToolCallExtractor.camelToSnake("saveMemory"))
        }

        @Test
        fun `PascalCase converts correctly`() {
            assertEquals("get_weather", ToolCallExtractor.camelToSnake("GetWeather"))
        }

        @Test
        fun `single word is unchanged`() {
            assertEquals("weather", ToolCallExtractor.camelToSnake("weather"))
        }
    }
}
