package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputCopy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KokoroAutoCheckTest {
    private val fastRun = KokoroSpeedResult.Measured(
        audioSeconds = 1.0,
        synthesisSeconds = 0.3,
        firstChunkSeconds = 0.1,
        peakMemoryMb = null,
    )
    // Built directly: copy() would keep fastRun's median factor, since the median is a constructor default.
    private val slowRun = KokoroSpeedResult.Measured(audioSeconds = 1.0, synthesisSeconds = 1.5, firstChunkSeconds = 0.1, peakMemoryMb = null)

    private val idle = flowOf(true)

    @Test
    fun `a pass selects Kokoro with Emma`() {
        val decision = decideKokoroAutoCheck(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.SherpaExperimental, fastRun)

        assertTrue(decision.selectKokoro)
        assertInstanceOf(KokoroAutoCheckResult.Passed::class.java, decision.result)
    }

    @Test
    fun `a slow phone keeps the current voice and says so`() {
        val decision = decideKokoroAutoCheck(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.AndroidTts, slowRun)

        assertFalse(decision.selectKokoro)
        val result = assertInstanceOf(KokoroAutoCheckResult.TooSlow::class.java, decision.result)
        assertEquals(VoiceOutputCopy.title(VoiceOutputEngine.AndroidTts), result.stayingOn)
        assertTrue(decision.result!!.message().contains("Staying on"))
    }

    @Test
    fun `short memory keeps the current voice without a speed verdict`() {
        val unavailable = KokoroSpeedResult.Unavailable("low", details = "free 0.5 GiB", memoryShort = true)

        val decision = decideKokoroAutoCheck(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.AndroidTts, unavailable)

        assertFalse(decision.selectKokoro)
        assertInstanceOf(KokoroAutoCheckResult.MemoryShort::class.java, decision.result)
    }

    @Test
    fun `a check that cannot run reports its reason and selects nothing`() {
        val unavailable = KokoroSpeedResult.Unavailable("Couldn't prepare the check on this phone.")

        val decision = decideKokoroAutoCheck(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.AndroidTts, unavailable)

        assertFalse(decision.selectKokoro)
        assertEquals("Couldn't prepare the check on this phone.", decision.result!!.message())
    }

    @Test
    fun `a voice the user changed during the check stands with no selection and no result`() {
        val decision = decideKokoroAutoCheck(VoiceOutputEngine.AndroidTts, VoiceOutputEngine.SherpaExperimental, fastRun)

        assertFalse(decision.selectKokoro)
        assertNull(decision.result)
    }

    @Test
    fun `a run selects Emma, clears the pending flag and never plays a sample`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>()
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { controller.measureSpeed(any()) } returns fastRun
        val check = KokoroAutoCheck(preferences, controller, cache)

        val result = check.runOnce({ KokoroChatModelState.READY }, idle)

        assertInstanceOf(KokoroAutoCheckResult.Passed::class.java, result)
        coVerify { preferences.setSelectedKokoroVoice(KokoroVoice.EMMA) }
        coVerify { preferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental) }
        coVerify { preferences.setPendingKokoroCheck(false) }
        verify(exactly = 0) { controller.unloadEngine() }
    }

    @Test
    fun `a failed run keeps the current voice, frees the engine and reports once`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.InflectMicroExperimental)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { controller.measureSpeed(any()) } returns slowRun
        val check = KokoroAutoCheck(preferences, controller, cache)

        val result = check.runOnce({ KokoroChatModelState.READY }, idle)

        assertInstanceOf(KokoroAutoCheckResult.TooSlow::class.java, result)
        coVerify(exactly = 0) { preferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental) }
        verify { controller.unloadEngine() }
        assertEquals(result, check.takeResult())
        assertNull(check.takeResult())
    }

    @Test
    fun `a chat model that is not downloaded skips the measurement`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        val check = KokoroAutoCheck(preferences, controller, cache)

        val result = check.runOnce({ KokoroChatModelState.NO_MODEL }, idle)

        assertEquals(KOKORO_NO_CHAT_MODEL_MESSAGE, (result as KokoroAutoCheckResult.CannotRun).unavailable.message)
        coVerify(exactly = 0) { controller.measureSpeed(any()) }
    }

    @Test
    fun `a cached pass selects Emma with no measurement and no chat model load`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { cache.hasValidPass() } returns true
        val check = KokoroAutoCheck(preferences, controller, cache)

        val result = check.runOnce({ error("the chat model must not load on a cached pass") }, idle)

        assertInstanceOf(KokoroAutoCheckResult.Passed::class.java, result)
        coVerify { preferences.setSelectedKokoroVoice(KokoroVoice.EMMA) }
        coVerify { preferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental) }
        coVerify(exactly = 0) { controller.measureSpeed(any()) }
        coVerify(exactly = 0) { cache.recordPass() }
    }

    @Test
    fun `a measured pass is recorded in the cache`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { controller.measureSpeed(any()) } returns fastRun
        val check = KokoroAutoCheck(preferences, controller, cache)

        check.runOnce({ KokoroChatModelState.READY }, idle)

        coVerify(exactly = 1) { cache.recordPass() }
        coVerify(exactly = 0) { cache.recordNotPassed() }
    }

    @Test
    fun `a measured fail clears the cached pass`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { controller.measureSpeed(any()) } returns slowRun
        val check = KokoroAutoCheck(preferences, controller, cache)

        check.runOnce({ KokoroChatModelState.READY }, idle)

        coVerify(exactly = 1) { cache.recordNotPassed() }
        coVerify(exactly = 0) { cache.recordPass() }
    }

    @Test
    fun `a stale pending flag, cleared by a Settings check, measures nothing`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(false)
        val check = KokoroAutoCheck(preferences, controller, cache)

        val result = check.runOnce({ error("the chat model must not load for a stale pick") }, idle)

        assertNull(result)
        coVerify(exactly = 0) { controller.measureSpeed(any()) }
        coVerify(exactly = 0) { preferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental) }
    }

    @Test
    fun `a pick announced twice in a row runs one check`() {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        // Two true emissions with no false between: a DataStore write that repeats the pending flag.
        every { preferences.pendingKokoroCheck } returns flow {
            emit(true)
            emit(true)
        }
        coEvery { controller.measureSpeed(any()) } returns fastRun
        val check = KokoroAutoCheck(preferences, controller, cache)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var loads = 0

        check.start(
            scope = scope,
            chatModelReady = MutableStateFlow(true),
            packReady = MutableStateFlow(true),
            inferenceIdle = idle,
            loadChatModel = {
                loads++
                KokoroChatModelState.READY
            },
        )
        scope.cancel()

        assertEquals(1, loads)
        coVerify(exactly = 1) { controller.measureSpeed(any()) }
    }

    @Test
    fun `the measurement waits until inference is idle, and the card shows checking meanwhile`() = runBlocking {
        val preferences = mockk<VoiceOutputPreferences>(relaxed = true)
        val controller = mockk<KokoroVoiceOutputController>(relaxed = true)
        val cache = mockk<KokoroSpeedCheckCache>(relaxed = true)
        every { preferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { preferences.pendingKokoroCheck } returns flowOf(true)
        coEvery { controller.measureSpeed(any()) } returns fastRun
        val inferenceIdle = MutableStateFlow(false)
        val check = KokoroAutoCheck(preferences, controller, cache)

        val measuring = async { check.runOnce({ KokoroChatModelState.READY }, inferenceIdle) }
        delay(50)
        coVerify(exactly = 0) { controller.measureSpeed(any()) }
        assertEquals(KokoroCheckProgress(0, KOKORO_SPEED_TEST_RUNS), check.progress.value)

        inferenceIdle.value = true
        measuring.await()
        coVerify(exactly = 1) { controller.measureSpeed(any()) }
        assertNull(check.progress.value)
    }
}
