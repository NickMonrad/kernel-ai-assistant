package com.kernel.ai.assistant

import android.media.MediaRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WakeMicContentionGateTest {

    private val own = 101
    private val other = 202
    private val mic = MediaRecorder.AudioSource.MIC
    private val camcorder = MediaRecorder.AudioSource.CAMCORDER
    private val voiceCall = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION

    private var released = 0
    private var rearmed = 0
    private var rearmAllowedNow = true
    private var ownSession: Int? = own
    private lateinit var gate: WakeMicContentionGate

    @BeforeEach
    fun setUp() {
        gate = WakeMicContentionGate(
            releaseMic = { released++ },
            rearmMic = { rearmed++ },
            rearmAllowed = { rearmAllowedNow },
            ownSessionId = { ownSession },
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
        assertEquals(0, rearmed)
    }

    @Test
    fun `camcorder and voice call clients pause the wake mic`() {
        gate.onRecordingClients(listOf(ownClient(), otherClient(camcorder)))
        assertTrue(gate.paused)

        gate.onRecordingClients(listOf(ownClient(), otherClient(voiceCall)))
        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `re-arms when the other app stops recording`() {
        gate.onRecordingClients(listOf(otherClient(mic)))
        gate.onRecordingClients(listOf(ownClient()))

        assertFalse(gate.paused)
        assertEquals(1, released)
        assertEquals(1, rearmed)
    }

    @Test
    fun `another always-on VOICE_RECOGNITION listener never pauses`() {
        gate.onRecordingClients(listOf(ownClient(), otherClient(voiceRecognition)))

        assertFalse(gate.paused)
        assertEquals(0, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `an always-on listener alongside a MIC recorder pauses only for the recorder`() {
        gate.onRecordingClients(listOf(ownClient(), otherClient(voiceRecognition), otherClient(voiceRecognition)))
        assertFalse(gate.paused)

        gate.onRecordingClients(listOf(ownClient(), otherClient(voiceRecognition), MicRecordingClient(303, mic, false)))
        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `Jandal's own capture alone never pauses`() {
        gate.onRecordingClients(listOf(ownClient()))

        assertFalse(gate.paused)
        assertEquals(0, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `no active clients does nothing`() {
        ownSession = null
        gate.onRecordingClients(emptyList())

        assertFalse(gate.paused)
        assertEquals(0, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `another app recording while our detector holds no capture still pauses`() {
        ownSession = null
        gate.onRecordingClients(listOf(otherClient(mic)))

        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `our capture silenced by a higher-priority client pauses, and un-silenced re-arms`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertTrue(gate.paused)
        assertEquals(1, released)

        gate.onRecordingClients(listOf(ownClient(silenced = false)))
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `a voice session holding the mic defers the re-arm`() {
        gate.onRecordingClients(listOf(otherClient(mic)))

        rearmAllowedNow = false
        gate.onRecordingClients(listOf(ownClient()))
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
        assertEquals(1, rearmed)
    }
}
