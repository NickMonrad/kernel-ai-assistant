package com.kernel.ai.core.voice

import com.kernel.ai.core.inference.hardware.HardwareTier

enum class VoiceOutputEngine(
    val displayName: String,
    val description: String,
    val debugOnly: Boolean = false,
) {
    AndroidTts(
        displayName = "Android TTS",
        description = "Uses the device's built-in speech voice. Works without a download.",
    ),
    SherpaExperimental(
        displayName = "Piper",
        description = "Natural-sounding local voices. Falls back to Android TTS while the voice downloads or if playback fails.",
        debugOnly = false,
    ),
    KokoroExperimental(
        displayName = "Kokoro",
        description = "Kokoro-82M speech on this device. Optional download of about 351 MB. Falls back to Inflect Micro, then Android TTS.",
        debugOnly = false,
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
         * Release offers Piper (Cori), Kokoro and Android TTS, plus Inflect Micro on eligible devices. Engines
         * marked debugOnly stay in debug builds.
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
