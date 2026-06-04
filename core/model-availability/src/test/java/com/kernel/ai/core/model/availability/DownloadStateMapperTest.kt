package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ModelAvailabilityState.ActionRequired
import com.kernel.ai.core.model.availability.ModelAvailabilityState.Preparing
import com.kernel.ai.core.model.availability.ModelAvailabilityState.Unavailable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DownloadStateMapperTest {

    private val ungatedModel = KernelModel.GEMMA_4_E2B
    private val gatedModel = KernelModel.EMBEDDING_GEMMA_300M
    private val bundledModel = KernelModel.MINI_LM

    @Test
    fun `downloaded maps to Ready`() {
        val result = DownloadState.Downloaded("/path/to/model")
            .toAvailability(ungatedModel, hfAuth = false)
        assertEquals(ModelAvailabilityState.Ready, result)
    }

    @Test
    fun `bundled model always Ready even when NotDownloaded`() {
        val result = DownloadState.NotDownloaded
            .toAvailability(bundledModel, hfAuth = false)
        assertEquals(ModelAvailabilityState.Ready, result)
    }

    @Test
    fun `downloading maps to Preparing with progress`() {
        val result = DownloadState.Downloading(progress = 0.42f)
            .toAvailability(ungatedModel, hfAuth = false)
        assertEquals(Preparing(progress = 0.42f, isAutoQueued = false), result)
    }

    @Test
    fun `downloading with AUTO_QUEUED source maps to Preparing isAutoQueued true`() {
        val result = DownloadState.Downloading(progress = 0.5f)
            .toAvailability(ungatedModel, hfAuth = false, source = DownloadSource.AUTO_QUEUED)
        assertEquals(Preparing(progress = 0.5f, isAutoQueued = true), result)
    }

    @Nested
    inner class GatedModels {

        @Test
        fun `not downloaded gated model without HF auth maps to SignInRequired`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(gatedModel, hfAuth = false)
            assertEquals(ActionRequired(ActionReason.SignInRequired), result)
        }

        @Test
        fun `not downloaded gated model with HF auth and APPROVAL_PENDING maps to ApprovalPending`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(gatedModel, hfAuth = true, gated = GatedModelStatus.APPROVAL_PENDING)
            assertEquals(ActionRequired(ActionReason.ApprovalPending), result)
        }

        @Test
        fun `not downloaded gated model with HF auth and ACCESS_DENIED maps to AccessDenied`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(gatedModel, hfAuth = true, gated = GatedModelStatus.ACCESS_DENIED)
            assertEquals(Unavailable(UnavailableReason.AccessDenied), result)
        }

        @Test
        fun `not downloaded gated model with HF auth and NONE status maps to NotDisplayed`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(gatedModel, hfAuth = true, gated = GatedModelStatus.NONE)
            assertEquals(ModelAvailabilityState.NotDisplayed, result)
        }

        @Test
        fun `not downloaded gated model with HF auth and APPROVED status maps to NotDisplayed`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(gatedModel, hfAuth = true, gated = GatedModelStatus.APPROVED)
            assertEquals(ModelAvailabilityState.NotDisplayed, result)
        }
    }

    @Nested
    inner class UngatedNotDownloaded {

        @Test
        fun `not downloaded ungated model with AUTO_QUEUED source maps to Preparing isAutoQueued`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(ungatedModel, hfAuth = false, source = DownloadSource.AUTO_QUEUED)
            assertEquals(Preparing(progress = 0f, isAutoQueued = true), result)
        }

        @Test
        fun `not downloaded ungated model with USER_INITIATED source maps to NotDisplayed`() {
            val result = DownloadState.NotDownloaded
                .toAvailability(ungatedModel, hfAuth = false, source = DownloadSource.USER_INITIATED)
            assertEquals(ModelAvailabilityState.NotDisplayed, result)
        }
    }

    @Nested
    inner class ErrorStates {

        @Test
        fun `error with licenceRequired maps to LicenseRequired`() {
            val result = DownloadState.Error(
                message = "Licence not accepted",
                licenceRequired = true,
            ).toAvailability(ungatedModel, hfAuth = false)
            assertEquals(ActionRequired(ActionReason.LicenseRequired), result)
        }

        @Test
        fun `error without licence maps to DownloadFailed`() {
            val result = DownloadState.Error(
                message = "Network timeout",
                licenceRequired = false,
            ).toAvailability(ungatedModel, hfAuth = false)
            assertEquals(ActionRequired(ActionReason.DownloadFailed("Network timeout")), result)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `downloaded state regardless of gated or auth returns Ready`() {
            val result = DownloadState.Downloaded("/path")
                .toAvailability(gatedModel, hfAuth = false)
            assertEquals(ModelAvailabilityState.Ready, result)
        }

        @Test
        fun `downloading regardless of gated or auth returns Preparing`() {
            val result = DownloadState.Downloading(progress = 0.1f)
                .toAvailability(gatedModel, hfAuth = false)
            assertEquals(Preparing(progress = 0.1f, isAutoQueued = false), result)
        }
    }
}
