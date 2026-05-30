package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioManager
import android.speech.SpeechRecognizer
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `initialRecognizerBackend always starts on device`() {
        assertEquals(RecognizerBackend.OnDevice, initialRecognizerBackend())
    }

    @Test
    fun `initial backend arms the startup-timeout platform fallback`() {
        // Contract: whichever backend initialRecognizerBackend() chooses, the startup-timeout
        // safety net must be active so the async fallback fires if the recognizer stalls.
        // This also covers the sync-throw path added in startListening: both code paths share
        // the same RecognizerBackend.OnDevice value, so a change to either helper breaks here.
        val backend = initialRecognizerBackend()
        assertEquals(true, shouldRetryWithPlatformAfterStartupTimeout(backend))
    }


    @Test
    fun `sessionResultTimeoutMs shortens alert command watchdog`() {
        assertEquals(6_000L, sessionResultTimeoutMs(VoiceCaptureMode.Command))
        assertEquals(6_000L, sessionResultTimeoutMs(VoiceCaptureMode.SlotReply))
        assertEquals(2_500L, sessionResultTimeoutMs(VoiceCaptureMode.AlertCommand))
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
    fun `honorAndroidNativeBlockingReason is null for non-Honor devices`() {
        assertEquals(null, honorAndroidNativeBlockingReason("google"))
    }

    @Test
    fun `honorAndroidNativeBlockingReason instructs Honor users to switch engines`() {
        assertEquals(
            "Android native speech recognition is failing on this device. Switch to Sherpa-ONNX or Vosk in Settings → Voice.",
            honorAndroidNativeBlockingReason("HONOR"),
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

        every { recognitionSupport.createOnDeviceSpeechRecognizer() } returns onDeviceRecognizer
        every { recognitionSupport.createPlatformSpeechRecognizer() } returns platformRecognizer

        every { onDeviceRecognizer.startListening(any()) } throws
            RuntimeException("on-device recognizer unavailable")

        // Act
        val result = controller.startListening(VoiceCaptureMode.Command)

        // Assert: overall result is Started (platform succeeded)
        assertEquals(VoiceInputStartResult.Started, result)

        // Platform recognizer was started
        verify(exactly = 1) { recognitionSupport.createPlatformSpeechRecognizer() }
        verify(exactly = 1) { platformRecognizer.startListening(any()) }

        // Orphaned on-device recognizer was cleaned up before the retry
        verify(atLeast = 1) { onDeviceRecognizer.cancel() }
        verify(atLeast = 1) { onDeviceRecognizer.destroy() }
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
}
