package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine

/**
 * Which speech choices the setup wizard offers on this device, and their defaults (#1855 part 2, #2173).
 * Pure: callers pass the device facts, so the rules are unit-testable without Android.
 */
internal object SetupSpeechOptions {

    /**
     * Automatic and asr4all streaming always work, both on the offline asr4all model (#2173). Android native is offered
     * only when Android's own recognizer is ready: the availability check covers the service, the on-device recognizer,
     * and the language pack.
     */
    fun inputOptions(androidNativeReady: Boolean): List<VoiceInputEngine> = buildList {
        add(VoiceInputEngine.Auto)
        add(VoiceInputEngine.Asr4all)
        if (androidNativeReady) add(VoiceInputEngine.AndroidNative)
    }

    /** One plain-English line explaining why Android native is missing, or null when it is offered. */
    fun inputReason(androidNativeReady: Boolean, isRelease: Boolean = false): String? =
        if (androidNativeReady) {
            null
        } else if (isRelease) {
            "Android speech recognition isn't ready on this phone, so Automatic will use the offline asr4all model once it has downloaded."
        } else {
            "Android's own listener can't be used on this phone, so Automatic is the one that works here."
        }

    /**
     * Release offers Piper (Cori), Kokoro and Android TTS, plus Inflect Micro on eligible devices. Debug builds also keep
     * Inflect Micro everywhere, as Settings does. Kokoro is chosen here but only takes effect after its speed check (#2173).
     */
    fun outputOptions(hasSystemTts: Boolean, isRelease: Boolean, inflectEligible: Boolean): List<VoiceOutputEngine> {
        val offered = VoiceOutputEngine.entriesForBuild(isRelease, inflectEligible)
        return listOf(
            VoiceOutputEngine.SherpaExperimental,
            VoiceOutputEngine.InflectMicroExperimental,
            VoiceOutputEngine.KokoroExperimental,
            VoiceOutputEngine.AndroidTts,
        ).filter { it in offered && (it != VoiceOutputEngine.AndroidTts || hasSystemTts) }
    }

    /** [candidate] when the wizard offers it, otherwise null. */
    fun <T> offered(candidate: T, options: List<T>): T? = candidate.takeIf { it in options }

    /** The engine in use when setup does not list it: it gets its own "Current: …" card. Null when it is listed. */
    fun <T> extraCurrent(options: List<T>, current: T?): T? = current?.takeIf { it !in options }

    /** The tier default speech output, or the first offered option when the default is not offered. */
    fun defaultOutput(tier: HardwareTier, options: List<VoiceOutputEngine>): VoiceOutputEngine =
        offered(VoiceOutputEngine.defaultForTier(tier), options) ?: options.first()
}
