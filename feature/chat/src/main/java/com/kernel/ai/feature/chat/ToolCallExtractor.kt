package com.kernel.ai.feature.chat

/**
 * Stateless utilities for extracting tool call JSON from raw model output.
 *
 * E4B and Gemma 4 emit tool calls in two different formats:
 *  - Gemma 4 native token:  <|tool_call>call:name{args}<tool_call|>
 *  - JSON (E4B / FunctionGemma):  {"name": "...", "arguments": {...}} possibly embedded in prose
 *
 * Both paths are normalised to the same canonical JSON string so [SkillExecutor] can
 * dispatch them uniformly.
 */
internal object ToolCallExtractor {

    /**
     * Scans [text] for a Gemma 4 native tool call token:
     *   <|tool_call>call:name{key:<|"|>value<|"|>,key2:numval}<tool_call|>
     * If found, normalises it to {"name": "name", "arguments": {...}} JSON.
     */
    fun extractNativeToolCall(text: String): String? {
        val startTag = "<|tool_call>"
        val endTag = "<tool_call|>"
        val startIdx = text.indexOf(startTag)
        if (startIdx < 0) return null
        val innerStart = startIdx + startTag.length
        val endIdx = text.indexOf(endTag, innerStart)
        if (endIdx < 0) return null

        val inner = text.substring(innerStart, endIdx).trim()
        if (!inner.startsWith("call:")) return null

        val afterCall = inner.substring("call:".length)
        val braceIdx = afterCall.indexOf('{')

        val rawName: String
        val argsJson = org.json.JSONObject()
        if (braceIdx < 0) {
            rawName = afterCall.trim()
        } else {
            rawName = afterCall.substring(0, braceIdx).trim()
            val lastBrace = afterCall.lastIndexOf('}')
            if (lastBrace > braceIdx) {
                val argsBlock = afterCall.substring(braceIdx + 1, lastBrace).trim()
                if (argsBlock.isNotBlank()) parseNativeArgs(argsBlock, argsJson)
            }
        }

        if (rawName.isBlank()) return null
        val snakeName = camelToSnake(rawName)

        return org.json.JSONObject()
            .put("name", snakeName)
            .put("arguments", argsJson)
            .toString()
    }

    /**
     * Scans [text] for the first balanced {...} block that contains a "name" key.
     * Handles responses where the model emits the tool call JSON embedded in prose.
     */
    fun extractToolCallJson(text: String): String? {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start == -1) break
            var depth = 0
            var j = start
            var inString = false
            var escape = false
            while (j < text.length) {
                val c = text[j]
                when {
                    escape -> escape = false
                    c == '\\' && inString -> escape = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(start, j + 1)
                            if (candidate.contains("\"name\"")) return candidate
                            break
                        }
                    }
                }
                j++
            }
            i = start + 1
        }
        return null
    }

    /** Converts camelCase to snake_case for mapping native tool names to SkillRegistry names. */
    fun camelToSnake(name: String): String =
        name.replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }.trimStart('_')

    /**
     * Parses the Gemma 4 native arg block, e.g.:
     *   location:<|"|>London<|"|>,unit:<|"|>celsius<|"|>
     *   duration:5
     *
     * Model occasionally wraps parameter keys in <|"|> delimiters. We strip them from the key
     * after extraction. Leading commas/whitespace are skipped at the loop level so the key
     * extraction never includes them.
     */
    internal fun parseNativeArgs(raw: String, out: org.json.JSONObject) {
        val strToken = """<|"|>"""
        var i = 0
        while (i < raw.length) {
            // Skip commas and whitespace between parameters (root cause of leading-comma key names).
            while (i < raw.length && (raw[i] == ',' || raw[i] == ' ')) i++
            if (i >= raw.length) break

            val colonIdx = raw.indexOf(':', i)
            if (colonIdx < 0) break
            // Strip <|"|> delimiters from key names — model sometimes wraps keys in them.
            val key = raw.substring(i, colonIdx).trim()
                .removePrefix(strToken)
                .removeSuffix(strToken)
                .trim()
            if (key.isBlank()) { i = colonIdx + 1; continue }
            val rest = raw.substring(colonIdx + 1)
            if (rest.startsWith(strToken)) {
                val valueStart = strToken.length
                val valueEnd = rest.indexOf(strToken, valueStart)
                if (valueEnd < 0 || valueEnd < valueStart) break
                out.put(key, rest.substring(valueStart, valueEnd))
                i = colonIdx + 1 + valueEnd + strToken.length
                if (i < raw.length && raw[i] == ',') i++
            } else {
                val commaIdx = rest.indexOf(',')
                val rawVal = if (commaIdx < 0) rest.trim() else rest.substring(0, commaIdx).trim()
                out.put(key, rawVal.toLongOrNull() ?: rawVal.toDoubleOrNull() ?: rawVal)
                i = colonIdx + 1 + (if (commaIdx < 0) rest.length else commaIdx + 1)
            }
        }
    }
}
