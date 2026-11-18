package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine

/**
 * Which speech choices the setup wizard offers on this device, and their defaults (#1855 part 2).
 * Pure: callers pass the device facts, so the rules are unit-testable without Android.
 */
internal object SetupSpeechOptions {

    /**
     * Automatic always works. Android native is offered only when Android's own recognizer is ready:
     * the availability check covers the service, the on-device recognizer, and the language pack.
     */
    fun inputOptions(androidNativeReady: Boolean, isRelease: Boolean = false): List<VoiceInputEngine> =
        if (isRelease) {
            listOf(VoiceInputEngine.AndroidNative)
        } else if (androidNativeReady) {
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative)
        } else {
            listOf(VoiceInputEngine.Auto)
        }

    /** One plain-English line explaining why Android native is missing, or null when it is offered. */
    fun inputReason(androidNativeReady: Boolean, isRelease: Boolean = false): String? =
        if (androidNativeReady) {
            null
        } else if (isRelease) {
            "Android speech recognition isn't ready on this phone. Voice input will be unavailable until Android speech recognition is available."
        } else {
            "Android's own listener can't be used on this phone, so Automatic is the one that works here."
        }

    /**
     * The voices setup offers, in the order Cori, Inflect Micro, Android's built-in voice. Uses the same build rule
     * as Settings ([VoiceOutputEngine.entriesForBuild]): release builds offer Inflect only where [inflectEligible],
     * debug builds everywhere. Kokoro is never offered in setup. Android's built-in voice needs an installed TTS engine.
     */
    fun outputOptions(hasSystemTts: Boolean, isRelease: Boolean, inflectEligible: Boolean): List<VoiceOutputEngine> {
        if (isRelease) return listOf(VoiceOutputEngine.AndroidTts)
        val offered = VoiceOutputEngine.entriesForBuild(isRelease, inflectEligible)
        return listOf(
            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.InflectMicroExperimental,
            VoiceOutputEngine.AndroidTts,
        ).filter { it in offered && (it != VoiceOutputEngine.AndroidTts || hasSystemTts) }
    }

    /** [candidate] when the wizard offers it, otherwise null. */
    fun <T> offered(candidate: T, options: List<T>): T? = candidate.takeIf { it in options }

    /** The tier default speech output, or the first offered option when the default is not offered. */
    fun defaultOutput(tier: HardwareTier, options: List<VoiceOutputEngine>): VoiceOutputEngine =
        offered(VoiceOutputEngine.defaultForTier(tier), options) ?: options.first()
}
