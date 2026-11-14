package com.kernel.ai.feature.settings

import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.UnavailableReason
import com.kernel.ai.core.voice.VoicePackDownloadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class VoicePackAvailabilityMappingTest {
    @Test
    fun `idle downloadable voice pack is not shown as not bundled`() {
        val mapped = VoicePackDownloadState.NotDownloaded.toModelAvailability()

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
        assertNotEquals(
            ModelAvailabilityState.Unavailable(UnavailableReason.NotBundled),
            mapped,
        )
    }

    @Test
    fun `downloaded voice pack is ready`() {
        assertEquals(
            ModelAvailabilityState.Ready,
            VoicePackDownloadState.Downloaded(localDir = "/data/voices/piper").toModelAvailability(),
        )
    }

    @Test
    fun `failed voice pack download surfaces the failure message`() {
        assertEquals(
            ModelAvailabilityState.ActionRequired(ActionReason.DownloadFailed("disk full")),
            VoicePackDownloadState.Error("disk full").toModelAvailability(),
        )
    }

    @Test
    fun `idle Inflect Micro with no downloads is downloadable not not-bundled`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = emptyList(),
            states = emptyMap(),
            frontendState = VoicePackDownloadState.NotDownloaded,
            frontendApproxDownloadBytes = 0L,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }
}
