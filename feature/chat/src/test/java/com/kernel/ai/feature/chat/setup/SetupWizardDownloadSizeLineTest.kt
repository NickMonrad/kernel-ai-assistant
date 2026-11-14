package com.kernel.ai.feature.chat.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupWizardDownloadSizeLineTest {

    @Test
    fun `zero bytes left says nothing to download on a re-run`() {
        val line = downloadSizeLine(totalBytes = 0L, choiceRecorded = true)

        assertTrue(line.startsWith("Nothing left to download"))
        assertFalse(line.contains("About"))
        assertFalse(line.contains("0 B"))
    }

    @Test
    fun `zero bytes left says nothing to download on a first run too`() {
        val line = downloadSizeLine(totalBytes = 0L, choiceRecorded = false)

        assertTrue(line.startsWith("Nothing left to download"))
        assertFalse(line.contains("0 B"))
    }

    @Test
    fun `a negative estimate is treated as nothing left`() {
        assertEquals(
            downloadSizeLine(totalBytes = 0L, choiceRecorded = true),
            downloadSizeLine(totalBytes = -1L, choiceRecorded = true),
        )
    }

    @Test
    fun `non-zero bytes keep the still-to-download wording on a re-run`() {
        val line = downloadSizeLine(totalBytes = 2_500_000_000L, choiceRecorded = true)

        assertTrue(line.startsWith("About 2.5 GB still to download."))
    }

    @Test
    fun `non-zero bytes keep the total wording on a first run`() {
        val line = downloadSizeLine(totalBytes = 300_000_000L, choiceRecorded = false)

        assertTrue(line.startsWith("About 300 MB in total."))
    }
}
