package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
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
    private val chatReady = MutableStateFlow(true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val memoryGate: KokoroWarmUpMemoryGate = mockk()
    private val loadMarker: KokoroLoadMarker = mockk()
    private val kokoro: KokoroVoiceOutputController = mockk()

    @BeforeEach
    fun setup() {
        every { voiceOutputPreferences.selectedEngine } returns engine
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenEnabled
        every { voiceOutputPreferences.selectedKokoroVoice } returns voice
        every { memoryGate.hasHeadroom() } returns true
        every { loadMarker.unfinishedAtLaunch } returns false
        coEvery { kokoro.warmUp() } returns VoiceOutputResult.Spoken
        justRun { kokoro.unloadEngine() }
    }

    @Test
    fun `warms the engine once when Kokoro is active with spoken responses and headroom`() = runTest {
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()

        coVerify(exactly = 1) { kokoro.warmUp() }
    }

    @Test
    fun `waits for the chat model to be ready before warming`() = runTest {
        chatReady.value = false
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()
        coVerify(exactly = 0) { kokoro.warmUp() }

        chatReady.value = true
        runCurrent()

        coVerify(exactly = 1) { kokoro.warmUp() }
    }

    @Test
    fun `warms again when the Kokoro voice changes`() = runTest {
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()
        voice.value = KokoroVoice.DANIEL
        runCurrent()

        coVerify(exactly = 2) { kokoro.warmUp() }
    }

    @Test
    fun `another engine selected releases the loaded Kokoro engine and does not warm`() = runTest {
        engine.value = VoiceOutputEngine.InflectMicroExperimental
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
        verify(exactly = 1) { kokoro.unloadEngine() }
    }

    @Test
    fun `spoken responses off releases the loaded Kokoro engine and does not warm`() = runTest {
        spokenEnabled.value = false
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
        verify(exactly = 1) { kokoro.unloadEngine() }
    }

    @Test
    fun `does not warm under memory pressure`() = runTest {
        every { memoryGate.hasHeadroom() } returns false
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
    }

    @Test
    fun `skips the automatic warm-up when the previous process died during a native load`() = runTest {
        every { loadMarker.unfinishedAtLaunch } returns true
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()

        coVerify(exactly = 0) { kokoro.warmUp() }
    }

    @Test
    fun `a native error during warm-up is contained and the flow keeps following settings`() = runTest {
        coEvery { kokoro.warmUp() } throws OutOfMemoryError("native allocation")
        KokoroBackgroundWarmUp(voiceOutputPreferences, memoryGate, loadMarker, kokoro).start(backgroundScope, chatReady)
        runCurrent()
        voice.value = KokoroVoice.DANIEL
        runCurrent()

        coVerify(exactly = 2) { kokoro.warmUp() }
    }

    @Test
    fun `low memory blocks warm-up even with plenty available`() {
        assertFalse(kokoroHasHeadroom(KokoroMemorySnapshot(lowMemory = true, memAvailableBytes = null, availMemBytes = 8L * 1024 * 1024 * 1024), KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
        assertFalse(kokoroHasHeadroom(KokoroMemorySnapshot(lowMemory = false, memAvailableBytes = KOKORO_WARM_UP_MIN_AVAILABLE_BYTES - 1, availMemBytes = 0L), KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
        assertTrue(kokoroHasHeadroom(KokoroMemorySnapshot(lowMemory = false, memAvailableBytes = KOKORO_WARM_UP_MIN_AVAILABLE_BYTES, availMemBytes = 0L), KOKORO_WARM_UP_MIN_AVAILABLE_BYTES))
    }
}
