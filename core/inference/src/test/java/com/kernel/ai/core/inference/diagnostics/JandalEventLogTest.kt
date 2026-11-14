package com.kernel.ai.core.inference.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset

class JandalEventLogTest {

    private enum class Route { NATIVE, ZIPFORMER }

    private fun event(ts: Long, name: String = "e", fields: Map<String, Any?> = emptyMap()) =
        buildJandalEvent(ts, "voice", name, fields)

    // ── Buffer eviction ──────────────────────────────────────────────────────

    @Test
    fun `buffer evicts oldest events beyond capacity and keeps order`() {
        val buffer = JandalEventBuffer(capacity = 3)
        (1..5).forEach { buffer.add(event(it.toLong(), name = "e$it")) }
        assertEquals(listOf("e3", "e4", "e5"), buffer.snapshot().map { it.name })
    }

    @Test
    fun `prepend keeps the newest events when older ones overflow capacity`() {
        val buffer = JandalEventBuffer(capacity = 3)
        buffer.add(event(10, "new1"))
        buffer.add(event(11, "new2"))
        buffer.prepend(listOf(event(1, "old1"), event(2, "old2"), event(3, "old3")))
        assertEquals(listOf("old3", "new1", "new2"), buffer.snapshot().map { it.name })
    }

    // ── Store: bounded file, reload, rewrite ─────────────────────────────────

    @Test
    fun `file never exceeds two times capacity and reload keeps the newest events`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        val buffer = JandalEventBuffer(capacity = 3)
        val store = JandalEventStore(file, buffer, capacity = 3)
        (1..20).forEach { store.append(event(1_000L + it, name = "e$it")) }

        assertTrue(file.readLines().size <= 6, "file has ${file.readLines().size} lines")

        val reloaded = JandalEventBuffer(capacity = 3)
        JandalEventStore(file, reloaded, capacity = 3).load(nowMs = 1_000L + 21)
        assertEquals(listOf("e18", "e19", "e20"), reloaded.snapshot().map { it.name })
    }

    @Test
    fun `rewrite does not duplicate events on reload`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        val store = JandalEventStore(file, JandalEventBuffer(capacity = 3), capacity = 3)
        (1..6).forEach { store.append(event(1_000L + it, name = "e$it")) }

        val reloaded = JandalEventBuffer(capacity = 10)
        JandalEventStore(file, reloaded, capacity = 10).load(nowMs = 1_010L)
        val names = reloaded.snapshot().map { it.name }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `appending after a reload continues the same bounded file`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        JandalEventStore(file, JandalEventBuffer(capacity = 5), capacity = 5)
            .apply { append(event(1_000, "a")); append(event(1_001, "b")) }

        val buffer = JandalEventBuffer(capacity = 5)
        val reopened = JandalEventStore(file, buffer, capacity = 5)
        reopened.load(nowMs = 1_002)
        reopened.append(event(1_002, "c"))

        val again = JandalEventBuffer(capacity = 5)
        JandalEventStore(file, again, capacity = 5).load(nowMs = 1_003)
        assertEquals(listOf("a", "b", "c"), again.snapshot().map { it.name })
    }

    @Test
    fun `load drops events older than the 24 hour window`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        val now = JANDAL_EVENT_MAX_AGE_MS * 3
        JandalEventStore(file, JandalEventBuffer(), capacity = 10).apply {
            append(event(now - JANDAL_EVENT_MAX_AGE_MS - 1, "stale"))
            append(event(now - 1, "fresh"))
        }
        val loaded = JandalEventBuffer()
        JandalEventStore(file, loaded, capacity = 10).load(nowMs = now)
        assertEquals(listOf("fresh"), loaded.snapshot().map { it.name })
    }

    @Test
    fun `oversized file is not read`(@TempDir dir: File) {
        val file = File(dir, "events.log")
        file.writeText("x".repeat((JANDAL_EVENT_MAX_FILE_BYTES + 1).toInt()))
        val loaded = JandalEventBuffer()
        JandalEventStore(file, loaded, capacity = 10).load(nowMs = 0)
        assertTrue(loaded.snapshot().isEmpty())
    }

    @Test
    fun `export lines exclude events outside the age window`() {
        val now = JANDAL_EVENT_MAX_AGE_MS * 2
        val lines = renderJandalEventLines(
            listOf(event(0, "old"), event(now - 5, "recent")),
            nowMs = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(1, lines.size)
        assertTrue(lines.single().endsWith("voice recent"))
    }

    // ── Allowlist ────────────────────────────────────────────────────────────

    @Test
    fun `strings are always redacted, including short words and numbers`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("milk"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("Alice"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("1234"))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue("NATIVE"))
    }

    @Test
    fun `user text from a field value never reaches the event`() {
        val built = buildJandalEvent(1, "voice", "list_add", mapOf("item" to "milk", "count" to 2))
        assertEquals(mapOf("item" to JANDAL_EVENT_REDACTED, "count" to "2"), built.fields)
        assertFalse(built.toLine().contains("milk"))
    }

    @Test
    fun `phone numbers and epoch timestamps are redacted even when typed as numbers`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(5551234567L))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(1_700_000_000_000L))
        assertEquals("999999999", sanitizeJandalValue(999_999_999L))
    }

    @Test
    fun `non-finite and non-scalar values are redacted`() {
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(Double.NaN))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(Double.POSITIVE_INFINITY))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(listOf("a")))
        assertEquals(JANDAL_EVENT_REDACTED, sanitizeJandalValue(null))
    }

    @Test
    fun `booleans, counts, decimals and enum constants pass through`() {
        assertEquals("true", sanitizeJandalValue(true))
        assertEquals("240", sanitizeJandalValue(240))
        assertEquals("1.5", sanitizeJandalValue(1.5))
        assertEquals("ZIPFORMER", sanitizeJandalValue(Route.ZIPFORMER))
    }

    @Test
    fun `invalid category, name and field keys are rejected`() {
        val built = buildJandalEvent(1, "Voice Input", "ok", mapOf("Bad Key" to 1, "good_key" to 2))
        assertEquals(JANDAL_EVENT_REDACTED, built.category)
        assertEquals(mapOf("good_key" to "2"), built.fields)
    }

    @Test
    fun `fields beyond the cap are dropped`() {
        val many = (1..20).associate { "k$it" to it }
        assertEquals(JANDAL_EVENT_MAX_FIELDS, buildJandalEvent(1, "voice", "e", many).fields.size)
    }

    // ── Line format ──────────────────────────────────────────────────────────

    @Test
    fun `line round trips through parse`() {
        val original = buildJandalEvent(42, "voice", "stt_route", mapOf("route" to Route.NATIVE, "online" to false))
        assertEquals(original, parseJandalEventLine(original.toLine()))
    }

    @Test
    fun `malformed line is rejected`() {
        assertNull(parseJandalEventLine("not a line"))
        assertNull(parseJandalEventLine("abc|voice|e"))
        assertNull(parseJandalEventLine("1|voice|e|nokey"))
    }

    @Test
    fun `parse re-sanitises values from disk`() {
        val parsed = parseJandalEventLine("1|voice|e|item=milk|route=NATIVE|n=7")
        assertEquals(mapOf("item" to JANDAL_EVENT_REDACTED, "route" to "NATIVE", "n" to "7"), parsed?.fields)
    }

    // ── JandalEvents entry point ─────────────────────────────────────────────

    @Test
    fun `install persists events recorded before and after it`(@TempDir dir: File) {
        val file = File(dir, JANDAL_EVENTS_FILE_NAME)
        JandalEvents.record("lifecycle", "before_install", "build" to Route.NATIVE)
        JandalEvents.install(file)
        JandalEvents.record("lifecycle", "after_install", "ok" to true)
        JandalEvents.awaitWritesForTest()

        val names = file.readLines().map { it.split('|')[2] }
        assertEquals(listOf("before_install", "after_install"), names)
    }
}
