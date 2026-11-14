package com.kernel.ai.core.inference.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

class JandalEventLogTest {

    private fun event(ts: Long, name: String = "e") =
        buildJandalEvent(ts, "voice", name, emptyMap())

    // ── Eviction ─────────────────────────────────────────────────────────────

    @Test
    fun `buffer evicts oldest events beyond capacity and keeps order`() {
        val buffer = JandalEventBuffer(capacity = 3)
        (1..5).forEach { buffer.add(event(it.toLong(), name = "e$it")) }

        assertEquals(listOf("e3", "e4", "e5"), buffer.snapshot().map { it.name })
    }

    @Test
    fun `prepend keeps the newest events when older ones overflow capacity`() {
        val buffer = JandalEventBuffer(capacity = 3)
        buffer.add(event(10L, name = "new1"))
        buffer.add(event(11L, name = "new2"))
        buffer.prepend(listOf(event(1L, "old1"), event(2L, "old2"), event(3L, "old3")))

        assertEquals(listOf("old3", "new1", "new2"), buffer.snapshot().map { it.name })
    }

    @Test
    fun `store keeps the file bounded and reloads only the newest events`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        val now = 1_000_000L
        val writeBuffer = JandalEventBuffer(capacity = 3)
        val store = JandalEventStore(file, writeBuffer, capacity = 3)
        (1..7).forEach { i ->
            val e = event(now + i, name = "e$i")
            writeBuffer.add(e)
            store.append(e)
        }

        // Rewrites happen every 3 appends, so the file never holds more than 3 + 3 lines.
        assertEquals(true, file.readLines().size <= 6)

        val reloaded = JandalEventBuffer(capacity = 3)
        JandalEventStore(file, reloaded, capacity = 3).load(nowMs = now + 7)
        assertEquals(listOf("e5", "e6", "e7"), reloaded.snapshot().map { it.name })
    }

    @Test
    fun `load drops events older than the 24 hour window`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        val now = 10 * JANDAL_EVENT_MAX_AGE_MS
        file.writeText(
            listOf(
                event(now - JANDAL_EVENT_MAX_AGE_MS - 1, "stale").toLine(),
                event(now - 1_000, "fresh").toLine(),
            ).joinToString("\n") + "\n",
        )

        val buffer = JandalEventBuffer()
        JandalEventStore(file, buffer).load(nowMs = now)

        assertEquals(listOf("fresh"), buffer.snapshot().map { it.name })
    }

    @Test
    fun `export lines exclude events outside the age window`() {
        val now = 5 * JANDAL_EVENT_MAX_AGE_MS
        val old = buildJandalEvent(now - JANDAL_EVENT_MAX_AGE_MS - 5_000, "voice", "old", emptyMap())
        val recent = buildJandalEvent(now - 5_000, "voice", "recent", mapOf("ms" to 12))

        val lines = renderJandalEventLines(listOf(old, recent), now, ZoneId.of("UTC"))

        assertEquals(1, lines.size)
        assertEquals(true, lines.single().endsWith(" voice recent ms=12"))
    }

    // ── Redaction ────────────────────────────────────────────────────────────

    @Test
    fun `free text is redacted`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("What is the weather like"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("Text Alice I'm running late"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("Buy oat milk, eggs"))
    }

    @Test
    fun `contact numbers and emails are redacted`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("0400123456"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("+61 400 123 456"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("+61400123456"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("alice@example.com"))
    }

    @Test
    fun `values that would break the line format are redacted`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("a|b"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("k=v"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("line\nbreak"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("x".repeat(49)))
    }

    @Test
    fun `non-text values are redacted rather than stringified`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(listOf("Alice")))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(Double.NaN))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(null))
    }

    @Test
    fun `identifiers, enums, numbers and booleans pass through`() {
        assertEquals("native_on_device", sanitizeJandalValue("native_on_device"))
        assertEquals("wake_session_completed", sanitizeJandalValue("wake_session_completed"))
        assertEquals("GEMMA", sanitizeJandalValue(DiagnosticEnum.GEMMA))
        assertEquals("240", sanitizeJandalValue(240))
        assertEquals("true", sanitizeJandalValue(true))
    }

    @Test
    fun `event built from user text carries no user text`() {
        val event = buildJandalEvent(
            timestampMs = 1L,
            category = "skill",
            name = "add_list_item",
            fields = mapOf(
                "transcript" to "buy milk and call Alice on 0400123456",
                "item" to "milk",
                "ms" to 12L,
                "ok" to false,
            ),
        )

        val line = event.toLine()
        assertEquals(false, line.contains("buy milk"))
        assertEquals(false, line.contains("Alice"))
        assertEquals(false, line.contains("0400123456"))
        assertEquals(
            mapOf("transcript" to JANDAL_EVENT_REDACTED, "item" to "milk", "ms" to "12", "ok" to "false"),
            event.fields,
        )
    }

    @Test
    fun `fields beyond the cap are dropped`() {
        val fields = (1..12).associate { "k$it" to it }
        val event = buildJandalEvent(1L, "voice", "x", fields)

        assertEquals(JANDAL_EVENT_MAX_FIELDS, event.fields.size)
    }

    // ── Line format ──────────────────────────────────────────────────────────

    @Test
    fun `line round trips through parse`() {
        val event = buildJandalEvent(42L, "download", "state", mapOf("model" to "GEMMA_4_E4B", "bytes" to 9L))

        assertEquals(event, parseJandalEventLine(event.toLine()))
    }

    @Test
    fun `malformed line is rejected`() {
        assertNull(parseJandalEventLine("not a line"))
        assertNull(parseJandalEventLine("abc|voice|ready"))
        assertNull(parseJandalEventLine("1|voice|ready|novalue"))
    }

    @Test
    fun `parse re-sanitises values from disk`() {
        val parsed = parseJandalEventLine("1|voice|ready|text=hello world")

        assertEquals(mapOf("text" to JANDAL_EVENT_REDACTED), parsed?.fields)
    }

    private enum class DiagnosticEnum { GEMMA }
}
