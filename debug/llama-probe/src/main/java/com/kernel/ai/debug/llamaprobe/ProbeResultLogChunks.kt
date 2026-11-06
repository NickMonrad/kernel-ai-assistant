package com.kernel.ai.debug.llamaprobe

import java.nio.charset.StandardCharsets

/** Splits sanitized probe JSON into UTF-8-safe pieces below Android's per-record log limit. */
fun splitProbeJsonForLogcat(json: String, maxBytes: Int = DEFAULT_MAX_BYTES): List<String> {
    require(maxBytes >= 4) { "maxBytes must fit one UTF-8 code point" }
    if (json.isEmpty()) return emptyList()

    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    val chunks = ArrayList<String>()
    var start = 0
    while (start < bytes.size) {
        var end = start + minOf(maxBytes, bytes.size - start)
        while (end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        check(end > start) { "maxBytes cannot contain a UTF-8 code point" }
        chunks += String(bytes, start, end - start, StandardCharsets.UTF_8)
        start = end
    }
    return chunks
}

private const val DEFAULT_MAX_BYTES = 2_800
