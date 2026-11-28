package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KokoroMeasureSpeedTest {
    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }

    @TempDir
    lateinit var modelsDir: File

    private val context: Context = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val loadMarker: KokoroLoadMarker = mockk()
    private val memoryGate: KokoroWarmUpMemoryGate = mockk()
    private val inferenceEngine: InferenceEngine = mockk()

    @BeforeEach
    fun setup() {
        // Empty models dir: the speed test stops at the model-file check, before any native load.
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { voiceOutputPreferences.selectedKokoroVoice } returns flowOf(KokoroVoice.EMMA)
        every { memoryGate.snapshot() } returns KokoroMemorySnapshot(false, 4L * GIB, 4L * GIB)
    }

    private fun controller() = KokoroVoiceOutputController(
        context,
        voiceOutputPreferences,
        loadMarker,
        memoryGate,
        inferenceEngine,
    )

    @Test
    fun `an interrupted earlier load does not stop a user-tapped speed test before its model check`() = runBlocking {
        every { loadMarker.unfinishedAtLaunch } returns true

        val result = controller().measureSpeed()

        assertTrue(
            result is KokoroSpeedResult.Unavailable && result.message.startsWith("Download "),
            "expected the model-file check to answer, got $result",
        )
    }

    @Test
    fun `low memory still stops a speed test before any load`() = runBlocking {
        every { loadMarker.unfinishedAtLaunch } returns false
        every { memoryGate.snapshot() } returns KokoroMemorySnapshot(false, 1L * GIB, 1L * GIB)

        val result = controller().measureSpeed()

        assertTrue(
            result is KokoroSpeedResult.Unavailable && result.memoryShort && result.message == KOKORO_LOW_MEMORY_MESSAGE,
            "expected the low-memory answer, got $result",
        )
    }
}
