package com.kernel.ai.feature.settings

import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SherpaSttAvailabilityMappingTest {
    @Test
    fun `idle downloadable model is not shown as not bundled`() {
        val state = SherpaSttDownloadState()

        assertEquals(ModelAvailabilityState.NotDisplayed, state.toModelAvailability())
    }

    @Test
    fun `downloaded model is ready`() {
        val state = SherpaSttDownloadState(isDownloaded = true)

        assertEquals(ModelAvailabilityState.Ready, state.toModelAvailability())
    }

    @Test
    fun `in-flight download reports progress`() {
        val state = SherpaSttDownloadState(isDownloading = true, progress = 0.4f)

        assertEquals(
            ModelAvailabilityState.Preparing(progress = 0.4f, isAutoQueued = false),
            state.toModelAvailability(),
        )
    }

    @Test
    fun `licence-gated issue requires licence action`() {
        val state = SherpaSttDownloadState(
            issue = SherpaSttDownloadIssue(message = "gated", licenceRequired = true, licenceUrl = null),
        )

        assertEquals(
            ModelAvailabilityState.ActionRequired(ActionReason.LicenseRequired),
            state.toModelAvailability(),
        )
    }

    @Test
    fun `failed download surfaces the failure message`() {
        val state = SherpaSttDownloadState(
            issue = SherpaSttDownloadIssue(message = "network down", licenceRequired = false, licenceUrl = null),
        )

        assertEquals(
            ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed("network down")),
            state.toModelAvailability(),
        )
    }
}
