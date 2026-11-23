package com.kernel.ai.feature.settings

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SherpaSttDownloadStateTest {
    private val models = Asr4AllInstall.models(Asr4AllSize.M)

    @Test
    fun `queued files that are all waiting for Wi-Fi report waiting, not transferring`() {
        val states = models.associateWith { DownloadState.Downloading() }

        val state = sherpaSttDownloadState(models, states, waitingForWifi = models.toSet())

        assertTrue(state.waitingForWifi)
        assertTrue(state.isDownloading)
        assertEquals("Waiting for Wi-Fi", state.statusLabel())
    }

    @Test
    fun `one transferring file means the family is downloading, not waiting`() {
        val states = models.associateWith { DownloadState.Downloading() } +
            (models.first() to DownloadState.Downloading(progress = 0.4f))

        val state = sherpaSttDownloadState(models, states, waitingForWifi = models.drop(1).toSet())

        assertFalse(state.waitingForWifi)
        assertEquals("Downloading", state.statusLabel())
    }

    @Test
    fun `a fully downloaded family is never waiting`() {
        val states = models.associateWith { DownloadState.Downloaded("/x") }

        val state = sherpaSttDownloadState(models, states, waitingForWifi = models.toSet())

        assertFalse(state.waitingForWifi)
        assertTrue(state.isDownloaded)
        assertEquals("Downloaded", state.statusLabel())
    }

    @Test
    fun `a family with nothing queued is not downloaded and not waiting`() {
        val states = models.associateWith { DownloadState.NotDownloaded }

        val state = sherpaSttDownloadState(models, states, waitingForWifi = emptySet())

        assertFalse(state.waitingForWifi)
        assertEquals("Not downloaded", state.statusLabel())
    }

    @Test
    fun `the automatic card explains the wait while the offline model is waiting for Wi-Fi`() {
        val waiting = SherpaSttDownloadState(isDownloading = true, waitingForWifi = true)

        assertEquals(
            "The offline speech model is waiting for Wi-Fi. Automatic can use it once it has downloaded.",
            automaticOfflineModelNote(waiting),
        )
    }

    @Test
    fun `waiting for Wi-Fi only applies to models the waiting set names`() {
        val states = models.associateWith { DownloadState.Downloading() }
        val unrelated = setOf(KernelModel.entries.first { it !in models })

        val state = sherpaSttDownloadState(models, states, waitingForWifi = unrelated)

        assertFalse(state.waitingForWifi)
    }
}
