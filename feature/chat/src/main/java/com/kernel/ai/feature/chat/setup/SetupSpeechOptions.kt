package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine

/**
 * Which speech choices the setup wizard offers on this device, and their defaults (#1855 part 2).
 * Pure: callers pass the device facts, so the rules are unit-testable without Android.
 */
internal object SetupSpeechOptions {

    /**
     * Automatic always works. Android native is offered only when the on-device recognizer exists. The
     * on-device check is the real support signal (see [VoiceCapabilities]); a registered service alone is not.
     */
    fun inputOptions(capabilities: VoiceCapabilities): List<VoiceInputEngine> =
        if (capabilities.onDeviceRecognitionAvailable) {
            listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative)
        } else {
            listOf(VoiceInputEngine.Auto)
        }

    /** One plain-English line explaining why Android native is missing, or null when it is offered. */
    fun inputReason(capabilities: VoiceCapabilities): String? =
        if (capabilities.onDeviceRecognitionAvailable) {
            null
        } else {
            "This phone doesn't have Android's speech service, so Automatic is the one that works here."
        }

    /** Cori is always offered. Android's built-in voice is offered only when TextToSpeech reports an engine. */
    fun outputOptions(hasSystemTts: Boolean): List<VoiceOutputEngine> =
        if (hasSystemTts) {
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts)
        } else {
            listOf(VoiceOutputEngine.SherpaExperimental)
        }

    /** [candidate] when offered, otherwise the first option. Options are never empty. */
    fun <T> resolve(candidate: T, options: List<T>): T = candidate.takeIf { it in options } ?: options.first()

    /**
     * Default speech output: Cori when the saved engine is Cori. Otherwise the tier default, so an updated
     * build-5 install that stored Android TTS by default is offered Cori on a re-run (#1868).
     */
    fun initialOutput(stored: VoiceOutputEngine, tier: HardwareTier, options: List<VoiceOutputEngine>): VoiceOutputEngine =
        resolve(
            candidate = if (stored == VoiceOutputEngine.SherpaExperimental) stored else VoiceOutputEngine.defaultForTier(tier),
            options = options,
        )
}
