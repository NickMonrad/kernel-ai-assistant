package com.kernel.ai.core.voice

import android.content.Context
import android.speech.SpeechRecognizer

/**
 * What the device offers for speech recognition, read from the platform. Pure data, so callers
 * and tests can build fixtures directly. Read with [readVoiceCapabilities].
 *
 * @property platformRecognizerPresent a speech recognition service is registered. The app
 *   exports its own `JandalRecognitionService`, so this is normally true even on ROMs without
 *   Google services (GrapheneOS); [onDeviceRecognitionAvailable] is the real support signal.
 * @property onDeviceRecognitionAvailable an on-device recognizer exists. It may still lack
 *   the language pack, so check the locale separately.
 */
data class VoiceCapabilities(
    val platformRecognizerPresent: Boolean,
    val onDeviceRecognitionAvailable: Boolean,
)

/** Why Android native speech may not work for the user's current setup. */
enum class AndroidNativeWarning {
    /** No speech recognition service is registered. Nothing can run. */
    NoRecognitionService,

    /** No on-device recognizer. Offline capture cannot work. */
    NoOnDeviceRecognizer,

    /** On-device recognizer exists but the device language pack is missing. */
    LanguagePackMissing,

    /** Online recognition is off, so a silent on-device recognizer has no fallback. */
    OnlineOff,
}

/**
 * The warning to show when Android native is the selected engine, or null when it is safe.
 *
 * Precedence: an earlier missing capability hides the later ones. [localeReady] is the
 * language-pack check, and [onlineAllowed] is the "Allow online speech recognition" setting.
 */
fun androidNativeWarning(
    capabilities: VoiceCapabilities,
    localeReady: Boolean,
    onlineAllowed: Boolean,
): AndroidNativeWarning? = when {
    !capabilities.platformRecognizerPresent -> AndroidNativeWarning.NoRecognitionService
    !capabilities.onDeviceRecognitionAvailable -> AndroidNativeWarning.NoOnDeviceRecognizer
    !localeReady -> AndroidNativeWarning.LanguagePackMissing
    !onlineAllowed -> AndroidNativeWarning.OnlineOff
    else -> null
}

/**
 * Reads [VoiceCapabilities] from the running device. Cheap enough to call when a screen
 * opens; it does not start a recognizer or touch the network.
 */
fun readVoiceCapabilities(context: Context): VoiceCapabilities = VoiceCapabilities(
    platformRecognizerPresent = SpeechRecognizer.isRecognitionAvailable(context),
    onDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
)
