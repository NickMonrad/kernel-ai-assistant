package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ActionReason
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.UnavailableReason
import com.kernel.ai.core.voice.VoicePackDownloadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            piperSelected = false,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    private val inflectModels = listOf(
        KernelModel.entries.first { it.fileName == "duration.onnx" },
        KernelModel.entries.first { it.fileName == "decode.onnx" },
    )

    @Test
    fun `Cori downloading belongs to the Piper row while Piper is selected`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = emptyMap(),
            frontendState = VoicePackDownloadState.Downloading(progress = 0.5f),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = true,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    @Test
    fun `Cori downloading alone is not Inflect progress while Piper is not selected`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = emptyMap(),
            frontendState = VoicePackDownloadState.Downloading(progress = 0.5f),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = false,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    @Test
    fun `Piper-owned downloaded Cori does not stall Inflect graph progress`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = inflectModels.associateWith { DownloadState.Downloading(progress = 0.5f) },
            frontendState = VoicePackDownloadState.Downloaded(localDir = "/data/voices/cori"),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = true,
        )

        assertTrue(mapped is ModelAvailabilityState.Preparing)
        val progress = (mapped as ModelAvailabilityState.Preparing).progress
        assertEquals(0.5f, progress, 0.01f)
    }

    @Test
    fun `finished Cori does not make Inflect ready or show a contradictory state`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = emptyMap(),
            frontendState = VoicePackDownloadState.Downloaded(localDir = "/data/voices/cori"),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = false,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    @Test
    fun `Cori failure is not reported as an Inflect failure when Inflect has not started`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = emptyMap(),
            frontendState = VoicePackDownloadState.Error("disk full"),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = false,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    @Test
    fun `Inflect graph download counts the shared pack in its own progress`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = mapOf(inflectModels[0] to DownloadState.Downloading(progress = 1f)),
            frontendState = VoicePackDownloadState.Downloaded(localDir = "/data/voices/cori"),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = false,
        )

        assertTrue(mapped is ModelAvailabilityState.Preparing)
        val progress = (mapped as ModelAvailabilityState.Preparing).progress
        assertTrue(progress > 0f && progress < 1f)
    }

    @Test
    fun `Piper-owned Cori progress is not shown on Inflect once its graphs are downloaded`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = inflectModels.associateWith { DownloadState.Downloaded(localPath = "/data/inflect") },
            frontendState = VoicePackDownloadState.Downloading(progress = 0.3f),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = true,
        )

        assertEquals(ModelAvailabilityState.NotDisplayed, mapped)
    }

    @Test
    fun `downloaded Inflect graphs show Cori progress while Piper is not selected`() {
        val mapped = aggregateInflectMicroAvailability(
            requiredModels = inflectModels,
            states = inflectModels.associateWith { DownloadState.Downloaded(localPath = "/data/inflect") },
            frontendState = VoicePackDownloadState.Downloading(progress = 0.3f),
            frontendApproxDownloadBytes = 115_574_061L,
            piperSelected = false,
        )

        assertTrue(mapped is ModelAvailabilityState.Preparing)
        val progress = (mapped as ModelAvailabilityState.Preparing).progress
        assertTrue(progress > 0f && progress < 1f)
    }
}
