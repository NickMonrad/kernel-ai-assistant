package com.kernel.ai.core.inference.download

import androidx.work.NetworkType
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadConsentTest {

    @Test
    fun `no network choice blocks auto-queued downloads`() {
        assertFalse(DownloadConsent.autoQueueAllowed(null))
    }

    @Test
    fun `any recorded choice allows auto-queued downloads`() {
        assertTrue(DownloadConsent.autoQueueAllowed(DownloadNetworkChoice.WIFI_ONLY))
        assertTrue(DownloadConsent.autoQueueAllowed(DownloadNetworkChoice.MOBILE_DATA_ALLOWED))
    }

    @Test
    fun `existing install with downloads keeps mobile data without a prompt`() {
        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, DownloadConsent.legacyChoiceFor(hasExistingDownloads = true))
    }

    @Test
    fun `new install stays unchosen`() {
        assertNull(DownloadConsent.legacyChoiceFor(hasExistingDownloads = false))
    }

    @Test
    fun `manual download before any choice waits for Wi-Fi`() {
        val effective = DownloadConsent.effectiveForManualDownload(null)
        assertEquals(DownloadNetworkChoice.WIFI_ONLY, effective)
        assertEquals(NetworkType.UNMETERED, effective.requiredNetworkType)
    }

    @Test
    fun `wifi choice requires unmetered network`() {
        assertEquals(
            NetworkType.UNMETERED,
            DownloadConsent.effectiveForManualDownload(DownloadNetworkChoice.WIFI_ONLY).requiredNetworkType,
        )
    }

    @Test
    fun `mobile choice accepts any connection`() {
        assertEquals(
            NetworkType.CONNECTED,
            DownloadConsent.effectiveForManualDownload(DownloadNetworkChoice.MOBILE_DATA_ALLOWED).requiredNetworkType,
        )
    }

    @Test
    fun `stored names round trip and unknown names mean no choice`() {
        DownloadNetworkChoice.entries.forEach { choice ->
            assertEquals(choice, DownloadNetworkChoice.fromStorageName(choice.name))
        }
        assertNull(DownloadNetworkChoice.fromStorageName("bogus"))
        assertNull(DownloadNetworkChoice.fromStorageName(null))
    }

    @Test
    fun `first-run plan on flagship includes the flagship chat model and skips E2B`() {
        val planned = DownloadConsent.plannedModels(HardwareTier.FLAGSHIP)
        assertTrue(KernelModel.GEMMA_4_E4B in planned)
        assertFalse(KernelModel.GEMMA_4_E2B in planned)
        assertTrue(planned.none { it.isBundled })
    }

    @Test
    fun `first-run plan on non-flagship tiers keeps required models and excludes the flagship model`() {
        val planned = DownloadConsent.plannedModels(HardwareTier.MID_RANGE)
        assertTrue(KernelModel.GEMMA_4_E2B in planned)
        assertFalse(KernelModel.GEMMA_4_E4B in planned)
    }
}
