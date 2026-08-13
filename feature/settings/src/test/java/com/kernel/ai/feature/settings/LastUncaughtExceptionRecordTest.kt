package com.kernel.ai.feature.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class LastUncaughtExceptionRecordTest {

    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("kernel-uncaught-record-test").toFile()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun recordFile() = File(tempDir, LAST_UNCAUGHT_EXCEPTION_FILE_NAME)

    // ── Formatting ─────────────────────────────────────────────────────────

    @Test
    fun `format includes timestamp thread exception class message and stack`() {
        val timestamp = Instant.parse("2026-08-13T01:43:27.123Z")
        val throwable = IllegalStateException("boom")

        val text = formatUncaughtExceptionRecord(timestamp, "main", throwable)

        assertTrue(text.contains("Timestamp: 2026-08-13T01:43:27.123Z"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("Exception: java.lang.IllegalStateException"))
        assertTrue(text.contains("Message: boom"))
        assertTrue(text.contains("Stack trace:"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("at com.kernel.ai.feature.settings.LastUncaughtExceptionRecordTest"))
    }

    @Test
    fun `format omits message line when the throwable has no message`() {
        val text = formatUncaughtExceptionRecord(Instant.EPOCH, "worker", NullPointerException())

        assertTrue(text.contains("Exception: java.lang.NullPointerException"))
        assertFalse(text.contains("Message:"))
    }

    @Test
    fun `format includes caused-by and suppressed detail naturally produced by the throwable`() {
        val suppressed = IllegalArgumentException("suppressed-cause")
        val cause = IllegalStateException("inner").apply { addSuppressed(suppressed) }
        val top = RuntimeException("outer", cause)

        val text = formatUncaughtExceptionRecord(Instant.EPOCH, "main", top)

        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: inner"))
        assertTrue(text.contains("Suppressed: java.lang.IllegalArgumentException: suppressed-cause"))
    }

    @Test
    fun `record is bounded to the fixed byte limit`() {
        // A deep cause chain produces a stack representation far larger than the bound.
        var throwable: Throwable = RuntimeException("leaf")
        repeat(1_200) { throwable = RuntimeException("level-$it", throwable) }

        val text = formatUncaughtExceptionRecord(Instant.EPOCH, "main", throwable)

        assertTrue(text.toByteArray(Charsets.UTF_8).size <= LAST_UNCAUGHT_EXCEPTION_MAX_BYTES)
        assertTrue(text.endsWith("[... record truncated at $LAST_UNCAUGHT_EXCEPTION_MAX_BYTES bytes ...]\n"))
    }

    // ── Persistence ────────────────────────────────────────────────────────

    @Test
    fun `write then read survives a simulated process restart`() {
        val file = recordFile()
        val original = RuntimeException("persisted")

        assertTrue(writeLastUncaughtExceptionRecord(file, "main", original))

        // A fresh read path (new process) reads the same persisted record.
        val text = readLastUncaughtExceptionRecord(file)
        assertNotNull(text)
        assertTrue(text!!.contains("Exception: java.lang.RuntimeException"))
        assertTrue(text.contains("Message: persisted"))
        assertTrue(text.contains("Thread: main"))
    }

    @Test
    fun `read returns null when no record exists`() {
        assertNull(readLastUncaughtExceptionRecord(recordFile()))
    }

    @Test
    fun `read throws when the record exists but is unreadable`() {
        val file = recordFile()
        file.writeText("existing")
        assumeTrue(file.setReadable(false), "filesystem must honour read permissions")
        assertThrows(IOException::class.java) { readLastUncaughtExceptionRecord(file) }
    }

    @Test
    fun `write failure returns false and does not throw`() {
        // Parent "directory" is actually a regular file, so the write cannot succeed.
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val badFile = File(blocker, "record.txt")

        assertFalse(writeLastUncaughtExceptionRecord(badFile, "main", RuntimeException("x")))
    }

    // ── Handler delegation ──────────────────────────────────────────────────

    @Test
    fun `capture handler delegates exactly once with the original thread and throwable`() {
        val file = recordFile()
        val original = IllegalStateException("original")
        val thread = Thread.currentThread()
        var delegated = 0
        var seenThread: Thread? = null
        var seenThrowable: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { t, e ->
            delegated++
            seenThread = t
            seenThrowable = e
        }

        val handler = createUncaughtExceptionCaptureHandler(file, previous)
        handler.uncaughtException(thread, original)

        assertEquals(1, delegated)
        assertSame(thread, seenThread)
        assertSame(original, seenThrowable)
        assertTrue(file.isFile, "record must be written before delegation")
        assertTrue(file.readText().contains("Exception: java.lang.IllegalStateException"))
    }

    @Test
    fun `capture handler still delegates when persistence fails`() {
        val blocker = File(tempDir, "blocker").apply { writeText("not a directory") }
        val badFile = File(blocker, "record.txt")
        var delegated = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated++ }

        val handler = createUncaughtExceptionCaptureHandler(badFile, previous)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, delegated)
    }

    @Test
    fun `capture handler with null previous writes record without throwing`() {
        val file = recordFile()
        val handler = createUncaughtExceptionCaptureHandler(file, null)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("no-previous"))

        assertTrue(file.isFile)
        assertTrue(file.readText().contains("Message: no-previous"))
    }

    // ── Debug-gated installation ────────────────────────────────────────────

    @Test
    fun `non-debuggable configuration does not install the capture handler`() {
        var installed = false

        val result = installUncaughtExceptionCaptureIfDebuggable(
            debuggable = false,
            recordFile = recordFile(),
            currentHandler = null,
            setHandler = { installed = true },
        )

        assertFalse(result)
        assertFalse(installed)
    }

    @Test
    fun `debuggable configuration installs a working capture handler`() {
        var installedHandler: Thread.UncaughtExceptionHandler? = null
        val file = recordFile()

        val result = installUncaughtExceptionCaptureIfDebuggable(
            debuggable = true,
            recordFile = file,
            currentHandler = null,
            setHandler = { installedHandler = it },
        )

        assertTrue(result)
        assertNotNull(installedHandler)
        installedHandler!!.uncaughtException(Thread.currentThread(), RuntimeException("installed"))
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("Message: installed"))
    }
}
