package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KokoroBackgroundWarmUpTest {

    private val engine = MutableStateFlow(VoiceOutputEngine.KokoroExperimental)
    private val spokenEnabled = MutableStateFlow(true)
    private val voice = MutableStateFlow(KokoroVoice.EMMA)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val memoryGate: KokoroWarmUpMemoryGate = mockk()
    private val kokoro: KokoroVoiceOutputController = mockk()

    @BeforeEach
    fun setup() {
        every { voiceOutputPreferences.selectedEngine } returns engine
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenEnabled
        every { voiceOutputPreferences.selectedKokoroVoice } returns voice
        every { memoryGate.hasHeadroom() } returns true
        coEvery { kokoro.warmUp() } returns VoiceOutputResult.Spoken
    }

    @Test
    fun `warms the engine once when Kokoro is active with spoken responses and headroom`() = runTest {
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, kokoro).start(backgroundScope)
        runCurrent()

        coVerify(exactly = 1) { kokoro.warmUp() }
    }

    @Test
    fun `warms again when the Kokoro voice changes`() = runTest {
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, kokoro).start(backgroundScope)
        runCurrent()
        voice.value = KokoroVoice.DANIEL
        runCurrent()

        coVerify(exactly = 2) { kokoro.warmUp() }
    }

    @Test
    fun `does not warm when another engine is active`() = runTest {
        engine.value = VoiceOutputEngine.InflectMicroExperimental
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, kokoro).start(backgroundScope)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
    }

    @Test
    fun `does not warm when spoken responses are off`() = runTest {
        spokenEnabled.value = false
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, kokoro).start(backgroundScope)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
    }

    @Test
    fun `does not warm under memory pressure`() = runTest {
        every { memoryGate.hasHeadroom() } returns false
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, kokoro).start(backgroundScope)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
    }

    @Test
    fun `low memory blocks warm-up even with plenty available`() {
        assertFalse(kokoroWarmUpHasHeadroom(lowMemory = true, availableBytes = 8L * 1024 * 1024 * 1024))
        assertFalse(kokoroWarmUpHasHeadroom(lowMemory = false, availableBytes = KOKORO_WARM_UP_MIN_AVAILABLE_BYTES - 1))
        assertTrue(kokoroWarmUpHasHeadroom(lowMemory = false, availableBytes = KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
    }
}
