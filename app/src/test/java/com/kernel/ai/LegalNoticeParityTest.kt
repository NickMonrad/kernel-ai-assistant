package com.kernel.ai

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.io.File

class LegalNoticeParityTest {
    @Test
    fun `root NOTICE is byte-identical to the bundled licences notice`() {
        val bundled = appAssetsDirectory().resolve("legal/NOTICE.txt")
        val root = repositoryRoot().resolve("NOTICE")

        assertArrayEquals(bundled.readBytes(), root.readBytes())
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
