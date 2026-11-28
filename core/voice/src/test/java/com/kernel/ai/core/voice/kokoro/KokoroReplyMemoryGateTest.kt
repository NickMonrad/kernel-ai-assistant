package com.kernel.ai.core.voice.kokoro

import android.content.Context
import android.media.AudioManager
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Reply-time Kokoro load is gated on memory (#2170); the fallback chain takes over when it refuses. */
class KokoroReplyMemoryGateTest {
    private companion object {
        const val GIB = 1024L * 1024 * 1024
    }

    @TempDir
    lateinit var modelsDir: File

    private val context: Context = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val loadMarker: KokoroLoadMarker = mockk(relaxed = true)
    private val memoryGate: KokoroWarmUpMemoryGate = mockk()
    private val inferenceEngine: InferenceEngine = mockk()

    @BeforeEach
    fun setup() {
        // Empty models dir: a load would stop at the model-file check, so a result of "Download ..." proves no
        // memory refusal happened. The audio manager is only touched when a reply stops; a relaxed mock covers it.
        every { context.getExternalFilesDir("models") } returns modelsDir
        every { context.getSystemService(any<String>()) } returns mockk<AudioManager>(relaxed = true)
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { voiceOutputPreferences.selectedKokoroVoice } returns flowOf(KokoroVoice.EMMA)
        every { voiceOutputPreferences.voiceGain } returns flowOf(1f)
        every { voiceOutputPreferences.voicePitch } returns flowOf(1f)
    }

    private fun controller() = KokoroVoiceOutputController(
        context,
        voiceOutputPreferences,
        loadMarker,
        memoryGate,
        inferenceEngine,
    )

    @Test
    fun `a reply that needs a full load is refused below 1_25 GiB and never marks a load`() = runBlocking {
        every { memoryGate.snapshot() } returns KokoroMemorySnapshot(false, 1L * GIB, 1L * GIB)

        val result = controller().speak(VoiceSpeakRequest(text = "Hello there."))

        assertTrue(
            result is VoiceOutputResult.Unavailable && result.message == KOKORO_LOW_MEMORY_MESSAGE,
            "expected the memory refusal so the fallback speaks, got $result",
        )
        verify(exactly = 1) { memoryGate.snapshot() }
        verify(exactly = 0) { loadMarker.loading() }
    }

    @Test
    fun `a reply with enough memory reaches the model-file check instead of the memory refusal`() = runBlocking {
        every { memoryGate.snapshot() } returns KokoroMemorySnapshot(false, 4L * GIB, 4L * GIB)

        val result = controller().speak(VoiceSpeakRequest(text = "Hello there."))

        assertTrue(
            result is VoiceOutputResult.Unavailable && result.message.startsWith("Download "),
            "expected the model-file check to answer, got $result",
        )
    }
}
