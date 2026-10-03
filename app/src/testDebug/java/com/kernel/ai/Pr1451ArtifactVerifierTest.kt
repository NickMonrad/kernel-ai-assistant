package com.kernel.ai

import java.io.File
import java.security.MessageDigest
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Pr1451ArtifactVerifierTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `verified bytes and exact app source are checked before runtime creation`() {
        val file = modelFile("exact model bytes")
        val events = mutableListOf<String>()
        val sourceHead = "a".repeat(40)

        val result = Pr1451ArtifactVerifier.verifyBeforeRuntime(
            file = file,
            appSourceHead = sourceHead,
            suppliedSourceCommit = sourceHead,
            expectedBytes = file.length(),
            expectedSha256 = sha256(file),
            recordPreflight = { check ->
                events += "hash"
                assertTrue(check.passed)
            },
            createRuntime = {
                events += "runtime"
                "started"
            },
        )

        assertEquals("started", result.runtime)
        assertEquals(listOf("hash", "runtime"), events)
    }

    @Test
    fun `source mismatch records preflight and never creates runtime`() {
        val file = modelFile("pinned bytes")
        val events = mutableListOf<String>()

        val failure = runCatching {
            Pr1451ArtifactVerifier.verifyBeforeRuntime(
                file = file,
                appSourceHead = "a".repeat(40),
                suppliedSourceCommit = "b".repeat(40),
                expectedBytes = file.length(),
                expectedSha256 = sha256(file),
                recordPreflight = { check ->
                    events += "hash"
                    assertFalse(check.sourceCommitMatches)
                },
                createRuntime = {
                    events += "runtime"
                    "must not start"
                },
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf("hash"), events)
    }

    @Test
    fun `size or digest drift never creates runtime`() {
        val file = modelFile("original bytes")
        val events = mutableListOf<String>()
        val sourceHead = "c".repeat(40)

        val sizeFailure = runCatching {
            Pr1451ArtifactVerifier.verifyBeforeRuntime(
                file = file,
                appSourceHead = sourceHead,
                suppliedSourceCommit = sourceHead,
                expectedBytes = file.length() + 1,
                expectedSha256 = sha256(file),
                recordPreflight = { events += "size-hash" },
                createRuntime = { events += "runtime-size" },
            )
        }
        val hashFailure = runCatching {
            Pr1451ArtifactVerifier.verifyBeforeRuntime(
                file = file,
                appSourceHead = sourceHead,
                suppliedSourceCommit = sourceHead,
                expectedBytes = file.length(),
                expectedSha256 = "0".repeat(64),
                recordPreflight = { events += "hash-hash" },
                createRuntime = { events += "runtime-hash" },
            )
        }

        assertTrue(sizeFailure.isFailure)
        assertTrue(hashFailure.isFailure)
        assertEquals(listOf("size-hash", "hash-hash"), events)
    }

    @Test
    fun `missing size and digest pins are recorded and never create runtime`() {
        val file = modelFile("unpinned bytes")
        val sourceHead = "b".repeat(40)
        val events = mutableListOf<String>()

        val failure = runCatching {
            Pr1451ArtifactVerifier.verifyBeforeRuntime(
                file = file,
                appSourceHead = sourceHead,
                suppliedSourceCommit = sourceHead,
                expectedBytes = null,
                expectedSha256 = null,
                recordPreflight = { check ->
                    events += "preflight"
                    assertFalse(check.expectedBytesValid)
                    assertFalse(check.expectedSha256Valid)
                },
                createRuntime = {
                    events += "runtime"
                    "must not start"
                },
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf("preflight"), events)
    }

    @Test
    fun `post-run check detects file mutation after valid preflight`() {
        val file = modelFile("before benchmark")
        val sourceHead = "d".repeat(40)
        val expectedSha = sha256(file)
        val preflight = Pr1451ArtifactVerifier.inspect(
            file, sourceHead, sourceHead, file.length(), expectedSha,
        )
        assertTrue(preflight.passed)

        file.writeText("after benchmark")
        val postRun = Pr1451ArtifactVerifier.verifyUnchangedAfterRun(
            file, sourceHead, sourceHead, preflight.expectedBytes, expectedSha, preflight,
        )

        assertFalse(postRun.passed)
        assertFalse(postRun.unchangedSincePreflight)
    }

    private fun modelFile(contents: String): File =
        temporaryDirectory.resolve("model.litertlm").toFile().apply { writeText(contents) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
