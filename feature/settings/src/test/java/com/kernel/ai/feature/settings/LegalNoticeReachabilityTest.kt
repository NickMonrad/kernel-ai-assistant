package com.kernel.ai.feature.settings

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every native notice shipped in the release metadata must be reachable from the
 * Legal & privacy screen, so a notice can't be bundled in the APK without a
 * user-visible path to it.
 */
class LegalNoticeReachabilityTest {
    private val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/release-attribution-metadata.json").isFile }

    @Test
    fun everyNativeNoticeAssetIsListedAsRuntimeNotice() {
        val metadata = File(repoRoot, "app/release-attribution-metadata.json").readText()
        val section = metadata.substringAfter("\"native_notice_assets\"")
        val nativeNoticeKeys = Regex("\"([^\"]+\\.txt)\"\\s*:\\s*\"[0-9a-f]{64}\"")
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(nativeNoticeKeys.isNotEmpty())

        val reachable = RUNTIME_NOTICES.map { it.second }.toSet()
        val unreachable = nativeNoticeKeys - reachable
        assertTrue(unreachable.isEmpty()) { "Native notices not reachable from Legal & privacy: $unreachable" }
        nativeNoticeKeys.forEach { name ->
            assertTrue(File(repoRoot, "app/src/main/assets/legal/$name").isFile) { "Missing legal asset $name" }
        }
    }
}
