package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConversationModelReadinessTest {

    private val e4bPath = KernelModel.GEMMA_4_E4B.fileName
    private val e2bPath = KernelModel.GEMMA_4_E2B.fileName

    // ── FLAGSHIP ──────────────────────────────────────────────────────────────

    @Nested
    inner class FlagshipTier {

        @Test
        fun `E4B installed returns Ready`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloaded(e4bPath),
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertEquals(ConversationModelReadiness.Ready, result)
        }

        @Test
        fun `E2B installed but E4B missing returns FallbackActive`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded(e2bPath),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.FallbackActive::class.java, result)
            val fallback = result as ConversationModelReadiness.FallbackActive
            assertEquals(KernelModel.GEMMA_4_E4B, fallback.recommendedModel)
        }

        @Test
        fun `neither E4B nor E2B installed returns ActionRequired`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
            val action = result as ConversationModelReadiness.ActionRequired
            assertEquals(KernelModel.GEMMA_4_E4B, action.recommendedModel)
            assertEquals(KernelModel.GEMMA_4_E2B, action.fallbackModel)
        }

        @Test
        fun `both E4B and E2B installed returns Ready`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloaded(e4bPath),
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded(e2bPath),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertEquals(ConversationModelReadiness.Ready, result)
        }

        @Test
        fun `E4B downloading returns Preparing`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloading(progress = 0.5f),
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.Preparing::class.java, result)
            val preparing = result as ConversationModelReadiness.Preparing
            assertEquals(KernelModel.GEMMA_4_E4B, preparing.downloadingModel)
            assertEquals(0.5f, preparing.progress!!, 0.001f)
        }

        @Test
        fun `E4B missing and E2B downloading returns Preparing`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.Downloading(progress = 0.3f),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.Preparing::class.java, result)
            val preparing = result as ConversationModelReadiness.Preparing
            assertEquals(KernelModel.GEMMA_4_E2B, preparing.downloadingModel)
            assertEquals(0.3f, preparing.progress!!, 0.001f)
        }

        @Test
        fun `E4B downloading with E2B downloaded returns FallbackActive not Preparing`() {
            // When E2B is already installed, E4B downloading does not yield Preparing
            // because E2B already satisfies chat readiness. Returns FallbackActive
            // since the recommended model (E4B) is not yet fully installed.
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloading(progress = 0.5f),
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded(e2bPath),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.FallbackActive::class.java, result)
            val fallback = result as ConversationModelReadiness.FallbackActive
            assertEquals(KernelModel.GEMMA_4_E4B, fallback.recommendedModel)
        }
    }

    // ── Non-flagship (MID_RANGE / LOW_POWER) ──────────────────────────────────

    @Nested
    inner class NonFlagshipTier {

        @Test
        fun `E2B installed returns Ready on mid range`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded(e2bPath),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.MID_RANGE, states)
            assertEquals(ConversationModelReadiness.Ready, result)
        }

        @Test
        fun `E2B installed returns Ready on low power`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded(e2bPath),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.LOW_POWER, states)
            assertEquals(ConversationModelReadiness.Ready, result)
        }

        @Test
        fun `E2B downloading on mid range returns Preparing`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.Downloading(progress = 0.7f),
            )
            val result = ConversationModelReadiness.compute(HardwareTier.MID_RANGE, states)
            assertInstanceOf(ConversationModelReadiness.Preparing::class.java, result)
            val preparing = result as ConversationModelReadiness.Preparing
            assertEquals(KernelModel.GEMMA_4_E2B, preparing.downloadingModel)
            assertEquals(0.7f, preparing.progress!!, 0.001f)
        }

        @Test
        fun `E2B missing on mid range returns ActionRequired`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.NotDownloaded,
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.MID_RANGE, states)
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
            val action = result as ConversationModelReadiness.ActionRequired
            // On non-flagship, E-2B is both the recommended model and the fallback
            assertEquals(KernelModel.GEMMA_4_E2B, action.recommendedModel)
            assertEquals(KernelModel.GEMMA_4_E2B, action.fallbackModel)
        }

        @Test
        fun `E4B irrelevant on mid range`() {
            // E4B alone should NOT satisfy readiness on non-flagship
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloaded(e4bPath),
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.MID_RANGE, states)
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Nested
    inner class EdgeCases {

        @Test
        fun `empty download states maps to ActionRequired on flagship`() {
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, emptyMap())
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
            val action = result as ConversationModelReadiness.ActionRequired
            assertEquals(KernelModel.GEMMA_4_E4B, action.recommendedModel)
            assertEquals(KernelModel.GEMMA_4_E2B, action.fallbackModel)
        }

        @Test
        fun `empty download states maps to ActionRequired on mid range`() {
            val result = ConversationModelReadiness.compute(HardwareTier.MID_RANGE, emptyMap())
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
            val action = result as ConversationModelReadiness.ActionRequired
            assertEquals(KernelModel.GEMMA_4_E2B, action.recommendedModel)
            assertEquals(KernelModel.GEMMA_4_E2B, action.fallbackModel)
        }

        @Test
        fun `E4B downloading with no progress returns Preparing with null progress`() {
            // Downloading with default progress of 0f
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Downloading(),
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.Preparing::class.java, result)
            val preparing = result as ConversationModelReadiness.Preparing
            assertEquals(KernelModel.GEMMA_4_E4B, preparing.downloadingModel)
            // progress property captures the raw value (could be 0f)
            assertEquals(0f, preparing.progress!!, 0.001f)
        }

        @Test
        fun `E4B Error state maps to ActionRequired not Preparing`() {
            val states = mapOf(
                KernelModel.GEMMA_4_E4B to DownloadState.Error("Network error"),
                KernelModel.GEMMA_4_E2B to DownloadState.NotDownloaded,
            )
            val result = ConversationModelReadiness.compute(HardwareTier.FLAGSHIP, states)
            assertInstanceOf(ConversationModelReadiness.ActionRequired::class.java, result)
        }
    }
}
