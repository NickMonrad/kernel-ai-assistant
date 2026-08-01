package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import io.mockk.every
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NativeAndroidVoiceInputControllerTest {
    @Test
    fun `shouldUseCachedCaptureAvailability only for alert commands`() {
        assertEquals(false, shouldUseCachedCaptureAvailability(VoiceCaptureMode.Command))
        assertEquals(false, shouldUseCachedCaptureAvailability(VoiceCaptureMode.SlotReply))
        assertEquals(true, shouldUseCachedCaptureAvailability(VoiceCaptureMode.AlertCommand))
    }


    @Test
    fun `initialRecognizerBackend starts alert commands on platform and other modes on device`() {
        assertEquals(RecognizerBackend.OnDevice, initialRecognizerBackend(VoiceCaptureMode.Command))
        assertEquals(RecognizerBackend.OnDevice, initialRecognizerBackend(VoiceCaptureMode.SlotReply))
        assertEquals(RecognizerBackend.Platform, initialRecognizerBackend(VoiceCaptureMode.AlertCommand))
    }

    @Test
    fun `only on-device initial backend arms startup-timeout platform fallback`() {
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(initialRecognizerBackend(VoiceCaptureMode.Command)))
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(initialRecognizerBackend(VoiceCaptureMode.SlotReply)))
        assertEquals(false, shouldRetryWithPlatformAfterStartupTimeout(initialRecognizerBackend(VoiceCaptureMode.AlertCommand)))
    }


    @Test
    fun `sessionResultTimeoutMs keeps alert pre-speech timeout within the cue-to-command handoff but allows more time after progress`() {
        assertEquals(6_000L, sessionResultTimeoutMs(VoiceCaptureMode.Command))
        assertEquals(6_000L, sessionResultTimeoutMs(VoiceCaptureMode.SlotReply))
        // #1433: the first wake-command attempt must survive the cue-to-command
        // handoff (measured command arrival 7.4-9.0 s after readiness on both
        // devices), so the pre-speech alert budget is 10 s, not 2.5 s.
        assertEquals(10_000L, sessionResultTimeoutMs(VoiceCaptureMode.AlertCommand))
        assertEquals(6_000L, sessionResultTimeoutMs(VoiceCaptureMode.AlertCommand, hasSpeechProgress = true))
    }


    @Test
    fun `shouldRetryWithPlatformAfterWatchdogTimeout retries only on-device sessions without partials`() {
        assertEquals(true, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.OnDevice, false))
        assertEquals(false, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.OnDevice, true))
        assertEquals(false, shouldRetryWithPlatformAfterWatchdogTimeout(RecognizerBackend.Platform, false))
    }


    @Test
    fun `shouldRetryWithPlatformAfterStartupTimeout retries only on-device recognizer`() {
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.OnDevice))
        assertEquals(false, shouldRetryWithPlatformAfterStartupTimeout(RecognizerBackend.Platform))
    }

    @Test
    fun `shouldRetryWithPlatformAfterRecognitionError keeps normal command fallback conservative`() {
        assertEquals(
            true,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.Command,
                error = SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.Command,
                error = SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.Platform,
                mode = VoiceCaptureMode.Command,
                error = SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = false,
                sawPartialTranscript = false,
            ),
        )
    }

    @Test
    fun `shouldRetryWithPlatformAfterRecognitionError retries alert no-match after speech without partials`() {
        assertEquals(
            true,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.AlertCommand,
                error = SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = false,
            ),
        )
        assertEquals(
            false,
            shouldRetryWithPlatformAfterRecognitionError(
                backend = RecognizerBackend.OnDevice,
                mode = VoiceCaptureMode.AlertCommand,
                error = SpeechRecognizer.ERROR_NO_MATCH,
                heardSpeech = true,
                sawPartialTranscript = true,
            ),
        )
    }


    @Test
    fun `shouldForceRecognizerLanguage only forces verified locale`() {
        val unknownAvailability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
        )
        val readyAvailability = createRecognitionAvailability(
            isRecognitionAvailable = true,
            isOnDeviceRecognitionAvailable = true,
            languageTag = "en-AU",
            languageDisplayName = "English (Australia)",
            localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
        )

        assertEquals(false, shouldForceRecognizerLanguage(unknownAvailability))
        assertEquals(true, shouldForceRecognizerLanguage(readyAvailability))
    }
}

/**
 * Integration-level tests for [NativeAndroidVoiceInputController.startListening].
 *
 * These tests construct a real controller with a mocked [AndroidNativeRecognitionSupport] so
 * they exercise the actual dispatch/retry logic rather than just pure helper functions.
 * Android framework stubs return default values (isReturnDefaultValues = true in build.gradle.kts),
 * so SpeechRecognizer methods that are not explicitly stubbed are safe no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeAndroidVoiceInputControllerStartListeningTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var recognitionSupport: AndroidNativeRecognitionSupport
    private lateinit var controller: NativeAndroidVoiceInputController

    private val availability = createRecognitionAvailability(
        isRecognitionAvailable = true,
        isOnDeviceRecognitionAvailable = true,
        languageTag = "en-AU",
        languageDisplayName = "English (Australia)",
        localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        recognitionSupport = mockk(relaxed = true)

        // getAvailability / getCaptureAvailability both return the ready availability
        coEvery { recognitionSupport.getAvailability() } returns availability
        every { recognitionSupport.getCaptureAvailability() } returns availability

        controller = NativeAndroidVoiceInputController(context, recognitionSupport)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startListening falls back to platform recognizer when on-device start throws`() = runTest {
        // Arrange: on-device recognizer throws on startListening(); platform recognizer succeeds.
        val onDeviceRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        val platformRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        val platformListener = slot<RecognitionListener>()

        every { recognitionSupport.createOnDeviceSpeechRecognizer() } returns onDeviceRecognizer
        every { recognitionSupport.createPlatformSpeechRecognizer() } returns platformRecognizer
        every { platformRecognizer.setRecognitionListener(capture(platformListener)) } just runs

        every { onDeviceRecognizer.startListening(any()) } throws
            RuntimeException("on-device recognizer unavailable")
        val nextEvent = async { controller.events.first() }
        runCurrent()

        // Act
        val result = controller.startListening(VoiceCaptureMode.Command)

        // Assert: overall result is Started (platform succeeded)
        val started = result as VoiceInputStartResult.Started
        assertTrue(started.captureSessionId > 0L)
        platformListener.captured.onReadyForSpeech(mockk<Bundle>(relaxed = true))
        assertEquals(started.captureSessionId, nextEvent.await().captureSessionId)

        // Platform recognizer was started
        verify(exactly = 1) { recognitionSupport.createPlatformSpeechRecognizer() }
        verify(exactly = 1) { platformRecognizer.startListening(any()) }

        // Orphaned on-device recognizer was cleaned up before the retry
        verify(atLeast = 1) { onDeviceRecognizer.cancel() }
        verify(atLeast = 1) { onDeviceRecognizer.destroy() }
    }

    @Test
    fun `separate logical starts receive distinct capture session identifiers`() = runTest {
        val firstRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        val secondRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        every { recognitionSupport.createOnDeviceSpeechRecognizer() } returnsMany
            listOf(firstRecognizer, secondRecognizer)

        val first = controller.startListening(VoiceCaptureMode.Command) as
            VoiceInputStartResult.Started
        val second = controller.startListening(VoiceCaptureMode.Command) as
            VoiceInputStartResult.Started

        assertTrue(first.captureSessionId > 0L)
        assertTrue(second.captureSessionId > first.captureSessionId)
    }

    @Test
    fun `startListening returns Unavailable when both on-device and platform recognizers throw`() = runTest {
        val onDeviceRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        val platformRecognizer = mockk<SpeechRecognizer>(relaxed = true)

        every { recognitionSupport.createOnDeviceSpeechRecognizer() } returns onDeviceRecognizer
        every { recognitionSupport.createPlatformSpeechRecognizer() } returns platformRecognizer

        every { onDeviceRecognizer.startListening(any()) } throws RuntimeException("on-device unavailable")
        every { platformRecognizer.startListening(any()) } throws RuntimeException("platform unavailable")

        val result = controller.startListening(VoiceCaptureMode.Command)

        assertEquals(true, result is VoiceInputStartResult.Unavailable)
    }

    @Test
    fun `alert session survives the cue-to-command handoff and errors only at the extended budget`() = runTest {
        // #1433: the first wake-command attempt reaches readiness, plays the cue, and
        // then must still be listening when the command arrives (measured 7.4-9.0 s
        // after readiness on both devices).  The pre-fix 2.5 s budget expired the
        // first attempt before the command, forcing a retry session to become the
        // normal path.  This test pins the corrected budget: no error at the old
        // 2.5 s point, terminal error only at the extended alert budget.
        val platformRecognizer = mockk<SpeechRecognizer>(relaxed = true)
        val listener = slot<RecognitionListener>()
        every { recognitionSupport.createPlatformSpeechRecognizer() } returns platformRecognizer
        every { platformRecognizer.setRecognitionListener(capture(listener)) } just runs

        val events = mutableListOf<VoiceInputEvent>()
        val collector = launch { controller.events.collect { events += it } }
        runCurrent()

        val result = controller.startListening(VoiceCaptureMode.AlertCommand) as
            VoiceInputStartResult.Started
        listener.captured.onReadyForSpeech(mockk<Bundle>(relaxed = true))
        runCurrent()
        assertTrue(
            events.any { it is VoiceInputEvent.ListeningStarted },
            "attempt must reach readiness",
        )

        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(
            events.any { it is VoiceInputEvent.Error },
            "first attempt must still be listening at the old 2.5 s deadline",
        )

        advanceTimeBy(7_000)
        runCurrent()
        assertTrue(
            events.any { it is VoiceInputEvent.Error },
            "session must end at the extended alert budget",
        )
        assertTrue(
            events.filterIsInstance<VoiceInputEvent.Error>()
                .all { it.captureSessionId == result.captureSessionId },
            "the terminal error must belong to the same capture session",
        )
        collector.cancel()
    }
}
