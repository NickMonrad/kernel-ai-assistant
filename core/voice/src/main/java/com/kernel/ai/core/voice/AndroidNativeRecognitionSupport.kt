package com.kernel.ai.core.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "NativeVoiceInput"

data class AndroidNativeRecognitionAvailability(
    val isRecognitionAvailable: Boolean,
    val isOnDeviceRecognitionAvailable: Boolean,
    val languageTag: String,
    val languageDisplayName: String,
    val localeStatus: AndroidNativeRecognitionLocaleStatus = AndroidNativeRecognitionLocaleStatus.Ready,
) {
    val blockingReason: String?
        get() = when {
            !isRecognitionAvailable -> "Android speech recognition is not available on this device."
            !isOnDeviceRecognitionAvailable -> "On-device Android speech recognition is unavailable for the current setup. Install the required language pack or keep using Vosk for guaranteed local voice input."
            localeStatus == AndroidNativeRecognitionLocaleStatus.NotSupported ->
                "$languageDisplayName is not supported by Android native speech recognition on this device."
            localeStatus == AndroidNativeRecognitionLocaleStatus.Unavailable ->
                "$languageDisplayName is supported, but its Android native speech recognition language pack is not available on this device yet."
            else -> null
        }

    val warningMessage: String?
        get() = when {
            blockingReason != null -> blockingReason
            localeStatus == AndroidNativeRecognitionLocaleStatus.Unknown ->
                "Android native speech recognition could not verify on-device support for $languageDisplayName on this device. It may fail unless that language is supported and installed locally."
            else -> null
        }

    val languageSummary: String
        get() = "$languageDisplayName ($languageTag)"
}

enum class AndroidNativeRecognitionLocaleStatus {
    Ready,
    NotSupported,
    Unavailable,
    Unknown,
}

@Singleton
class AndroidNativeRecognitionSupport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun getAvailability(): AndroidNativeRecognitionAvailability {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val isOnDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val languageTag = locale.toLanguageTag()
        val languageDisplayName = locale.getDisplayName(locale).replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(locale) else char.toString()
        }

        val localeStatus = if (isRecognitionAvailable && isOnDeviceRecognitionAvailable) {
            checkLocaleSupport(languageTag)
        } else {
            AndroidNativeRecognitionLocaleStatus.Unknown
        }

        Log.i(
            TAG,
            "Android native availability: language=$languageTag displayName=$languageDisplayName " +
                "recognitionAvailable=$isRecognitionAvailable " +
                "onDeviceAvailable=$isOnDeviceRecognitionAvailable localeStatus=$localeStatus",
        )

        return AndroidNativeRecognitionAvailability(
            isRecognitionAvailable = isRecognitionAvailable,
            isOnDeviceRecognitionAvailable = isOnDeviceRecognitionAvailable,
            languageTag = locale.toLanguageTag(),
            languageDisplayName = languageDisplayName,
            localeStatus = localeStatus,
        )
    }

    fun createOnDeviceSpeechRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

    fun createPlatformSpeechRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context)

    private suspend fun checkLocaleSupport(languageTag: String): AndroidNativeRecognitionLocaleStatus =
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { continuation ->
                    val recognizer = createOnDeviceSpeechRecognizer()
                    val supportIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    }

                    fun complete(status: AndroidNativeRecognitionLocaleStatus) {
                        if (continuation.isActive) {
                            recognizer.destroy()
                            continuation.resume(status)
                        } else {
                            recognizer.destroy()
                        }
                    }

                    continuation.invokeOnCancellation {
                        recognizer.destroy()
                    }

                    recognizer.checkRecognitionSupport(
                        supportIntent,
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                Log.i(
                                    TAG,
                                    "Recognition support result for $languageTag: " +
                                        "installed=${recognitionSupport.getInstalledOnDeviceLanguages()} " +
                                        "supported=${recognitionSupport.getSupportedOnDeviceLanguages()} " +
                                        "pending=${recognitionSupport.getPendingOnDeviceLanguages()} " +
                                        "online=${recognitionSupport.getOnlineLanguages()}",
                                )
                                complete(resolveLocaleStatus(languageTag, recognitionSupport))
                            }

                            override fun onError(error: Int) {
                                Log.w(TAG, "Recognition support check failed for $languageTag with error=$error")
                                complete(
                                    when (error) {
                                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                            AndroidNativeRecognitionLocaleStatus.NotSupported
                                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                            AndroidNativeRecognitionLocaleStatus.Unavailable
                                        else -> AndroidNativeRecognitionLocaleStatus.Unknown
                                    },
                                )
                            }
                        },
                    )
                }
            } ?: run {
                Log.w(TAG, "Recognition support check timed out for $languageTag")
                AndroidNativeRecognitionLocaleStatus.Unknown
            }
        }
}

internal fun resolveLocaleStatus(
    languageTag: String,
    recognitionSupport: RecognitionSupport,
): AndroidNativeRecognitionLocaleStatus =
    resolveLocaleStatus(
        languageTag = languageTag,
        installedLanguages = recognitionSupport.getInstalledOnDeviceLanguages(),
        supportedLanguages = recognitionSupport.getSupportedOnDeviceLanguages(),
        pendingLanguages = recognitionSupport.getPendingOnDeviceLanguages(),
        onlineLanguages = recognitionSupport.getOnlineLanguages(),
    )

internal fun resolveLocaleStatus(
    languageTag: String,
    installedLanguages: List<String>,
    supportedLanguages: List<String>,
    pendingLanguages: List<String>,
    onlineLanguages: List<String>,
): AndroidNativeRecognitionLocaleStatus {
    return when {
        matchesRequestedLanguage(languageTag, installedLanguages) -> AndroidNativeRecognitionLocaleStatus.Ready
        matchesRequestedLanguage(languageTag, supportedLanguages) -> AndroidNativeRecognitionLocaleStatus.Unavailable
        matchesRequestedLanguage(languageTag, pendingLanguages) -> AndroidNativeRecognitionLocaleStatus.Unavailable
        matchesRequestedLanguage(languageTag, onlineLanguages) -> AndroidNativeRecognitionLocaleStatus.NotSupported
        else -> AndroidNativeRecognitionLocaleStatus.Unknown
    }
}

private fun matchesRequestedLanguage(
    requestedLanguageTag: String,
    availableLanguageTags: List<String>,
): Boolean {
    val requestedLocale = Locale.forLanguageTag(requestedLanguageTag)
    val requestedNormalizedTag = requestedLocale.toLanguageTag()
    val requestedBaseLanguage = requestedLocale.language

    return availableLanguageTags.any { availableLanguageTag ->
        val availableLocale = Locale.forLanguageTag(availableLanguageTag)
        val availableNormalizedTag = availableLocale.toLanguageTag()
        val availableBaseLanguage = availableLocale.language

        availableNormalizedTag == requestedNormalizedTag ||
            (
                requestedBaseLanguage.isNotBlank() &&
                    availableBaseLanguage.isNotBlank() &&
                    requestedBaseLanguage == availableBaseLanguage
            )
    }
}
