package com.kernel.ai

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal data class Pr1451ArtifactCheck(
    val appSourceHead: String,
    val suppliedSourceCommit: String?,
    val appSourceHeadValid: Boolean,
    val suppliedSourceCommitValid: Boolean,
    val sourceCommitMatches: Boolean,
    val expectedBytes: Long?,
    val expectedBytesValid: Boolean,
    val actualBytes: Long?,
    val sizeMatches: Boolean,
    val expectedSha256: String?,
    val expectedSha256Valid: Boolean,
    val actualSha256: String?,
    val sha256Matches: Boolean,
) {
    val passed: Boolean
        get() = appSourceHeadValid && suppliedSourceCommitValid && sourceCommitMatches &&
            expectedBytesValid && sizeMatches && expectedSha256Valid && sha256Matches

    fun failureMessage(): String = buildList {
        if (!appSourceHeadValid) add("app source head is not a full 40-character git SHA")
        if (!suppliedSourceCommitValid) add("source_commit must be a full 40-character git SHA")
        if (!sourceCommitMatches) add("source_commit=$suppliedSourceCommit does not match app head=$appSourceHead")
        if (!expectedBytesValid) add("expected_bytes must be a positive integer")
        if (!sizeMatches) add("model size mismatch: expected=$expectedBytes actual=$actualBytes")
        if (!expectedSha256Valid) add("expected_sha256 must be 64 hexadecimal characters")
        if (!sha256Matches) add("model SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha256")
    }.joinToString("; ").ifEmpty { "#1451 artifact pins match" }
}

internal data class Pr1451VerifiedRuntime<T>(
    val artifactCheck: Pr1451ArtifactCheck,
    val runtime: T,
)

internal data class Pr1451PostRunCheck(
    val artifactCheck: Pr1451ArtifactCheck,
    val unchangedSincePreflight: Boolean,
) {
    val passed: Boolean
        get() = artifactCheck.passed && unchangedSincePreflight
}

/** Debug-variant-only pin checks for the #1451 benchmark; never used by production code. */
internal object Pr1451ArtifactVerifier {
    private val gitShaPattern = Regex("[0-9a-fA-F]{40}")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private const val HASH_BUFFER_BYTES = 1024 * 1024

    fun inspect(
        file: File,
        appSourceHead: String,
        suppliedSourceCommit: String?,
        expectedBytes: Long?,
        expectedSha256: String?,
    ): Pr1451ArtifactCheck {
        val appSourceHeadValid = appSourceHead.matches(gitShaPattern)
        val suppliedSourceCommitValid = suppliedSourceCommit?.matches(gitShaPattern) == true
        val sourceCommitMatches = appSourceHeadValid && suppliedSourceCommitValid &&
            appSourceHead.equals(suppliedSourceCommit, ignoreCase = true)
        val expectedBytesValid = expectedBytes != null && expectedBytes > 0L
        val expectedSha256Valid = expectedSha256?.matches(sha256Pattern) == true
        val actualBytes = file.takeIf(File::isFile)?.length()
        val actualSha256 = if (actualBytes != null && expectedBytesValid && expectedSha256Valid) {
            sha256(file)
        } else {
            null
        }
        return Pr1451ArtifactCheck(
            appSourceHead = appSourceHead,
            suppliedSourceCommit = suppliedSourceCommit,
            appSourceHeadValid = appSourceHeadValid,
            suppliedSourceCommitValid = suppliedSourceCommitValid,
            sourceCommitMatches = sourceCommitMatches,
            expectedBytes = expectedBytes,
            expectedBytesValid = expectedBytesValid,
            actualBytes = actualBytes,
            sizeMatches = expectedBytesValid && actualBytes == expectedBytes,
            expectedSha256 = expectedSha256?.lowercase(),
            expectedSha256Valid = expectedSha256Valid,
            actualSha256 = actualSha256,
            sha256Matches = expectedSha256Valid && actualSha256.equals(expectedSha256, ignoreCase = true),
        )
    }

    fun <T> verifyBeforeRuntime(
        file: File,
        appSourceHead: String,
        suppliedSourceCommit: String?,
        expectedBytes: Long?,
        expectedSha256: String?,
        recordPreflight: (Pr1451ArtifactCheck) -> Unit,
        createRuntime: () -> T,
    ): Pr1451VerifiedRuntime<T> {
        val check = inspect(file, appSourceHead, suppliedSourceCommit, expectedBytes, expectedSha256)
        recordPreflight(check)
        check(check.passed) { check.failureMessage() }
        return Pr1451VerifiedRuntime(check, createRuntime())
    }

    fun verifyUnchangedAfterRun(
        file: File,
        appSourceHead: String,
        suppliedSourceCommit: String?,
        expectedBytes: Long?,
        expectedSha256: String?,
        preflight: Pr1451ArtifactCheck,
    ): Pr1451PostRunCheck {
        val after = inspect(file, appSourceHead, suppliedSourceCommit, expectedBytes, expectedSha256)
        return Pr1451PostRunCheck(
            artifactCheck = after,
            unchangedSincePreflight = after.actualBytes == preflight.actualBytes &&
                after.actualSha256 == preflight.actualSha256,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
