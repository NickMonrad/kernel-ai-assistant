package com.kernel.ai

import com.kernel.ai.core.inference.terms.TERMS_VERSION
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TermsParityTest {
    @Test
    fun `root TERMS has the same text as the bundled copy`() {
        val bundled = appAssetsDirectory().resolve("legal/TERMS.md")
        val root = repositoryRoot().resolve("TERMS.md")

        assertEquals(bundled.readText(), root.readText())
    }

    @Test
    fun `accepted Terms version is the effective date of the bundled document`() {
        val effectiveLine = Regex("""_Effective date: (\d{1,2} \w+ \d{4})\._""")
            .find(repositoryRoot().resolve("TERMS.md").readText())
            ?.groupValues?.get(1)
            ?: error("TERMS.md has no '_Effective date: ..._' line")
        val effective = LocalDate.parse(effectiveLine, DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))

        assertEquals(TERMS_VERSION, effective.toString())
    }

    private fun appAssetsDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory.resolve("src/main/assets"),
            workingDirectory.resolve("app/src/main/assets"),
        ).firstOrNull { it.resolve("legal/TERMS.md").isFile }
            ?: error("Cannot locate app/src/main/assets from ${workingDirectory.path}")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            workingDirectory,
            workingDirectory.parentFile,
        ).firstOrNull { it.resolve("TERMS.md").isFile && it.resolve("app").isDirectory }
            ?: error("Cannot locate repository root TERMS.md from ${workingDirectory.path}")
    }
}
