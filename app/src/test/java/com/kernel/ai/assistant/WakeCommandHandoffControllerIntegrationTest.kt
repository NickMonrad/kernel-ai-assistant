package com.kernel.ai.assistant

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.kernel.ai.core.voice.AcousticEventType
import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.NativeAndroidVoiceInputController
import com.kernel.ai.core.voice.StartListeningCueContext
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.StartListeningCueResult
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.WakeWordDetector
import com.kernel.ai.alarm.shouldPlayClockAlertListeningCue
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Controller-to-wake-session regressions for review 4836608504, using the REAL
 * [NativeAndroidVoiceInputController] (mocked platform support) through the full
 * [WakeWordService.runWakeCommandHandoff] path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeCommandHandoffControllerIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var controller: NativeAndroidVoiceInputController
    private lateinit var listeners: MutableList<RecognitionListener>
    private lateinit var recognizers: MutableList<SpeechRecognizer>

    private val availability = AndroidNativeRecognitionAvailability(
        isRecognitionAvailable = true,
        isOnDeviceRecognitionAvailable = true,
        languageTag = "en-AU",
        languageDisplayName = "English (Australia)",
        localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = mockk<Context>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        val executor = mockk<Executor>(relaxed = true)
        every { context.mainExecutor } returns executor
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() }

        val support = mockk<AndroidNativeRecognitionSupport>(relaxed = true)
        every { support.getCaptureAvailability() } returns availability
        listeners = mutableListOf()
        recognizers = mutableListOf()
        every { support.createPlatformSpeechRecognizer() } answers {
            val recognizer = mockk<SpeechRecognizer>(relaxed = true)
            recognizers += recognizer
            every { recognizer.setRecognitionListener(any()) } answers {
                listeners += firstArg<RecognitionListener>()
            }
            recognizer
        }
        controller = NativeAndroidVoiceInputController(context, support)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class OrderingDetector : WakeWordDetector {
        override val isAvailable: Boolean get() = true
        var released = false
        override fun start(
            generationId: Long,
            onDetected: () -> Unit,
            verifyWindow: ((ShortArray) -> Boolean)?,
        ) = Unit

        override fun stop() {
            released = true
        }
    }

    private fun cuePlayer(): StartListeningCuePlayer =
        mockk<StartListeningCuePlayer>().apply {
            every { playCue(StartListeningCueContext.WAKE_WORD) } returns
                StartListeningCueResult(started = true, context = StartListeningCueContext.WAKE_WORD)
        }

    private fun journal(events: MutableList<Pair<String, Long>>) = WakeSessionJournal(
        generationId = 7L,
        sessionId = 9L,
        emit = { type, _, sessionId, _ -> events += type to sessionId },
    )

    @Test
    fun `refreshed watchdog exhaustion skips the wake attempt-2 and re-arms once`() = runTest {
        val detector = OrderingDetector()
        val journalEvents = mutableListOf<Pair<String, Long>>()
        var rearmCount = 0
        var routedCount = 0

        val job = launch {
            runWakeCommandHandoff(
                wakeWordDetector = detector,
                voiceInputController = controller,
                cuePlayer = cuePlayer(),
                generationId = 7L,
                sessionId = 9L,
                journal = journal(journalEvents),
                routeTranscript = { routedCount++; true },
                onSessionTerminal = { rearmCount++ },
            )
        }
        runCurrent()

        // 1. Attempt 1 reaches readiness (one recognizer started).
        assertEquals(1, recognizers.size)
        listeners[0].onReadyForSpeech(mockk<Bundle>(relaxed = true))
        runCurrent()

        // 2. The first recognizer receives a silent ERROR_NO_MATCH.
        // 3. One in-place refresh starts under the same capture session.
        listeners[0].onError(SpeechRecognizer.ERROR_NO_MATCH)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, recognizers.size, "one in-place refresh recognizer")

        // 4. The refreshed recognizer reaches readiness internally.
        listeners[1].onReadyForSpeech(mockk<Bundle>(relaxed = true))
        runCurrent()

        // 5. Its no-speech watchdog expires → 6. categorized error.
        advanceTimeBy(5_000)
        runCurrent()

        // 7. The wake session must not start attempt 2 (no third recognizer).
        // 8. Exactly one SESSION_CANCELLED and one re-arm.
        job.join()
        assertEquals(2, recognizers.size, "no attempt-2 recognizer after exhausted no-speech window")
        assertEquals(1, rearmCount)
        assertEquals(0, routedCount)
        val recorded = journalEvents.map { it.first }
        assertEquals(1, recorded.count { it == AcousticEventType.STT_START_REQUESTED }, "attempt 1 only")
        assertEquals(1, recorded.count { it == AcousticEventType.STT_ERROR })
        assertEquals(1, recorded.count { it == AcousticEventType.SESSION_CANCELLED })
        assertEquals(1, recorded.count { it == AcousticEventType.CUE_REQUESTED }, "one cue for the one readiness")
        assertTrue(journalEvents.all { it.second == 9L }, "capture session correlation preserved")
    }

    @Test
    fun `clock-alert listening cue plays exactly once across an in-place refresh`() = runTest {
        // The clock-alert path plays its listening cue for every owned AlertCommand
        // readiness event; the controller must emit exactly one readiness per capture
        // session, so an in-place refresh cannot replay the cue.
        val readinessEvents = mutableListOf<VoiceInputEvent.ListeningStarted>()

        val job = launch {
            controller.events.collect { event ->
                if (event is VoiceInputEvent.ListeningStarted) readinessEvents += event
            }
        }
        runCurrent()

        controller.startListening(VoiceCaptureMode.AlertCommand)
        listeners[0].onReadyForSpeech(mockk<Bundle>(relaxed = true))
        runCurrent()
        listeners[0].onError(SpeechRecognizer.ERROR_NO_MATCH)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, recognizers.size, "one in-place refresh recognizer")
        assertEquals(
            2,
            listeners.size,
            "second recognizer must register its listener; recognizers=${recognizers.size}",
        )
        listeners[1].onReadyForSpeech(mockk<Bundle>(relaxed = true))
        runCurrent()

        assertEquals(1, readinessEvents.size, "one readiness per capture session")
        val sessionId = readinessEvents.single().captureSessionId
        val owned = readinessEvents.count {
            shouldPlayClockAlertListeningCue(it, sessionId, isVoiceListening = true)
        }
        assertEquals(1, owned, "clock-alert plays exactly one cue across the refresh")
        job.cancel()
    }
}
