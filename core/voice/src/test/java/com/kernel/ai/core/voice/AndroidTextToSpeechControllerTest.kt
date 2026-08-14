package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Focused tests for #1463: Android TTS recovery when the configured default engine is
 * stale/unavailable. The real [TextToSpeech] constructor is replaced with the controller's
 * local seam ([TextToSpeechFactory]); the fake instances control init statuses and report
 * installed engines from [TextToSpeech.getEngines], so discovery is fully controlled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTextToSpeechControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stubFrameworkBuilders()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkConstructor(AudioFocusRequest.Builder::class)
        unmockkConstructor(AudioAttributes.Builder::class)
    }

    /**
     * Fires the init listener synchronously so tests control per-attempt init status.
     * [installedEngines] controls what the created instance reports from
     * [TextToSpeech.getEngines] — the discovery source for the recovery path.
     */
    private class FakeTtsFactory(
        private val initStatuses: List<Int> = listOf(TextToSpeech.SUCCESS),
        private val defaultEnginePackage: String? = null,
        private val installedEngines: List<TextToSpeech.EngineInfo> = emptyList(),
    ) : TextToSpeechFactory {
        val created = mutableListOf<TextToSpeech>()
        val packages = mutableListOf<String?>()

        override fun create(listener: TextToSpeech.OnInitListener, enginePackage: String?): TextToSpeech {
            val engine = mockk<TextToSpeech>(relaxed = true)
            if (defaultEnginePackage != null) {
                every { engine.defaultEngine } returns defaultEnginePackage
            }
            every { engine.engines } returns installedEngines
            created += engine
            packages += enginePackage
            // repeat the last configured status for any additional attempts
            val status = initStatuses.getOrElse(created.size - 1) { initStatuses.last() }
            listener.onInit(status)
            return engine
        }
    }

    private fun engineInfo(name: String): TextToSpeech.EngineInfo =
        TextToSpeech.EngineInfo().apply { this.name = name }

    private fun buildController(
        factory: FakeTtsFactory = FakeTtsFactory(),
    ): Pair<AndroidTextToSpeechController, FakeTtsFactory> {
        val controller = AndroidTextToSpeechController(context, factory)
        return controller to factory
    }

    /**
     * Real Android fluent builders (AudioAttributes.Builder, AudioFocusRequest.Builder)
     * return null from every method under the JVM unit-test stub, so their fluent chains
     * would NPE. Intercept construction and route each chain onto a fully stubbed builder
     * mock instead.
     */
    private fun stubFrameworkBuilders() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        every { audioManager.requestAudioFocus(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        val audioAttributes = mockk<AudioAttributes>(relaxed = true)
        val attributesBuilder = mockk<AudioAttributes.Builder>()
        every { attributesBuilder.setUsage(any()) } returns attributesBuilder
        every { attributesBuilder.setContentType(any()) } returns attributesBuilder
        every { attributesBuilder.build() } returns audioAttributes
        mockkConstructor(AudioAttributes.Builder::class)
        every { anyConstructed<AudioAttributes.Builder>().setUsage(any()) } returns attributesBuilder
        every { anyConstructed<AudioAttributes.Builder>().setContentType(any()) } returns attributesBuilder

        val focusRequest = mockk<AudioFocusRequest>(relaxed = true)
        val focusBuilder = mockk<AudioFocusRequest.Builder>()
        every { focusBuilder.setAudioAttributes(any()) } returns focusBuilder
        every { focusBuilder.setAcceptsDelayedFocusGain(any()) } returns focusBuilder
        every { focusBuilder.setWillPauseWhenDucked(any()) } returns focusBuilder
        every { focusBuilder.setOnAudioFocusChangeListener(any()) } returns focusBuilder
        every { focusBuilder.build() } returns focusRequest
        mockkConstructor(AudioFocusRequest.Builder::class)
        every { anyConstructed<AudioFocusRequest.Builder>().setAudioAttributes(any()) } returns focusBuilder
        every { anyConstructed<AudioFocusRequest.Builder>().setAcceptsDelayedFocusGain(any()) } returns focusBuilder
        every { anyConstructed<AudioFocusRequest.Builder>().setWillPauseWhenDucked(any()) } returns focusBuilder
        every { anyConstructed<AudioFocusRequest.Builder>().setOnAudioFocusChangeListener(any()) } returns focusBuilder
    }

    @Test
    fun `valid default engine initialises without discovery or explicit-engine retry`() =
        runTest(dispatcher) {
            val (controller, factory) = buildController()

            val result = controller.warmUp()

            assertEquals(VoiceOutputResult.Spoken, result)
            // normal default-engine constructor only; no engine discovery, no retry
            assertEquals(1, factory.created.size)
            assertNull(factory.packages[0])
            verify(exactly = 0) { factory.created[0].getEngines() }
            verify(exactly = 0) { factory.created[0].shutdown() }
        }

    @Test
    fun `stale default engine shuts down failed instance then retries installed engine`() =
        runTest(dispatcher) {
            val (controller, factory) = buildController(
                factory = FakeTtsFactory(
                    initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS),
                    installedEngines = listOf(engineInfo("com.google.android.tts")),
                ),
            )

            val result = controller.warmUp()

            assertEquals(VoiceOutputResult.Spoken, result)
            // default attempt (null package) failed, discovery ran, then one explicit retry
            assertEquals(2, factory.created.size)
            assertEquals(listOf(null, "com.google.android.tts"), factory.packages)
            verify { factory.created[0].getEngines() }
            verify { factory.created[0].shutdown() }
            verify(exactly = 0) { factory.created[1].shutdown() }
        }

    @Test
    fun `recovery skips the failed default package when it is still listed as installed`() =
        runTest(dispatcher) {
            val (controller, factory) = buildController(
                factory = FakeTtsFactory(
                    initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS),
                    defaultEnginePackage = "com.example.broken",
                    installedEngines = listOf(
                        engineInfo("com.example.broken"),
                        engineInfo("com.google.android.tts"),
                    ),
                ),
            )

            val result = controller.warmUp()

            assertEquals(VoiceOutputResult.Spoken, result)
            assertEquals(listOf(null, "com.google.android.tts"), factory.packages)
            verify { factory.created[0].shutdown() }
        }

    @Test
    fun `warmUp after successful fallback reuses the recovered engine`() =
        runTest(dispatcher) {
            val (controller, factory) = buildController(
                factory = FakeTtsFactory(
                    initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS),
                    installedEngines = listOf(engineInfo("com.google.android.tts")),
                ),
            )

            val first = controller.warmUp()
            val second = controller.warmUp()

            assertEquals(VoiceOutputResult.Spoken, first)
            assertEquals(VoiceOutputResult.Spoken, second)
            // default attempt + one recovery retry, and no recreation on the second warmUp
            assertEquals(2, factory.created.size)
            verify(exactly = 1) { factory.created[0].getEngines() }
            verify(exactly = 0) { factory.created[1].shutdown() }
        }

    @Test
    fun `no usable engine returns Unavailable and leaves clean state for later retry`() =
        runTest(dispatcher) {
            val (controller, factory) = buildController(
                factory = FakeTtsFactory(
                    initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.ERROR),
                    installedEngines = listOf(engineInfo("com.google.android.tts")),
                ),
            )

            val first = controller.warmUp()
            val second = controller.warmUp()

            assertTrue(first is VoiceOutputResult.Unavailable)
            assertTrue(second is VoiceOutputResult.Unavailable)
            // the second warmUp re-attempts from scratch (default + recovery): no stale
            // instance is reused and no unresolved init deferred blocks a later caller
            assertEquals(4, factory.created.size)
            factory.created.forEach { verify { it.shutdown() } }
        }

    @Test
    fun `no installed engines means no retry attempt`() = runTest(dispatcher) {
        val (controller, factory) = buildController(
            factory = FakeTtsFactory(initStatuses = listOf(TextToSpeech.ERROR)),
        )

        val result = controller.warmUp()

        assertTrue(result is VoiceOutputResult.Unavailable)
        // discovery ran (getEngines on the failed default instance) but found nothing
        assertEquals(1, factory.created.size)
        verify { factory.created[0].getEngines() }
        verify { factory.created[0].shutdown() }
    }

    @Test
    fun `speak uses the recovered engine after fallback`() = runTest(dispatcher) {
        val (controller, factory) = buildController(
            factory = FakeTtsFactory(
                initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS),
                installedEngines = listOf(engineInfo("com.google.android.tts")),
            ),
        )

        val result = controller.speak(VoiceSpeakRequest(text = "hello", locale = Locale.US))

        assertEquals(VoiceOutputResult.Spoken, result)
        val recovered = factory.created[1]
        verify { recovered.speak("hello", TextToSpeech.QUEUE_FLUSH, any(), any()) }
        verify(exactly = 0) { factory.created[0].speak(any(), any(), any(), any()) }
    }

    @Test
    fun `streaming session uses the recovered engine after fallback`() = runTest(dispatcher) {
        val (controller, factory) = buildController(
            factory = FakeTtsFactory(
                initStatuses = listOf(TextToSpeech.ERROR, TextToSpeech.SUCCESS),
                installedEngines = listOf(engineInfo("com.google.android.tts")),
            ),
        )

        val session = controller.openStreamingSession(
            VoiceSpeakRequest(text = "", locale = Locale.US),
        )
        val result = session.append("streamed reply", isFinal = true)

        assertEquals(VoiceOutputResult.Spoken, result)
        val recovered = factory.created[1]
        verify { recovered.speak("streamed reply", TextToSpeech.QUEUE_FLUSH, any(), any()) }
        verify(exactly = 0) { factory.created[0].speak(any(), any(), any(), any()) }
    }
}
