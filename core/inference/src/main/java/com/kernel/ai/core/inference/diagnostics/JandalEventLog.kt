package com.kernel.ai.core.inference.diagnostics

import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "KernelAI"

/** File under the app's filesDir that persists events across process death. */
const val JANDAL_EVENTS_FILE_NAME = "jandal_events.log"

/** WorkManager phases recorded for model downloads. Fixed enum values only (#1870). */
enum class WorkerPhase { ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

/** Download state kinds recorded for model downloads. Fixed enum values only (#1870). */
enum class DownloadStateKind { NOT_DOWNLOADED, DOWNLOADED, ERROR, DOWNLOADING }

/** Lifecycle and permission outcomes. Fixed enum values only (#1870). */
enum class LifecycleOutcome { STARTED, STOPPED, REFUSED_NO_MIC_PERMISSION, GRANTED, DENIED }

/** TTS failure categories. Fixed enum values only (#1870). */
enum class TtsFailure { UNAVAILABLE, ENGINE_START }

/** Intent dispatch outcome category. Fixed enum values only (#1870). */
enum class IntentOutcome { SUCCESS, FAILURE, PARSE_ERROR, UNKNOWN_SKILL, CAPABILITY_REQUIRED, OTHER }

/** Most events kept in memory and on disk (ring buffer; oldest evicted first). */
const val JANDAL_EVENT_CAPACITY = 500

/** Events older than this are dropped on load and on export (#1870). */
const val JANDAL_EVENT_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Most fields one event may carry; extras are dropped. */
internal const val JANDAL_EVENT_MAX_FIELDS = 8

/** A persisted file larger than this is not read; it is replaced on the next rewrite. */
internal const val JANDAL_EVENT_MAX_FILE_BYTES = 512L * 1024

/** Stands in for any value or identifier that is not on the allowlist. */
internal const val JANDAL_EVENT_REDACTED = "[redacted]"

/** Category, name and field keys: short lowercase snake_case constants written in code. */
private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,31}")

/** Enum constant names, e.g. `NATIVE`, `DOWNLOADING`. */
private val ENUM_TOKEN = Regex("[A-Z][A-Z0-9_]{0,47}")

/** Boundary between a lowercase letter and an uppercase letter, e.g. `SherpaExperimental`. */
private val CAMEL_BOUNDARY = Regex("(?<=[a-z])(?=[A-Z])")

/**
 * Counts, durations and ids. Limited to nine digits so a phone number or epoch timestamp
 * cannot pass as a number. Use elapsed milliseconds, not epoch time.
 */
private val NUMBER = Regex("-?\\d{1,9}(\\.\\d{1,3})?")

/**
 * Allowed field values, in the order they are tested:
 * `true`/`false`, a bounded number, or an enum constant name. Free-form strings are never
 * allowed, so call sites cannot pass transcripts, names, numbers or params by accident.
 */
internal fun sanitizeJandalValue(raw: Any?): String = when (raw) {
    is Boolean -> raw.toString()
    is Int, is Long -> raw.toString().takeIf { NUMBER.matches(it) } ?: JANDAL_EVENT_REDACTED
    is Float, is Double -> {
        val d = (raw as Number).toDouble()
        if (d.isFinite()) d.toString().takeIf { NUMBER.matches(it) } ?: JANDAL_EVENT_REDACTED
        else JANDAL_EVENT_REDACTED
    }
    is Enum<*> -> raw.name.replace(CAMEL_BOUNDARY, "_").uppercase().takeIf { ENUM_TOKEN.matches(it) } ?: JANDAL_EVENT_REDACTED
    else -> JANDAL_EVENT_REDACTED
}

/** Re-applies the allowlist to a value read back from disk. Only rendered tokens are valid. */
private fun sanitizeStoredValue(text: String): String = when {
    text == "true" || text == "false" -> text
    NUMBER.matches(text) || ENUM_TOKEN.matches(text) -> text
    else -> JANDAL_EVENT_REDACTED
}

/** One structured app event. Every field value has passed the allowlist. */
data class JandalEvent(
    val timestampMs: Long,
    val category: String,
    val name: String,
    val fields: Map<String, String>,
)

/**
 * Builds a [JandalEvent]. Category and name must match [IDENTIFIER] or become
 * [JANDAL_EVENT_REDACTED]. Field keys that do not match are dropped. Values go through
 * [sanitizeJandalValue].
 */
internal fun buildJandalEvent(
    timestampMs: Long,
    category: String,
    name: String,
    fields: Map<String, Any?>,
): JandalEvent {
    val out = LinkedHashMap<String, String>()
    for ((key, value) in fields) {
        if (out.size >= JANDAL_EVENT_MAX_FIELDS) break
        if (!IDENTIFIER.matches(key)) continue
        out[key] = sanitizeJandalValue(value)
    }
    return JandalEvent(
        timestampMs = timestampMs,
        category = if (IDENTIFIER.matches(category)) category else JANDAL_EVENT_REDACTED,
        name = if (IDENTIFIER.matches(name)) name else JANDAL_EVENT_REDACTED,
        fields = out,
    )
}

/**
 * Bounded, thread-safe in-memory ring of [JandalEvent]s. Eviction drops the oldest event
 * once [capacity] is exceeded.
 */
class JandalEventBuffer(private val capacity: Int = JANDAL_EVENT_CAPACITY) {
    private val lock = Any()
    private val events = ArrayDeque<JandalEvent>()

    fun add(event: JandalEvent) = synchronized(lock) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }

    /** Adds older events in front, keeping the newest when the buffer overflows. */
    fun prepend(older: List<JandalEvent>) = synchronized(lock) {
        for (event in older.asReversed()) events.addFirst(event)
        while (events.size > capacity) events.removeFirst()
    }

    fun snapshot(): List<JandalEvent> = synchronized(lock) { events.toList() }
}

/**
 * Persists events to one line per event. Only the single writer thread may call this class.
 * [persisted] mirrors exactly what is on disk, so a rewrite can never duplicate or drop an
 * event that an earlier append already wrote.
 */
internal class JandalEventStore(
    private val file: File,
    private val buffer: JandalEventBuffer,
    private val capacity: Int = JANDAL_EVENT_CAPACITY,
) {
    private val persisted = ArrayDeque<JandalEvent>()
    private var linesOnDisk = 0

    /** Reads the file on the writer thread. Oversized files are not read; the next append replaces them. */
    fun load(nowMs: Long) {
        if (!file.isFile) return
        if (file.length() > JANDAL_EVENT_MAX_FILE_BYTES) {
            // Silent by design: core:inference has no Android Log stubs in JVM tests.
            linesOnDisk = 2 * capacity
            return
        }
        val lines = file.readLines().filter { it.isNotBlank() }
        linesOnDisk = lines.size
        val events = lines.mapNotNull { parseJandalEventLine(it) }
            .filter { nowMs - it.timestampMs in 0..JANDAL_EVENT_MAX_AGE_MS }
            .takeLast(capacity)
        persisted.addAll(events)
        buffer.prepend(events)
        if (linesOnDisk >= 2 * capacity) rewrite()
    }

    fun append(event: JandalEvent) {
        persisted.addLast(event)
        while (persisted.size > capacity) persisted.removeFirst()
        linesOnDisk++
        if (linesOnDisk >= 2 * capacity) rewrite() else file.appendText(event.toLine() + "\n")
    }

    /** Rewrites from [persisted] via temp file and rename, so the file holds at most [capacity] lines. */
    private fun rewrite() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(persisted.joinToString(separator = "") { it.toLine() + "\n" })
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        linesOnDisk = persisted.size
    }
}

internal fun JandalEvent.toLine(): String = buildString {
    append(timestampMs).append('|').append(category).append('|').append(name)
    for ((k, v) in fields) append('|').append(k).append('=').append(v)
}

/** Parses one line written by [toLine]; returns null for a malformed line. Values are re-sanitised. */
internal fun parseJandalEventLine(line: String): JandalEvent? {
    val parts = line.split('|')
    if (parts.size < 3) return null
    val timestamp = parts[0].toLongOrNull() ?: return null
    val category = parts[1]
    val name = parts[2]
    if (!IDENTIFIER.matches(category) || !IDENTIFIER.matches(name)) return null
    val fields = LinkedHashMap<String, String>()
    for (pair in parts.drop(3)) {
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val key = pair.substring(0, eq)
        if (!IDENTIFIER.matches(key)) return null
        fields.putIfAbsent(key, sanitizeStoredValue(pair.substring(eq + 1)))
    }
    return JandalEvent(timestamp, category, name, fields)
}

private val EXPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

/** One export line: `MM-dd HH:mm:ss.SSS category name key=value ...`. */
internal fun JandalEvent.toExportLine(zone: ZoneId): String = buildString {
    append(EXPORT_TIME.format(Instant.ofEpochMilli(timestampMs).atZone(zone)))
    append(' ').append(category).append(' ').append(name)
    for ((k, v) in fields) append(' ').append(k).append('=').append(v)
}

/** Export lines for events inside the age window, oldest first. */
internal fun renderJandalEventLines(
    events: List<JandalEvent>,
    nowMs: Long,
    zone: ZoneId,
): List<String> = events
    .filter { nowMs - it.timestampMs in 0..JANDAL_EVENT_MAX_AGE_MS }
    .map { it.toExportLine(zone) }

/**
 * App-wide entry point for Jandal's own diagnostic events (#1870). Logcat is hidden on some
 * OEM builds (MagicOS), so the support export reads these instead.
 *
 * Usage: `JandalEvents.record("voice", "stt_route", "route" to SttRoute.NATIVE, "ready" to true)`.
 * Values must be booleans, counts (up to nine digits) or enum constants. Strings are always
 * redacted. Category and name must be lowercase snake_case constants.
 *
 * Writes go through one daemon thread. [record] never blocks on disk, and no failure in the
 * diagnostics path can reach the app.
 */
object JandalEvents {
    private val buffer = JandalEventBuffer(JANDAL_EVENT_CAPACITY)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jandal-events").apply { isDaemon = true }
    }

    // Accessed only on the writer thread.
    private var store: JandalEventStore? = null
    private var installed = false
    private val beforeInstall = ArrayDeque<JandalEvent>()

    /**
     * Loads persisted events and starts persisting. Idempotent. Events recorded before this
     * call are kept in memory and written once the file is open.
     */
    fun install(file: File) = submit {
        if (installed) return@submit
        installed = true
        val s = JandalEventStore(file, buffer)
        s.load(System.currentTimeMillis())
        store = s
        while (beforeInstall.isNotEmpty()) s.append(beforeInstall.removeFirst())
    }

    fun record(category: String, name: String, vararg fields: Pair<String, Any?>) =
        record(category, name, fields.toMap())

    fun record(category: String, name: String, fields: Map<String, Any?>) {
        val event = try {
            buildJandalEvent(System.currentTimeMillis(), category, name, fields)
        } catch (t: Throwable) {
            return
        }
        buffer.add(event)
        submit {
            val s = store
            if (s != null) {
                s.append(event)
            } else {
                beforeInstall.addLast(event)
                while (beforeInstall.size > JANDAL_EVENT_CAPACITY) beforeInstall.removeFirst()
            }
        }
    }

    /** Export lines for the support attachment. Covers this session plus any loaded history. */
    fun exportLines(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<String> =
        renderJandalEventLines(buffer.snapshot(), nowMs, zone)

    /** Blocks until queued writes finish. For tests only; not for production code. */
    fun awaitWritesForTest() {
        writer.submit(Callable {}).get()
    }

    private fun submit(block: () -> Unit) {
        try {
            writer.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "Jandal event write failed; diagnostics skipped", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Writer shut down: diagnostics are best-effort.
        }
    }
}
