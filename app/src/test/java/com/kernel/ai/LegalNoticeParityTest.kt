package com.kernel.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class LegalNoticeParityTest {
    @Test
    fun `root NOTICE has the same text as the bundled licences notice`() {
        val bundled = appAssetsDirectory().resolve("legal/NOTICE.txt")
        val root = repositoryRoot().resolve("NOTICE")

        assertEquals(bundled.readText(), root.readText())
    }

    private fun appAssetsDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory.resolve("src/main/assets"),
            workingDirectory.resolve("app/src/main/assets"),
        ).firstOrNull { it.resolve("legal/NOTICE.txt").isFile }
            ?: error("Cannot locate app/src/main/assets from ${workingDirectory.path}")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory,
            workingDirectory.parentFile,
        ).firstOrNull { it.resolve("NOTICE").isFile && it.resolve("app").isDirectory }
            ?: error("Cannot locate repository root NOTICE from ${workingDirectory.path}")
    }
}
