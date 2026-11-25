package com.kernel.ai.assistant

import android.media.MediaRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WakeMicContentionGateTest {

    private val own = 101
    private val speechToText = 303
    private val other = 202
    private val mic = MediaRecorder.AudioSource.MIC
    private val camcorder = MediaRecorder.AudioSource.CAMCORDER
    private val voiceCall = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION
    private val hotword = WakeMicContentionGate.HOTWORD_SOURCE

    private var released = 0
    private var rearmed = 0
    private var rearmAllowedNow = true
    private var now = 0L
    private val scheduled = mutableListOf<Long>()
    private var ownIds: Set<Int> = setOf(own)
    private lateinit var gate: WakeMicContentionGate

    @BeforeEach
    fun setUp() {
        gate = WakeMicContentionGate(
            releaseMic = { released++ },
            rearmMic = { rearmed++ },
            rearmAllowed = { rearmAllowedNow },
            ownSessionIds = { ownIds },
            clockMs = { now },
            scheduleRecheck = { scheduled += it },
        )
    }

    private fun ownClient(silenced: Boolean = false) = MicRecordingClient(own, voiceRecognition, silenced)
    private fun otherClient(source: Int, silenced: Boolean = false) = MicRecordingClient(other, source, silenced)

    @Test
    fun `a recorder app on MIC pauses the wake mic once`() {
        gate.onRecordingClients(listOf(ownClient(), otherClient(mic)))
        gate.onRecordingClients(listOf(ownClient(), otherClient(mic)))
        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `camcorder and voice call clients pause the wake mic`() {
        gate.onRecordingClients(listOf(otherClient(camcorder)))
        assertTrue(gate.paused)
        gate.onRecordingClients(emptyList())
        gate.onRecordingClients(listOf(otherClient(voiceCall)))
        assertTrue(gate.paused)
        assertEquals(2, released)
    }

    @Test
    fun `re-arms when the other app stops recording`() {
        gate.onRecordingClients(listOf(otherClient(mic)))
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `another always-on VOICE_RECOGNITION listener never pauses`() {
        gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
        assertFalse(gate.paused)
        assertEquals(0, released)
    }

    @Test
    fun `a system hotword client (source 1999) never pauses`() {
        gate.onRecordingClients(listOf(otherClient(hotword)))
        assertFalse(gate.paused)
        assertEquals(0, released)
    }

    @Test
    fun `an always-on listener alongside a MIC recorder pauses only for the recorder`() {
        gate.onRecordingClients(listOf(otherClient(voiceRecognition), otherClient(mic)))
        assertTrue(gate.paused)
        gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `Jandal's own capture alone never pauses`() {
        gate.onRecordingClients(listOf(ownClient()))
        assertFalse(gate.paused)
    }

    @Test
    fun `Jandal's speech-to-text capture never pauses the wake mic`() {
        ownIds = setOf(own, speechToText)
        gate.onRecordingClients(listOf(ownClient(), MicRecordingClient(speechToText, mic, silenced = false)))
        assertFalse(gate.paused)
        assertEquals(0, released)
    }

    @Test
    fun `no active clients does nothing`() {
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(0, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `another app recording while our detector holds no capture still pauses`() {
        ownIds = emptySet()
        gate.onRecordingClients(listOf(otherClient(mic)))
        assertTrue(gate.paused)
    }

    @Test
    fun `our capture silenced by a higher-priority client pauses`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `silenced, then only a VOICE_RECOGNITION silencer remains, stays paused until it leaves and the back-off passes`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
        assertTrue(gate.paused)
        assertEquals(0, rearmed)

        gate.onRecordingClients(emptyList())
        assertTrue(gate.paused)
        assertEquals(listOf(WakeMicContentionGate.SILENCE_BACKOFF_MS), scheduled)

        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `silenced with no other client re-arms only after the back-off, not on the first empty update`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 4_000L
        gate.onRecordingClients(emptyList())
        assertTrue(gate.paused)
        assertEquals(listOf(WakeMicContentionGate.SILENCE_BACKOFF_MS - 4_000L), scheduled)

        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `a silence-backed pause re-arms from an empty update after the back-off`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = WakeMicContentionGate.SILENCE_BACKOFF_MS + 1
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `a voice session holding the mic defers the re-arm`() {
        rearmAllowedNow = false
        gate.onRecordingClients(listOf(otherClient(mic)))
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)
        assertEquals(0, rearmed)
    }

    @Test
    fun `a second contention after a re-arm releases again`() {
        gate.onRecordingClients(listOf(otherClient(mic)))
        gate.onRecordingClients(emptyList())
        gate.onRecordingClients(listOf(otherClient(mic)))
        assertTrue(gate.paused)
        assertEquals(2, released)
    }
}
