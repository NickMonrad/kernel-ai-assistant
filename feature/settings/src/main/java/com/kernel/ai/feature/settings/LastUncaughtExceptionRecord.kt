package com.kernel.ai.feature.settings

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Debug-build-only diagnostic capture (#1461): retains the most recent uncaught
 * Java/Kotlin exception across process restart so the Settings -> About export can
 * include the concrete stack after the process has died and been reopened.
 *
 * This is deliberately minimal:
 * - one private app-local file holding only the latest record;
 * - a fixed size bound;
 * - best-effort synchronous persistence that never alters normal crash handling.
 *
 * The capture handler is only installed in debuggable builds (see
 * [installUncaughtExceptionCaptureIfDebuggable]); release builds never install it.
 */
const val LAST_UNCAUGHT_EXCEPTION_FILE_NAME = "last_uncaught_exception.txt"

/** Fixed bound for the retained record, in UTF-8 bytes. */
const val LAST_UNCAUGHT_EXCEPTION_MAX_BYTES = 64 * 1024

private const val TRUNCATION_MARKER = "\n[... record truncated at " +
    "$LAST_UNCAUGHT_EXCEPTION_MAX_BYTES bytes ...]\n"

/** App-private location of the single retained record. */
fun lastUncaughtExceptionRecordFile(context: Context): File =
    File(context.filesDir, LAST_UNCAUGHT_EXCEPTION_FILE_NAME)

/**
 * Formats a bounded human-readable record for [throwable].
 *
 * The stack uses the normal JVM throwable representation (via `printStackTrace`),
 * so `Caused by`/`Suppressed` detail is included where the throwable naturally
 * carries it. The complete record is bounded to [LAST_UNCAUGHT_EXCEPTION_MAX_BYTES].
 */
fun formatUncaughtExceptionRecord(
    timestampUtc: Instant,
    threadName: String,
    throwable: Throwable,
): String {
    val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
    val body = buildString {
        appendLine("Timestamp: $timestampUtc")
        appendLine("Thread: $threadName")
        appendLine("Exception: ${throwable.javaClass.name}")
        throwable.message?.takeIf { it.isNotBlank() }?.let { appendLine("Message: $it") }
        appendLine("Stack trace:")
        append(stack)
    }
    return boundToMaxBytes(body)
}

/**
 * Synchronously persists [throwable] as the latest retained record.
 *
 * Best-effort by contract: returns true on success and never throws, so a
 * persistence failure can never suppress or alter the crash being handled.
 */
fun writeLastUncaughtExceptionRecord(file: File, threadName: String, throwable: Throwable): Boolean =
    runCatching {
        val record = formatUncaughtExceptionRecord(Instant.now(), threadName, throwable)
        file.parentFile?.mkdirs()
        file.writeBytes(record.toByteArray(Charsets.UTF_8))
    }.isSuccess

/**
 * Returns the retained record text, or null when no record exists.
 *
 * Throws [java.io.IOException] when the record exists but cannot be read, so callers
 * can distinguish "absent" from "unreadable" for export warnings.
 */
fun readLastUncaughtExceptionRecord(file: File): String? {
    if (!file.isFile) return null
    return file.readText(Charsets.UTF_8).take(LAST_UNCAUGHT_EXCEPTION_MAX_BYTES)
}

/**
 * Thin wrapper that persists the uncaught throwable, then delegates exactly once to
 * [previous] with the original thread and throwable so Android still terminates and
 * records the crash normally. On Android the platform always installs a default
 * handler before the application starts, so [previous] is non-null in practice;
 * if it is somehow null the write still happens and no delegation is attempted.
 */
fun createUncaughtExceptionCaptureHandler(
    recordFile: File,
    previous: Thread.UncaughtExceptionHandler?,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
    writeLastUncaughtExceptionRecord(recordFile, thread.name, throwable)
    previous?.uncaughtException(thread, throwable)
}

/**
 * Installs the capture handler when [debuggable] is true; otherwise leaves the process
 * handler untouched. Returns true when a capture handler was installed.
 *
 * [setHandler] is injectable so the gating can be unit-tested without mutating the
 * process-wide handler.
 */
fun installUncaughtExceptionCaptureIfDebuggable(
    debuggable: Boolean,
    recordFile: File,
    currentHandler: Thread.UncaughtExceptionHandler?,
    setHandler: (Thread.UncaughtExceptionHandler) -> Unit,
): Boolean {
    if (!debuggable) return false
    setHandler(createUncaughtExceptionCaptureHandler(recordFile, currentHandler))
    return true
}

/**
 * Truncates [body] to [LAST_UNCAUGHT_EXCEPTION_MAX_BYTES] UTF-8 bytes on a character
 * boundary, appending a marker. O(n) — decodes the byte prefix once.
 */
private fun boundToMaxBytes(body: String): String {
    val marker = TRUNCATION_MARKER
    val full = body.toByteArray(Charsets.UTF_8)
    if (full.size <= LAST_UNCAUGHT_EXCEPTION_MAX_BYTES) return body
    val maxBodyBytes = LAST_UNCAUGHT_EXCEPTION_MAX_BYTES - marker.toByteArray(Charsets.UTF_8).size
    // A cut inside a multi-byte UTF-8 sequence decodes to U+FFFD; drop that partial char.
    val prefix = String(full, 0, maxBodyBytes, Charsets.UTF_8).trimEnd('\uFFFD')
    return prefix + marker
}
