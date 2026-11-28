package com.kernel.ai.core.voice

/** Card copy for each speech output engine, shared by setup step 2 and Settings > Voice (#1972). */
object VoiceOutputCopy {
    /** The sentence a voice sample speaks, shared by setup step 2 and Settings > Voice. */
    const val SAMPLE_TEXT = "Hi, this is Jandal. This is how this voice sounds."

    /** Shown when a Kokoro sample cannot play. */
    const val KOKORO_SAMPLE_FAILED_MESSAGE = "Couldn't play the sample with Kokoro."

    fun title(engine: VoiceOutputEngine): String = when (engine) {
        VoiceOutputEngine.AndroidTts -> "Phone's built-in voice"
        VoiceOutputEngine.InflectMicroExperimental -> "Inflect Micro"
        VoiceOutputEngine.SherpaExperimental -> "Cori, natural voice"
        VoiceOutputEngine.KokoroExperimental -> engine.displayName
    }

    fun body(engine: VoiceOutputEngine): String = when (engine) {
        VoiceOutputEngine.AndroidTts -> "No download. Sounds more robotic."
        VoiceOutputEngine.InflectMicroExperimental -> "Higher-quality voice. Works offline."
        VoiceOutputEngine.SherpaExperimental -> "Sounds natural and works offline. Can be slower on older phones."
        VoiceOutputEngine.KokoroExperimental -> engine.description
    }
}
