package com.kernel.ai.core.inference.terms

import com.kernel.ai.core.inference.download.DownloadState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TermsRefusalTest {

    @Test
    fun `the download gate's Terms refusal is kept, so a cancelled job cannot replace it`() {
        assertTrue(DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE).isTermsRefusal())
    }

    @Test
    fun `other errors and states are not Terms refusals`() {
        assertFalse(DownloadState.Error("network down").isTermsRefusal())
        assertFalse(DownloadState.NotDownloaded.isTermsRefusal())
        val absent: DownloadState? = null
        assertFalse(absent.isTermsRefusal())
    }
}
