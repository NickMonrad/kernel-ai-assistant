package com.kernel.ai.core.inference.terms

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TermsRefusalsToClearTest {
    private val termsModel = KernelModel.entries.first { it.requiresTermsAcceptance }
    private val otherModel = KernelModel.entries.first { !it.requiresTermsAcceptance }
    private val accepted = TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION, acceptedAtMillis = 1L)

    @Test
    fun `an accepted Terms clears the stale refusal of a model that needs them`() {
        val states = mapOf(termsModel to DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE))

        assertEquals(listOf(termsModel), termsRefusalsToClear(states, accepted))
    }

    @Test
    fun `without an accepted Terms nothing is cleared`() {
        val states = mapOf(termsModel to DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE))

        assertTrue(termsRefusalsToClear(states, TermsAcceptanceRecord()).isEmpty())
    }

    @Test
    fun `an acceptance of an older Terms version does not clear the refusal`() {
        val states = mapOf(termsModel to DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE))
        val older = TermsAcceptanceRecord(acceptedVersion = "2000-01-01", acceptedAtMillis = 1L)

        assertTrue(termsRefusalsToClear(states, older).isEmpty())
    }

    @Test
    fun `a different download error on a model that needs the Terms is kept`() {
        val states = mapOf(termsModel to DownloadState.Error("Network unavailable"))

        assertTrue(termsRefusalsToClear(states, accepted).isEmpty())
    }

    @Test
    fun `a refusal on a model that never needed the Terms is not touched`() {
        val states = mapOf(otherModel to DownloadState.Error(TERMS_NOT_ACCEPTED_MESSAGE))

        assertTrue(termsRefusalsToClear(states, accepted).isEmpty())
    }
}
