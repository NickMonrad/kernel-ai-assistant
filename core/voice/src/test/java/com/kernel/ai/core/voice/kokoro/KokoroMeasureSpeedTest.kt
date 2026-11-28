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
        every { memoryGate.hasHeadroom() } returns true
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
        every { memoryGate.hasHeadroom() } returns false

        val result = controller().measureSpeed()

        assertEquals(
            KokoroSpeedResult.Unavailable("Not enough free memory to load Kokoro. Close other apps and test again."),
            result,
        )
    }
}
