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
    private val causes = mutableListOf<MicContention>()
    private var rearmed = 0
    private var rearmAllowedNow = true
    private var now = 0L
    private val scheduled = mutableListOf<Long>()
    private var ownIds: Set<Int> = setOf(own)
    private lateinit var gate: WakeMicContentionGate
    private val otherResumes = mutableListOf<Pair<ContentionResumeSource, Long>>()
    private var silenceStarts = 0
    private val silenceRuns = mutableListOf<Triple<SilenceRunEnd, Int, Long>>()

    @BeforeEach
    fun setUp() {
        gate = WakeMicContentionGate(
            releaseMic = { cause -> causes += cause; released++ },
            rearmMic = { rearmed++ },
            rearmAllowed = { rearmAllowedNow },
            ownSessionIds = { ownIds },
            clockMs = { now },
            scheduleRecheck = { scheduled += it },
            onOtherAppResumed = { source, heldMs -> otherResumes += source to heldMs },
            onSilenceRunStarted = { silenceStarts++ },
            onSilenceRunEnded = { end, probes, heldMs -> silenceRuns += Triple(end, probes, heldMs) },
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
    fun `the release names its cause, another app's recorder or the audio server silencing us`() {
        gate.onRecordingClients(listOf(otherClient(camcorder)))
        gate.onRecordingClients(emptyList())
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertEquals(listOf(MicContention.OTHER_APP, MicContention.SILENCED), causes)
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
    fun `an always-on silencer does not extend the hold, the probe re-arms at the back-off`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
        assertTrue(gate.paused)
        assertEquals(0, rearmed)

        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `a silenced probe doubles the hold up to the cap while the always-on listener stays`() {
        val holds = mutableListOf<Long>()
        var at = 0L
        repeat(6) {
            now = at
            scheduled.clear()
            gate.onRecordingClients(listOf(ownClient(silenced = true), otherClient(voiceRecognition)))
            val hold = scheduled.single()
            holds += hold
            at += hold
            now = at
            gate.onRecordingClients(listOf(otherClient(voiceRecognition)))
            assertFalse(gate.paused)
        }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), holds)
        assertEquals(6, released)
        assertEquals(6, rearmed)
    }

    @Test
    fun `a probe that survives one hold resets the hold to the first back-off`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())
        assertFalse(gate.paused)

        now = 2 * WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())

        scheduled.clear()
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertEquals(listOf(WakeMicContentionGate.SILENCE_BACKOFF_MS), scheduled)
    }

    @Test
    fun `a user resume probes ahead of the hold, but a recorder on MIC still keeps the mic`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 1_000L
        gate.onResumeRequested(listOf(otherClient(mic)))
        assertTrue(gate.paused)
        assertEquals(0, rearmed)

        gate.onResumeRequested(emptyList())
        assertFalse(gate.paused)
        assertEquals(1, rearmed)
    }

    @Test
    fun `silenced with no other client re-arms only after the back-off, not on the first empty update`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 4_000L
        gate.onRecordingClients(emptyList())
        assertTrue(gate.paused)
        assertEquals(WakeMicContentionGate.SILENCE_BACKOFF_MS - 4_000L, scheduled.last())

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

    @Test
    fun `a SILENCED back-off run logs one start, then one end when a probe survives its hold`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onHoldRecheck(emptyList())
        now = 2 * WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())

        assertEquals(1, silenceStarts)
        assertEquals(
            listOf(Triple(SilenceRunEnd.PROBE_SURVIVED, 1, 2 * WakeMicContentionGate.SILENCE_BACKOFF_MS)),
            silenceRuns,
        )
        assertTrue(otherResumes.isEmpty())
    }

    @Test
    fun `a probe silenced again within its hold adds no pause or resume event`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onHoldRecheck(emptyList())
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertEquals(2, released)
        assertEquals(1, silenceStarts)
        assertTrue(silenceRuns.isEmpty())

        now = 3 * WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onHoldRecheck(emptyList())
        now = 5 * WakeMicContentionGate.SILENCE_BACKOFF_MS
        gate.onRecordingClients(emptyList())

        assertEquals(1, silenceStarts)
        assertEquals(
            listOf(Triple(SilenceRunEnd.PROBE_SURVIVED, 2, 5 * WakeMicContentionGate.SILENCE_BACKOFF_MS)),
            silenceRuns,
        )
    }

    @Test
    fun `a user Resume ends the back-off run with the probes it made`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 1_000L
        gate.onResumeRequested(emptyList())

        assertFalse(gate.paused)
        assertEquals(listOf(Triple(SilenceRunEnd.USER_RESUME, 1, 1_000L)), silenceRuns)
    }

    @Test
    fun `an own in-app capture ending ends the back-off run at once, with no other-app resume`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 1_000L
        gate.onOwnVoiceCaptureEnded()
        gate.onRecordingClients(emptyList())

        assertFalse(gate.paused)
        assertEquals(1, rearmed)
        assertEquals(listOf(Triple(SilenceRunEnd.OWN_CAPTURE_ENDED, 0, 1_000L)), silenceRuns)
        assertTrue(otherResumes.isEmpty())
    }

    @Test
    fun `a silence after an ended run starts a new run`() {
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        now = 1_000L
        gate.onOwnVoiceCaptureEnded()
        gate.onRecordingClients(emptyList())
        gate.onRecordingClients(listOf(ownClient(silenced = true)))
        assertEquals(2, silenceStarts)
    }

    @Test
    fun `an other-app pause keeps its own resume event and starts no back-off run`() {
        gate.onRecordingClients(listOf(otherClient(mic)))
        now = 500L
        gate.onRecordingClients(emptyList())

        assertEquals(listOf(ContentionResumeSource.RECORDING_CHANGED to 500L), otherResumes)
        assertEquals(0, silenceStarts)
        assertTrue(silenceRuns.isEmpty())
    }
}
