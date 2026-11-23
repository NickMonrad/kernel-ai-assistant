package com.kernel.ai.core.voice

/** Why the endpointer ended an asr4all capture. The reader journals it through the capture's end record. */
internal enum class EndpointReason {
    /** The utterance was armed (see [MIN_SPEECH_SAMPLES]), then quiet for [ENDPOINT_SILENCE_SAMPLES]. */
    VAD,

    /** Nothing armed within [NO_SPEECH_SAMPLES] of captured audio. */
    NO_SPEECH,

    /** [CAPTURE_CAP_SAMPLES] of captured audio. */
    CAP,
}

/** 700 ms at 16 kHz: the trailing silence that ends an utterance once it is armed. A pause this long ends the capture. */
internal const val ENDPOINT_SILENCE_SAMPLES = 16_000 * 700 / 1000

/** 300 ms at 16 kHz of consecutive speech before the utterance is armed. A 100 ms start cue or a lone blip cannot arm it. */
internal const val MIN_SPEECH_SAMPLES = 16_000 * 300 / 1000

/**
 * 500 ms: captured audio from capture start that cannot arm the endpoint, because the start cue plays there. The
 * production value for the asr4all reader; [UtteranceEndpointer.cueGuardSamples] defaults to 0 for logic tests.
 */
internal const val START_CUE_GUARD_MS = 500L

/** 10 s at 16 kHz without an armed utterance: the no-speech window. */
internal const val NO_SPEECH_SAMPLES = 16_000 * 10

/** 20 s at 16 kHz: the hard cap on one capture, well under the 60 s the old chunk clock allowed. */
internal const val CAPTURE_CAP_SAMPLES = 16_000 * 20

/** Bounds for one capture mode: the audio cap and end-of-speech silence on the captured-audio clock, and the wall limit. */
internal data class CaptureLimits(val capSamples: Int, val silenceSamples: Int, val wallLimitMs: Long)

/** Commands, slot replies and alert commands: 20 s cap, 700 ms end silence, 30 s wall limit (20 s + 10 s load and decode). */
internal val COMMAND_CAPTURE_LIMITS = CaptureLimits(CAPTURE_CAP_SAMPLES, ENDPOINT_SILENCE_SAMPLES, 30_000L)

/** Chat dictation: 60 s cap and 1.5 s end silence, so a pause to think does not end it. Wall limit 70 s (60 s + 10 s). */
internal val DICTATION_CAPTURE_LIMITS = CaptureLimits(16_000 * 60, 16_000 * 1500 / 1000, 70_000L)

internal fun captureLimitsFor(mode: VoiceCaptureMode): CaptureLimits = when (mode) {
    VoiceCaptureMode.Dictation -> DICTATION_CAPTURE_LIMITS
    VoiceCaptureMode.Command, VoiceCaptureMode.SlotReply, VoiceCaptureMode.AlertCommand -> COMMAND_CAPTURE_LIMITS
}

/**
 * Endpoint decision on captured-audio time. The reader calls [onChunk] for every chunk as it is read, so the
 * decision never waits on the decoder: a slow decode delays the transcript, not the end of the capture.
 *
 * Arming: an utterance is armed only after [minSpeechSamples] of consecutive speech. Until then silence never ends it
 * by VAD, so a click cannot end a capture before the user speaks. Once armed it stays armed.
 *
 * Start cue: a chunk that starts inside [cueGuardSamples] is never speech. The cue plays at capture start, and its
 * speech-like tail would otherwise arm the endpoint and end the capture before the user speaks. The audio is still
 * decoded; only the endpoint decision ignores it.
 */
internal class UtteranceEndpointer(
    private val silenceSamples: Int = ENDPOINT_SILENCE_SAMPLES,
    private val noSpeechSamples: Int = NO_SPEECH_SAMPLES,
    private val capSamples: Int = CAPTURE_CAP_SAMPLES,
    private val minSpeechSamples: Int = MIN_SPEECH_SAMPLES,
    private val cueGuardSamples: Int = 0,
) {
    private var captured = 0L
    private var silentSamples = 0L
    private var speechRun = 0L

    /** Milliseconds of audio recorded so far, on the captured-audio clock (16 kHz mono). */
    val capturedMs: Long get() = captured * 1000L / 16_000L

    /** True once any chunk has been counted as speech (the onset). Audio inside the cue guard never counts. */
    var heardSpeech = false
        private set

    /** True once [minSpeechSamples] of consecutive speech has been heard. Only an armed utterance can end on silence. */
    var armed = false
        private set

    /**
     * Records a chunk of [samples] samples; returns the reason to end the capture, or null to keep listening.
     * [speech] is the VAD verdict. [hint] is a decoder signal that the chunk carried new text: it counts as speech only
     * until the utterance is armed, so it can rescue a VAD miss but cannot keep an armed utterance open.
     */
    fun onChunk(samples: Int, speech: Boolean, hint: Boolean = false): EndpointReason? {
        val chunkStart = captured
        captured += samples
        val voiced = (speech || (hint && !armed)) && chunkStart >= cueGuardSamples
        if (voiced) {
            heardSpeech = true
            speechRun += samples
            if (speechRun >= minSpeechSamples) armed = true
            silentSamples = 0
        } else {
            speechRun = 0
            silentSamples += samples
        }
        return when {
            armed && silentSamples >= silenceSamples -> EndpointReason.VAD
            !armed && captured >= noSpeechSamples -> EndpointReason.NO_SPEECH
            captured >= capSamples -> EndpointReason.CAP
            else -> null
        }
    }
}
