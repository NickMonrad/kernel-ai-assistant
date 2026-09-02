package com.kernel.ai.core.voice

enum class VoiceOutputEngine(
    val displayName: String,
    val description: String,
    val debugOnly: Boolean = false,
) {
    AndroidTts(
        displayName = "Android TTS",
        description = "Uses the platform text-to-speech engine and works without local Sherpa assets.",
    ),
    SherpaExperimental(
        displayName = "Sherpa Piper (Experimental)",
        description = "Uses locally prepared Sherpa-ONNX Piper voices and falls back to Android TTS if setup is missing or playback fails.",
    ),
    KokoroExperimental(
        displayName = "Kokoro (Experimental)",
        description = "Studio-grade Kokoro-82M TTS. Requires a ~130MB download. Falls back to Android TTS if unavailable.",
        debugOnly = true,
    ),
    InflectMicroExperimental(
        displayName = "Inflect Micro",
        description = "Higher-quality local Inflect Micro v2 speech for supported high-memory devices; falls back to Android TTS if unavailable.",
        debugOnly = false,
    ),
    ;

    companion object {
        fun fromStorage(value: String?): VoiceOutputEngine =
            entries.firstOrNull { it.name == value } ?: AndroidTts

        /**
         * Returns the engine catalogue allowed for this build and device.
         *
         * Inflect is release-visible only when the caller has established that the device is in
         * the validated high-memory class. The caller supplies that capability so this enum does
         * not duplicate hardware detection.
         */
        fun entriesForBuild(
            isRelease: Boolean,
            inflectEligible: Boolean,
        ): List<VoiceOutputEngine> = entries.filter { engine ->
            !isRelease ||
                (!engine.debugOnly &&
                    (engine != InflectMicroExperimental || inflectEligible))
        }

        fun resolveForBuild(
            value: String?,
            isRelease: Boolean,
            inflectEligible: Boolean,
        ): VoiceOutputEngine {
            val engine = fromStorage(value)
            return engine.takeIf {
                it in entriesForBuild(
                    isRelease = isRelease,
                    inflectEligible = inflectEligible,
                )
            } ?: AndroidTts
        }
    }
}
