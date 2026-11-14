package com.kernel.ai.core.inference.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExistingInstallEvidenceTest {

    @Test
    fun `an update with local model data is an existing install`() {
        assertTrue(DownloadConsent.existingInstallFromEvidence(isUpdatedInstall = true, hasLocalModelData = true))
    }

    @Test
    fun `an update that never downloaded anything is treated as fresh`() {
        // Installed this build, never opened it, then auto-updated: no models, no .tmp file.
        assertFalse(DownloadConsent.existingInstallFromEvidence(isUpdatedInstall = true, hasLocalModelData = false))
    }

    @Test
    fun `a fresh install with model data is not an existing install`() {
        assertFalse(DownloadConsent.existingInstallFromEvidence(isUpdatedInstall = false, hasLocalModelData = true))
    }

    @Test
    fun `never-opened update gets no mobile data and the wizard stays pending`() {
        val existing = DownloadConsent.existingInstallFromEvidence(isUpdatedInstall = true, hasLocalModelData = false)
        assertNull(DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided = false, isUpdatedInstall = existing))
    }

    @Test
    fun `an existing install with data gets the legacy mobile choice once`() {
        val existing = DownloadConsent.existingInstallFromEvidence(isUpdatedInstall = true, hasLocalModelData = true)
        assertEquals(
            DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
            DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided = false, isUpdatedInstall = existing),
        )
    }
}
