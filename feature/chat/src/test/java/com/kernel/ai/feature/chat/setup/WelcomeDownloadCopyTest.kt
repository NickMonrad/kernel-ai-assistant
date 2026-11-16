package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.feature.chat.formatBytesShort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Third welcome row (#1936, owner decision on #1957): size while a download is left, "Chat is ready" when none is. */
class WelcomeDownloadCopyTest {

    @Test
    fun `a download still needed keeps the spec title and names the size`() {
        val bytes = KernelModel.GEMMA_4_E4B.approxSizeBytes

        val copy = welcomeDownloadCopy(bytes)

        assertEquals("Chat needs a one-time download", copy.title)
        assertEquals("About ${formatBytesShort(bytes)}. You choose Wi-Fi or mobile data in step 3.", copy.body)
        assertEquals(WelcomeDownloadIcon.Download, copy.icon)
    }

    @Test
    fun `nothing left to download makes the title Chat is ready with no body and a check icon`() {
        val copy = welcomeDownloadCopy(0L)

        assertEquals("Chat is ready", copy.title)
        assertNull(copy.body)
        assertEquals(WelcomeDownloadIcon.Ready, copy.icon)
    }
}
