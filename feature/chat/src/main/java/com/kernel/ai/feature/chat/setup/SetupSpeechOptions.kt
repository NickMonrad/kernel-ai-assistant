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
    fun inputOptions(androidNativeReady: Boolean): List<VoiceInputEngine> =
        if (androidNativeReady) listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative) else listOf(VoiceInputEngine.Auto)

    /** One plain-English line explaining why Android native is missing, or null when it is offered. */
    fun inputReason(androidNativeReady: Boolean): String? =
        if (androidNativeReady) {
            null
        } else {
            "Android's own listener can't be used on this phone, so Automatic is the one that works here."
        }

    /** Cori is always offered. Android's built-in voice is offered only when a TTS engine is installed. */
    fun outputOptions(hasSystemTts: Boolean): List<VoiceOutputEngine> =
        if (hasSystemTts) {
            listOf(VoiceOutputEngine.SherpaExperimental, VoiceOutputEngine.AndroidTts)
        } else {
            listOf(VoiceOutputEngine.SherpaExperimental)
        }

    /** [candidate] when the wizard offers it, otherwise null. */
    fun <T> offered(candidate: T, options: List<T>): T? = candidate.takeIf { it in options }

    /** The tier default speech output, or the first offered option when the default is not offered. */
    fun defaultOutput(tier: HardwareTier, options: List<VoiceOutputEngine>): VoiceOutputEngine =
        offered(VoiceOutputEngine.defaultForTier(tier), options) ?: options.first()
}
