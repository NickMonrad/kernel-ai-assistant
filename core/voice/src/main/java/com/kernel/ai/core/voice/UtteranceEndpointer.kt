package com.kernel.ai.core.voice

/** Why an asr4all capture ended on its own. Journaled as `stt_endpoint reason=VAD|NO_SPEECH|CAP` (enum token). */
internal enum class EndpointReason {
    /** Speech was heard, then quiet for [ENDPOINT_SILENCE_SAMPLES]. */
    VAD,

    /** No speech within [NO_SPEECH_SAMPLES] of captured audio. */
    NO_SPEECH,

    /** [CAPTURE_CAP_SAMPLES] of captured audio. */
    CAP,
}

/** 700 ms at 16 kHz: the trailing silence that ends an utterance after speech. */
internal const val ENDPOINT_SILENCE_SAMPLES = 16_000 * 700 / 1000

/** 10 s at 16 kHz without speech: the no-speech window. */
internal const val NO_SPEECH_SAMPLES = 16_000 * 10

/** 20 s at 16 kHz: the hard cap on one capture, well under the 60 s the old chunk clock allowed. */
internal const val CAPTURE_CAP_SAMPLES = 16_000 * 20

/**
 * Endpoint decision on captured-audio time. The reader calls [onChunk] for every chunk as it is read, so the
 * decision never waits on the decoder: a slow decode delays the transcript, not the end of the capture.
 */
internal class UtteranceEndpointer(
    private val silenceSamples: Int = ENDPOINT_SILENCE_SAMPLES,
    private val noSpeechSamples: Int = NO_SPEECH_SAMPLES,
    private val capSamples: Int = CAPTURE_CAP_SAMPLES,
) {
    private var captured = 0L
    private var silentSamples = 0L

    /** Milliseconds of audio recorded so far, on the captured-audio clock (16 kHz mono). */
    val capturedMs: Long get() = captured * 1000L / 16_000L

    /** True once any chunk has been classified as speech. */
    var heardSpeech = false
        private set

    /** Records a chunk of [samples] samples; returns the reason to end the capture, or null to keep listening. */
    fun onChunk(samples: Int, speech: Boolean): EndpointReason? {
        captured += samples
        if (speech) {
            heardSpeech = true
            silentSamples = 0
        } else {
            silentSamples += samples
        }
        return when {
            heardSpeech && silentSamples >= silenceSamples -> EndpointReason.VAD
            !heardSpeech && captured >= noSpeechSamples -> EndpointReason.NO_SPEECH
            captured >= capSamples -> EndpointReason.CAP
            else -> null
        }
    }
}
