package com.kernel.ai.core.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer

private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
private const val TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE"

/**
 * What the device offers for voice, read from the platform. Pure data, so callers and tests
 * can build fixtures directly. Read with [readVoiceCapabilities].
 *
 * @property platformRecognizerPresent a speech recognition service is registered.
 * @property onDeviceRecognitionAvailable an on-device recognizer exists. It may still lack
 *   the language pack, so check the locale separately.
 * @property googleServicesPresent Google Play services is installed. False on GrapheneOS and
 *   similar ROMs. Informational only: voice features must not depend on it.
 * @property systemTtsEnginePresent at least one system TTS engine service resolves.
 */
data class VoiceCapabilities(
    val platformRecognizerPresent: Boolean,
    val onDeviceRecognitionAvailable: Boolean,
    val googleServicesPresent: Boolean,
    val systemTtsEnginePresent: Boolean,
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
 *
 * Package visibility for Google Play services is declared in the app manifest `<queries>`.
 */
fun readVoiceCapabilities(context: Context): VoiceCapabilities {
    val packageManager = context.packageManager
    return VoiceCapabilities(
        platformRecognizerPresent = SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        googleServicesPresent = runCatching {
            packageManager.getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        }.isSuccess,
        systemTtsEnginePresent = packageManager.queryIntentServices(
            Intent(TTS_SERVICE_ACTION),
            PackageManager.ResolveInfoFlags.of(0),
        ).isNotEmpty(),
    )
}
