package com.kernel.ai.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WakeMicContentionGateTest {

    private val own = 101
    private val other = 202

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

    @Test
    fun `another app recording pauses the wake mic once`() {
        gate.onRecordingClients(listOf(MicRecordingClient(own, silenced = false), MicRecordingClient(other, false)))
        gate.onRecordingClients(listOf(MicRecordingClient(own, silenced = false), MicRecordingClient(other, false)))

        assertTrue(gate.paused)
        assertEquals(1, released)
        assertEquals(0, rearmed)
    }

    @Test
    fun `re-arms when the other app stops recording`() {
        gate.onRecordingClients(listOf(MicRecordingClient(other, false)))
        gate.onRecordingClients(listOf(MicRecordingClient(own, false)))

        assertFalse(gate.paused)
        assertEquals(1, released)
        assertEquals(1, rearmed)
    }

    @Test
    fun `Jandal's own capture alone never pauses`() {
        gate.onRecordingClients(listOf(MicRecordingClient(own, silenced = false)))

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
        gate.onRecordingClients(listOf(MicRecordingClient(other, false)))

        assertTrue(gate.paused)
        assertEquals(1, released)
    }

    @Test
    fun `our capture silenced by a higher-priority client pauses, and un-silenced re-arms`() {
        gate.onRecordingClients(listOf(MicRecordingClient(own, silenced = true)))
        assertTrue(gate.paused)
        assertEquals(1, released)

        gate.onRecordingClients(listOf(MicRecordingClient(own, silenced = false)))
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `a voice session holding the mic defers the re-arm, and the voice path re-arms later`() {
        gate.onRecordingClients(listOf(MicRecordingClient(other, false)))

        rearmAllowedNow = false
        gate.onRecordingClients(listOf(MicRecordingClient(own, false)))
        assertFalse(gate.paused)
        assertEquals(0, rearmed)
    }

    @Test
    fun `a second contention after a re-arm releases again`() {
        gate.onRecordingClients(listOf(MicRecordingClient(other, false)))
        gate.onRecordingClients(emptyList())
        gate.onRecordingClients(listOf(MicRecordingClient(other, false)))

        assertTrue(gate.paused)
        assertEquals(2, released)
        assertEquals(1, rearmed)
    }
}
