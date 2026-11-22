package com.kernel.ai.core.inference.terms

import com.kernel.ai.core.inference.download.KernelModel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TermsAcceptanceRecordTest {

    @Test
    fun `acceptance counts only for the version the app ships`() {
        assertTrue(TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION).isAccepted())
        assertFalse(TermsAcceptanceRecord(acceptedVersion = "2025-01-01").isAccepted())
        assertFalse(TermsAcceptanceRecord().isAccepted())
    }

    @Test
    fun `update prompt shows until the current version is accepted or dismissed`() {
        assertTrue(TermsAcceptanceRecord().promptPending())
        assertFalse(TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION).promptPending())
        assertFalse(TermsAcceptanceRecord(dismissedVersion = TERMS_VERSION).promptPending())
    }

    @Test
    fun `an older dismissal or acceptance does not hide the prompt for a new version`() {
        val newVersion = "2027-01-01"
        assertTrue(TermsAcceptanceRecord(dismissedVersion = TERMS_VERSION).promptPending(newVersion))
        assertTrue(TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION).promptPending(newVersion))
        assertFalse(TermsAcceptanceRecord(acceptedVersion = newVersion).promptPending(newVersion))
    }

    @Test
    fun `only asr4all speech models require the Terms before download`() {
        assertTrue(KernelModel.ASR4ALL_M_ENCODER.requiresTermsAcceptance)
        assertTrue(KernelModel.ASR4ALL_L_ENCODER.requiresTermsAcceptance)
        assertFalse(KernelModel.GEMMA_4_E4B.requiresTermsAcceptance)
    }
}
