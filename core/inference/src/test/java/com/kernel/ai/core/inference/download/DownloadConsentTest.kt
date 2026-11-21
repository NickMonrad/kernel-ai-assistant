package com.kernel.ai.core.inference.download

import androidx.work.WorkInfo
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadConsentTest {

    @Test
    fun `nothing auto-queues before the user chooses a network`() {
        assertFalse(DownloadConsent.autoQueueAllowed(null))
        assertTrue(DownloadConsent.autoQueueAllowed(DownloadNetworkChoice.WIFI_ONLY))
        assertTrue(DownloadConsent.autoQueueAllowed(DownloadNetworkChoice.MOBILE_DATA_ALLOWED))
    }

    @Test
    fun `updated install keeps mobile data without a prompt`() {
        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, DownloadConsent.legacyChoiceFor(isUpdatedInstall = true))
    }

    @Test
    fun `fresh install stays unchosen`() {
        assertNull(DownloadConsent.legacyChoiceFor(isUpdatedInstall = false))
    }

    @Test
    fun `manual download before any choice defaults to Wi-Fi only`() {
        assertEquals(DownloadNetworkChoice.WIFI_ONLY, DownloadConsent.effectiveForManualDownload(null))
        assertEquals(
            DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
            DownloadConsent.effectiveForManualDownload(DownloadNetworkChoice.MOBILE_DATA_ALLOWED),
        )
    }

    @Test
    fun `flagship auto-queue includes the flagship chat model and skips E2B`() {
        val planned = DownloadConsent.autoQueueModels(HardwareTier.FLAGSHIP, { false }, emptySet(), hasToken = true)
        assertTrue(KernelModel.GEMMA_4_E4B in planned)
        assertFalse(KernelModel.GEMMA_4_E2B in planned)
        assertTrue(planned.none { it.isBundled })
    }

    @Test
    fun `mid-range auto-queue keeps E2B and excludes the flagship-only model`() {
        val planned = DownloadConsent.autoQueueModels(HardwareTier.MID_RANGE, { false }, emptySet(), hasToken = true)
        assertTrue(KernelModel.GEMMA_4_E2B in planned)
        assertFalse(KernelModel.GEMMA_4_E4B in planned)
    }

    @Test
    fun `suppressed optional model is not auto-queued`() {
        val planned = DownloadConsent.autoQueueModels(
            HardwareTier.FLAGSHIP,
            { false },
            suppressedIds = setOf(KernelModel.GEMMA_4_E4B.name),
            hasToken = true,
        )
        assertFalse(KernelModel.GEMMA_4_E4B in planned)
    }

    @Test
    fun `downloaded models are not queued again`() {
        val planned = DownloadConsent.autoQueueModels(
            HardwareTier.FLAGSHIP,
            { it == KernelModel.GEMMA_4_E4B },
            emptySet(),
            hasToken = true,
        )
        assertFalse(KernelModel.GEMMA_4_E4B in planned)
    }

    @Test
    fun `gated models are not queued without a token`() {
        val gatedRequired = KernelModel.entries.filter { it.isGated && it.isRequired }
        val planned = DownloadConsent.autoQueueModels(HardwareTier.FLAGSHIP, { false }, emptySet(), hasToken = false)
        assertTrue(gatedRequired.none { it in planned })
    }

    @Test
    fun `speech defaults are asr4all M files that are not yet downloaded`() {
        val downloadedModel = KernelModel.ASR4ALL_M_CONFIG
        val speech = DownloadConsent.speechDefaultModels { it == downloadedModel }
        assertTrue(speech.all { it.fileName.startsWith("asr4all/m/") })
        assertTrue(speech.none { it == downloadedModel })
    }

    @Test
    fun `choice change re-queues when the network that applies to queued work changes`() {
        assertTrue(DownloadConsent.shouldRequeueOnChoiceChange(DownloadNetworkChoice.WIFI_ONLY, DownloadNetworkChoice.MOBILE_DATA_ALLOWED))
        assertTrue(DownloadConsent.shouldRequeueOnChoiceChange(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, DownloadNetworkChoice.WIFI_ONLY))
        assertTrue(DownloadConsent.shouldRequeueOnChoiceChange(null, DownloadNetworkChoice.MOBILE_DATA_ALLOWED))
        assertFalse(DownloadConsent.shouldRequeueOnChoiceChange(null, DownloadNetworkChoice.WIFI_ONLY))
        assertFalse(DownloadConsent.shouldRequeueOnChoiceChange(DownloadNetworkChoice.WIFI_ONLY, null))
        assertFalse(DownloadConsent.shouldRequeueOnChoiceChange(DownloadNetworkChoice.WIFI_ONLY, DownloadNetworkChoice.WIFI_ONLY))
        assertFalse(DownloadConsent.shouldRequeueOnChoiceChange(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, DownloadNetworkChoice.MOBILE_DATA_ALLOWED))
    }

    @Test
    fun `legacy choice is decided once so an abandoned fresh install never gets mobile data on update`() {
        assertEquals(DownloadNetworkChoice.MOBILE_DATA_ALLOWED, DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided = false, isUpdatedInstall = true))
        assertNull(DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided = true, isUpdatedInstall = true))
        assertNull(DownloadConsent.legacyChoiceOnFirstDecision(alreadyDecided = false, isUpdatedInstall = false))
    }

    @Test
    fun `voice defaults wait for the same consent as auto-queue`() {
        assertFalse(DownloadConsent.voiceDefaultsAllowed(null))
        assertTrue(DownloadConsent.voiceDefaultsAllowed(DownloadNetworkChoice.WIFI_ONLY))
    }

    @Test
    fun `unfinished work means queued, running or blocked`() {
        assertFalse(DownloadConsent.hasUnfinishedWork(emptyList()))
        assertFalse(DownloadConsent.hasUnfinishedWork(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)))
        assertTrue(DownloadConsent.hasUnfinishedWork(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(DownloadConsent.hasUnfinishedWork(listOf(WorkInfo.State.RUNNING)))
        assertTrue(DownloadConsent.hasUnfinishedWork(listOf(WorkInfo.State.BLOCKED, WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun `first-run estimate counts speech default files and the planned voice`() {
        val speechDefaults = DownloadConsent.speechDefaultModels { false }
        assertTrue(speechDefaults.isNotEmpty())
        val cori = 115_574_061L
        assertEquals(speechDefaults.sumOf { it.approxSizeBytes } + cori, DownloadConsent.firstRunEstimateBytes(speechDefaults, cori))
        assertEquals(cori, DownloadConsent.firstRunEstimateBytes(emptyList(), cori))
    }
}
