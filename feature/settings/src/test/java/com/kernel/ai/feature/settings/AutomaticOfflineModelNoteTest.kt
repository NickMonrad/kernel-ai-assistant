package com.kernel.ai.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AutomaticOfflineModelNoteTest {
    @Test
    fun `automatic card points to the asr4all row once its model is deleted`() {
        assertEquals(
            "The offline speech model isn't downloaded yet. Download it in asr4all streaming below.",
            automaticOfflineModelNote(asr4allDownloaded = false),
        )
    }

    @Test
    fun `automatic card has no note while the offline model is downloaded`() {
        assertNull(automaticOfflineModelNote(asr4allDownloaded = true))
    }

    @Test
    fun `automatic card has no note before the download state is known`() {
        assertNull(automaticOfflineModelNote(asr4allDownloaded = null))
    }
}
