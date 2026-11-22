package com.kernel.ai.core.inference.download

import com.kernel.ai.core.inference.terms.TERMS_NOT_ACCEPTED_MESSAGE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CancelledWorkerDownloadStateTest {

    @Test
    fun `a cancelled job keeps the Terms refusal the download gate left`() {
        val refusal = DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE)
        assertNull(cancelledWorkerDownloadState(refusal, "/models/a.bin", filePresent = false))
        assertNull(cancelledWorkerDownloadState(refusal, "/models/a.bin", filePresent = true))
    }

    @Test
    fun `a cancelled job after an ordinary error reads as not downloaded`() {
        assertEquals(
            DownloadState.NotDownloaded,
            cancelledWorkerDownloadState(DownloadState.Error("network down"), "/models/a.bin", filePresent = false),
        )
    }

    @Test
    fun `a cancelled job with the file on disk reads as downloaded`() {
        assertEquals(
            DownloadState.Downloaded(localPath = "/models/a.bin"),
            cancelledWorkerDownloadState(null, "/models/a.bin", filePresent = true),
        )
    }
}
