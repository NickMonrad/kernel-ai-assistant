package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.hardware.HardwareTier

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
        displayName = "Piper",
        description = "Natural-sounding local Piper voices via Sherpa-ONNX. Falls back to Android TTS while the voice downloads or if playback fails.",
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
        fun fromStorage(value: String?, default: VoiceOutputEngine = AndroidTts): VoiceOutputEngine =
            entries.firstOrNull { it.name == value } ?: default

        /**
         * Default engine for a device that has never chosen one: Piper (Cori) on mid-range and
         * flagship devices, Android TTS on low-power devices where Piper synthesis lags.
         */
        fun defaultForTier(tier: HardwareTier): VoiceOutputEngine =
            if (tier == HardwareTier.LOW_POWER) AndroidTts else SherpaExperimental

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
            default: VoiceOutputEngine = AndroidTts,
        ): VoiceOutputEngine {
            val engine = fromStorage(value, default)
            return engine.takeIf {
                it in entriesForBuild(
                    isRelease = isRelease,
                    inflectEligible = inflectEligible,
                )
            } ?: AndroidTts
        }
    }
}
