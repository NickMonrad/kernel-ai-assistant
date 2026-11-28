package com.kernel.ai.assistant

import android.media.MediaRecorder

/** One active microphone client from `AudioRecordingCallback`, reduced to what arbitration needs (#2108). */
internal data class MicRecordingClient(val sessionId: Int, val source: Int, val silenced: Boolean)

/** Why the gate released the wake mic: another app's recording, or the audio server silencing our capture (#2109). */
internal enum class MicContention { OTHER_APP, SILENCED }

/** The source that ended an other-app contention pause, reported on resume (#2178 diagnostics). */
internal enum class ContentionResumeSource { RECORDING_CHANGED, HOLD_EXPIRED, USER_RESUME }

/** How a SILENCED back-off run ended (#2178 diagnostics). */
internal enum class SilenceRunEnd { PROBE_SURVIVED, USER_RESUME, OWN_CAPTURE_ENDED }

/**
 * Steps wake capture aside while another app records, and re-arms when that ends (#2108).
 *
 * Owner rule (#2109): always-on wake and hotword listeners are not recordings. Other apps' clients
 * that capture with [MediaRecorder.AudioSource.VOICE_RECOGNITION] or the hidden hotword source
 * ([HOTWORD_SOURCE]) are ignored. Recorders, camera video and calls use other sources (MIC, CAMCORDER,
 * VOICE_COMMUNICATION, ...) and pause Jandal. Jandal's own captures, identified by [ownSessionIds]
 * (the wake detector and the speech-to-text recorders), never pause us unless the audio server silenced one.
 *
 * A release caused by the audio server silencing our capture is held for [silenceHoldMs], then the detector
 * probes by re-arming, whatever always-on clients remain. A probe that is silenced again doubles the hold up
 * to [SILENCE_BACKOFF_MAX_MS]; a probe that survives one hold resets it to [SILENCE_BACKOFF_MS]. A non-always-on
 * recorder still keeps Jandal paused for as long as it records. A user Resume calls [onResumeRequested], which
 * probes at once (#2109).
 *
 * [rearmAllowed] is false while a voice session or wake handoff owns the mic; the transition out of
 * contention then does not re-arm, and that owner re-arms on its own terminal event. [rearmMic] must
 * still refuse to take the mic while the user pause is set, because the voice path calls it independently.
 */
internal class WakeMicContentionGate(
    private val releaseMic: (MicContention) -> Unit,
    private val rearmMic: () -> Unit,
    private val rearmAllowed: () -> Boolean,
    private val ownSessionIds: () -> Set<Int>,
    private val clockMs: () -> Long,
    /** Asks for a later [onRecordingClients] with the current clients, so a time-based hold can end without a callback. */
    private val scheduleRecheck: (delayMs: Long) -> Unit,
    /** Reports each other-app resume: the source that ended the pause and how long the wake mic was held (#2178). */
    private val onOtherAppResumed: (source: ContentionResumeSource, heldMs: Long) -> Unit,
    /** Reports the start of a SILENCED back-off run, once; a failed probe is part of the same run (#2178). */
    private val onSilenceRunStarted: () -> Unit,
    /** Reports the end of a SILENCED back-off run: how it ended, its probe count and wall time from the first silence. */
    private val onSilenceRunEnded: (end: SilenceRunEnd, probeCount: Int, heldMs: Long) -> Unit,
) {
    /** True while another app holds the microphone, so the wake detector must not capture. */
    @Volatile var paused: Boolean = false
        private set

    /** Cause and start time of the current pause, for the resume report (#2178). */
    private var pausedCause: MicContention = MicContention.OTHER_APP
    private var pausedAtMs: Long = 0L

    /** Clock time of the release caused by the audio server silencing our capture; null for other pauses. */
    private var silencedAtMs: Long? = null

    /** Hold after the next silence. Doubles when a probe is silenced again, resets when a probe survives. */
    private var silenceHoldMs: Long = SILENCE_BACKOFF_MS

    /** Clock time the current probe re-arm started; null when no probe is pending survival. */
    private var probeStartedAtMs: Long? = null

    /** Clock time the current SILENCED back-off run began; null when no run is active (#2178 diagnostics). */
    private var silenceRunStartedAtMs: Long? = null

    /** Probes started in the current back-off run. */
    private var silenceRunProbes: Int = 0

    /** Called with the full active-client list on every recording-configuration change, and on re-sync. */
    fun onRecordingClients(clients: List<MicRecordingClient>) = evaluate(clients, ContentionResumeSource.RECORDING_CHANGED)

    /** A scheduled recheck ran, so a time-based hold may have ended. */
    fun onHoldRecheck(clients: List<MicRecordingClient>) = evaluate(clients, ContentionResumeSource.HOLD_EXPIRED)

    /** The user pressed Resume: re-arm now unless another recorder holds the mic (#2109). */
    fun onResumeRequested(clients: List<MicRecordingClient>) = evaluate(clients, ContentionResumeSource.USER_RESUME)

    /**
     * Our own in-app voice capture ended (#2178). A SILENCED hold taken while that capture held the mic is
     * attributed to it, so the hold, its back-off and the back-off run are dropped. The next recording update
     * then re-arms at once.
     */
    fun onOwnVoiceCaptureEnded() {
        silencedAtMs = null
        silenceHoldMs = SILENCE_BACKOFF_MS
        probeStartedAtMs = null
        endSilenceRun(SilenceRunEnd.OWN_CAPTURE_ENDED)
    }

    private fun evaluate(clients: List<MicRecordingClient>, source: ContentionResumeSource) {
        val own = ownSessionIds()
        val ownClients = clients.filter { it.sessionId in own }
        val others = clients.filter { it.sessionId !in own }
        val silenced = ownClients.any { it.silenced }
        val contended = silenced || others.any { it.source !in ALWAYS_ON_SOURCES }

        if (!paused) {
            if (contended) {
                val cause = if (silenced) MicContention.SILENCED else MicContention.OTHER_APP
                paused = true
                pausedCause = cause
                pausedAtMs = clockMs()
                if (silenced) silencedPause() else silencedAtMs = null
                releaseMic(cause)
                return
            }
            probeStartedAtMs?.let { started ->
                if (clockMs() - started >= silenceHoldMs) {
                    silenceHoldMs = SILENCE_BACKOFF_MS
                    probeStartedAtMs = null
                    endSilenceRun(SilenceRunEnd.PROBE_SURVIVED)
                }
            }
            return
        }
        if (contended) return
        silencedAtMs?.let { since ->
            val remainingMs = since + silenceHoldMs - clockMs()
            if (remainingMs > 0 && source != ContentionResumeSource.USER_RESUME) {
                scheduleRecheck(remainingMs)
                return
            }
            // The hold has passed (or the user asked): probe. The survival check resets the hold later.
            probeStartedAtMs = clockMs()
            silenceRunProbes++
            scheduleRecheck(silenceHoldMs)
        }
        paused = false
        silencedAtMs = null
        if (pausedCause == MicContention.OTHER_APP) onOtherAppResumed(source, clockMs() - pausedAtMs)
        if (source == ContentionResumeSource.USER_RESUME) endSilenceRun(SilenceRunEnd.USER_RESUME)
        if (rearmAllowed()) rearmMic()
    }

    /** A SILENCED pause. The first one starts a back-off run; a failed probe only extends the hold (#2178). */
    private fun silencedPause() {
        if (silenceRunStartedAtMs == null) {
            silenceRunStartedAtMs = clockMs()
            silenceRunProbes = 0
            onSilenceRunStarted()
        }
        holdAfterSilence()
    }

    /** Ends the back-off run, if one is active, and reports how it ended with its probe count and wall time. */
    private fun endSilenceRun(end: SilenceRunEnd) {
        val startedAt = silenceRunStartedAtMs ?: return
        silenceRunStartedAtMs = null
        onSilenceRunEnded(end, silenceRunProbes, clockMs() - startedAt)
    }

    private fun holdAfterSilence() {
        val started = probeStartedAtMs
        val probeFailed = started != null && clockMs() - started < silenceHoldMs
        silenceHoldMs = if (probeFailed) minOf(silenceHoldMs * 2, SILENCE_BACKOFF_MAX_MS) else SILENCE_BACKOFF_MS
        probeStartedAtMs = null
        silencedAtMs = clockMs()
        scheduleRecheck(silenceHoldMs)
    }

    companion object {
        /** First hold after the audio server silences our capture, and the hold a surviving probe resets to (#2109). */
        const val SILENCE_BACKOFF_MS: Long = 10_000L

        /** Longest hold between silenced probes, so a long-running always-on listener is re-checked at least this often. */
        const val SILENCE_BACKOFF_MAX_MS: Long = 120_000L

        /**
         * The hidden `MediaRecorder.AudioSource.HOTWORD` constant. The SDK does not expose it, so its value is
         * listed here. System hotword clients capture with it and must not pause Jandal (#2109).
         */
        const val HOTWORD_SOURCE: Int = 1999

        /**
         * Sources used by always-on listeners (wake words, hotwords, assistant dictation). A client on one
         * of these never pauses Jandal.
         */
        val ALWAYS_ON_SOURCES: Set<Int> = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, HOTWORD_SOURCE)
    }
}
