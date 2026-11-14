package com.kernel.ai.core.inference.diagnostics

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "KernelAI"

/** File under the app's filesDir that persists events across process death. */
const val JANDAL_EVENTS_FILE_NAME = "jandal_events.log"

/** Most events kept in memory and on disk (ring buffer; oldest evicted first). */
const val JANDAL_EVENT_CAPACITY = 500

/** Events older than this are dropped on load and on export (#1870). */
const val JANDAL_EVENT_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Most fields one event may carry; extras are dropped. */
internal const val JANDAL_EVENT_MAX_FIELDS = 8

/** Replaces any value that is not a safe identifier. */
internal const val JANDAL_EVENT_REDACTED = "[redacted]"

private const val MAX_TOKEN_LENGTH = 48
private val SAFE_TOKEN = Regex("[A-Za-z0-9_.:\\-]{1,$MAX_TOKEN_LENGTH}")

/** Phone numbers and other long digit runs are never safe identifiers. */
private val LONG_DIGIT_RUN = Regex("\\d{7,}")

/** One structured app event. Every string field has passed [sanitizeJandalValue]. */
data class JandalEvent(
    val timestampMs: Long,
    val category: String,
    val name: String,
    val fields: Map<String, String>,
)

/**
 * Builds a sanitised [JandalEvent]. Callers pass categories, enum names, ids, counts,
 * durations and booleans. Free text (transcripts, message bodies, names, numbers,
 * raw tool params) is replaced with [JANDAL_EVENT_REDACTED]: a value survives only if it
 * is a short identifier made of letters, digits and `_ . : -`, with no run of seven digits.
 */
internal fun buildJandalEvent(
    timestampMs: Long,
    category: Any?,
    name: Any?,
    fields: Map<String, Any?>,
): JandalEvent {
    val sanitizedFields = LinkedHashMap<String, String>()
    for ((key, value) in fields) {
        if (sanitizedFields.size >= JANDAL_EVENT_MAX_FIELDS) break
        sanitizedFields[sanitizeJandalValue(key)] = sanitizeJandalValue(value)
    }
    return JandalEvent(
        timestampMs = timestampMs,
        category = sanitizeJandalValue(category),
        name = sanitizeJandalValue(name),
        fields = sanitizedFields,
    )
}

/** Returns [raw] when it is a safe identifier, otherwise [JANDAL_EVENT_REDACTED]. */
internal fun sanitizeJandalValue(raw: Any?): String {
    val text = when (raw) {
        is String -> raw
        is Enum<*> -> raw.name
        is Boolean, is Int, is Long -> raw.toString()
        is Float -> if (raw.isFinite()) raw.toString() else return JANDAL_EVENT_REDACTED
        is Double -> if (raw.isFinite()) raw.toString() else return JANDAL_EVENT_REDACTED
        else -> return JANDAL_EVENT_REDACTED
    }
    return if (SAFE_TOKEN.matches(text) && !LONG_DIGIT_RUN.containsMatchIn(text)) {
        text
    } else {
        JANDAL_EVENT_REDACTED
    }
}

/**
 * Bounded, thread-safe in-memory ring of [JandalEvent]s. Eviction drops the oldest event
 * once [capacity] is exceeded.
 */
class JandalEventBuffer(private val capacity: Int = JANDAL_EVENT_CAPACITY) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<JandalEvent>()

    fun add(event: JandalEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > capacity) events.removeFirst()
        }
    }

    /** Inserts older [olderEvents] (oldest first) before everything already held. */
    fun prepend(olderEvents: List<JandalEvent>) {
        synchronized(lock) {
            for (event in olderEvents.asReversed()) events.addFirst(event)
            while (events.size > capacity) events.removeFirst()
        }
    }

    /** Oldest first. */
    fun snapshot(): List<JandalEvent> = synchronized(lock) { events.toList() }
}

/**
 * Persists a [JandalEventBuffer] to one line-per-event text file so events survive process
 * death. Lines are appended; the file is rewritten from memory once [capacity] lines have
 * been appended since the last rewrite, which bounds the file to about twice [capacity].
 * Not thread-safe: the caller serialises calls.
 */
internal class JandalEventStore(
    private val file: File,
    private val buffer: JandalEventBuffer,
    private val capacity: Int = JANDAL_EVENT_CAPACITY,
) {
    private var appendsSinceRewrite = 0

    /** Reads the file, keeping only events inside the age window. A missing or unreadable file is empty. */
    fun load(nowMs: Long) {
        val loaded = try {
            if (file.exists()) file.readLines() else emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "JandalEvents: could not read event file: ${e.javaClass.simpleName}")
            emptyList()
        }
        val recent = loaded
            .mapNotNull(::parseJandalEventLine)
            .filter { nowMs - it.timestampMs in 0..JANDAL_EVENT_MAX_AGE_MS }
        buffer.prepend(recent)
    }

    fun append(event: JandalEvent) {
        try {
            file.appendText(event.toLine() + "\n")
            appendsSinceRewrite++
            if (appendsSinceRewrite >= capacity) rewrite()
        } catch (e: IOException) {
            Log.w(TAG, "JandalEvents: append failed: ${e.javaClass.simpleName}")
        }
    }

    private fun rewrite() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(buffer.snapshot().joinToString(separator = "") { it.toLine() + "\n" })
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        appendsSinceRewrite = 0
    }
}

internal fun JandalEvent.toLine(): String = buildString {
    append(timestampMs).append('|').append(category).append('|').append(name)
    fields.forEach { (key, value) -> append('|').append(key).append('=').append(value) }
}

/** Parses one line written by [toLine]; returns null for a malformed line. Values are re-sanitised. */
internal fun parseJandalEventLine(line: String): JandalEvent? {
    val parts = line.split('|')
    if (parts.size < 3) return null
    val timestamp = parts[0].toLongOrNull() ?: return null
    val fields = LinkedHashMap<String, Any?>()
    for (part in parts.drop(3)) {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        fields[part.substring(0, eq)] = part.substring(eq + 1)
    }
    return buildJandalEvent(timestamp, parts[1], parts[2], fields)
}

private val EXPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

/** One export line: `MM-dd HH:mm:ss.SSS category name key=value ...`. */
internal fun JandalEvent.toExportLine(zone: ZoneId): String = buildString {
    append(EXPORT_TIME.withZone(zone).format(Instant.ofEpochMilli(timestampMs)))
    append(' ').append(category).append(' ').append(name)
    fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
}

/**
 * App-wide entry point for Jandal's own diagnostic events (#1870). Logcat is hidden on some
 * OEM builds (MagicOS), so the support export reads these instead.
 *
 * Usage: `JandalEvents.record("voice", "stt_ready", "engine" to "native", "ms" to 240)`.
 * Pass only categories, enum names, ids, counts, durations and booleans. Never pass
 * transcripts, message bodies, contact names or numbers, or raw tool params. Anything that
 * looks like free text is redacted, but that is a backstop and not a licence to send it.
 */
object JandalEvents {
    private val buffer = JandalEventBuffer()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jandal-events").apply { isDaemon = true }
    }

    @Volatile
    private var store: JandalEventStore? = null

    /** Loads persisted events from [file] and persists later events there. Call once at app start. */
    fun install(file: File) {
        val newStore = JandalEventStore(file, buffer)
        newStore.load(System.currentTimeMillis())
        store = newStore
    }

    fun record(category: String, name: String, vararg fields: Pair<String, Any?>) {
        record(category, name, fields.toMap())
    }

    fun record(category: String, name: String, fields: Map<String, Any?>) {
        val event = buildJandalEvent(System.currentTimeMillis(), category, name, fields)
        buffer.add(event)
        val target = store ?: return
        try {
            writer.execute { target.append(event) }
        } catch (_: RejectedExecutionException) {
            // Executor shut down (process exit); the in-memory copy is still recorded.
        }
    }

    /** Export lines for events inside the age window, oldest first. */
    fun exportLines(
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> = renderJandalEventLines(buffer.snapshot(), nowMs, zone)
}

/** Export lines for events inside the age window, oldest first. */
internal fun renderJandalEventLines(
    events: List<JandalEvent>,
    nowMs: Long,
    zone: ZoneId,
): List<String> = events
    .filter { nowMs - it.timestampMs in 0..JANDAL_EVENT_MAX_AGE_MS }
    .map { it.toExportLine(zone) }
