package com.kernel.ai.core.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "NativeVoiceInput"
/** Age after which the prewarm checks a not-Ready on-device language answer again (#1973). */
internal const val UNCONFIRMED_LOCALE_RECHECK_MS = 10 * 60 * 1000L
private const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"
private const val NO_EXTERNAL_RECOGNIZER_MESSAGE =
    "Android speech recognition is unavailable because no external recognition service is configured on this device."

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
            !isOnDeviceRecognitionAvailable -> "On-device Android speech recognition is unavailable for the current setup. Install the required language pack, or use Automatic for guaranteed local voice input."
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

private data class ParsedRecognitionComponent(
    val packageName: String,
    val className: String,
) {
    fun flatten(): String = "$packageName/$className"
}

private data class LocaleSupportCache(
    val languageTag: String,
    val localeStatus: AndroidNativeRecognitionLocaleStatus,
)
internal fun selectPlatformRecognitionService(
    configuredService: String?,
    availableServices: List<String>,
    selfPackageName: String,
): String? {
    fun parse(componentName: String): ParsedRecognitionComponent? {
        val slash = componentName.indexOf('/')
        if (slash <= 0 || slash == componentName.lastIndex) return null
        val packageName = componentName.substring(0, slash)
        val rawClassName = componentName.substring(slash + 1)
        if (packageName.isBlank() || rawClassName.isBlank()) return null
        val className = when {
            rawClassName.startsWith('.') -> "$packageName$rawClassName"
            '.' in rawClassName -> rawClassName
            else -> "$packageName.$rawClassName"
        }
        return ParsedRecognitionComponent(packageName = packageName, className = className)
    }

    val availableComponents = availableServices.mapNotNull { raw ->
        parse(raw)?.let { component -> raw to component }
    }
    val externalComponents = availableComponents.filter { (_, component) ->
        component.packageName != selfPackageName
    }
    val configuredExternalComponent = configuredService
        ?.let(::parse)
        ?.takeIf { it.packageName != selfPackageName }

    return when {
        externalComponents.isEmpty() -> null
        configuredExternalComponent == null -> externalComponents.first().second.flatten()
        externalComponents.any { (_, component) -> component == configuredExternalComponent } ->
            configuredExternalComponent.flatten()
        else -> externalComponents.first().second.flatten()
    }
}


@Singleton
class AndroidNativeRecognitionSupport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var localeSupportCache: LocaleSupportCache? = null

    /** elapsedRealtime when the last live check finished; 0 before the first one. */
    @Volatile
    private var lastLiveCheckEndMs = 0L

    // Independent scope so RecognitionSupportCallback closures don't capture
    // the calling Activity's coroutine continuation chain.
    private val localeCheckScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * One live check at a time (#1973): a start that arrives while the chat's prewarm or Settings is
     * checking awaits that check. A second check would add its own throwaway recognizer, whose teardown
     * could land after the session starts (#1008), and would wait out its own timeout.
     */
    private val liveCheck = SingleFlight<AndroidNativeRecognitionAvailability>(localeCheckScope)

    /** Runs the live on-device language check, or joins the one already running, and caches its answer. */
    suspend fun getAvailability(): AndroidNativeRecognitionAvailability = liveCheck.run { runLiveCheck() }

    /** Waits for a live check that is already running, without starting one. */
    suspend fun awaitInFlightLiveCheck() = liveCheck.awaitInFlight()

    private suspend fun runLiveCheck(): AndroidNativeRecognitionAvailability {
        val baseAvailability = currentRecognitionAvailability()
        val localeStatus = if (
            baseAvailability.isRecognitionAvailable &&
            baseAvailability.isOnDeviceRecognitionAvailable
        ) {
            checkLocaleSupport(baseAvailability.languageTag)
        } else {
            AndroidNativeRecognitionLocaleStatus.Unknown
        }
        val availability = baseAvailability.copy(localeStatus = localeStatus)
        localeSupportCache = LocaleSupportCache(
            languageTag = availability.languageTag,
            localeStatus = availability.localeStatus,
        )
        lastLiveCheckEndMs = SystemClock.elapsedRealtime()

        Log.i(
            TAG,
            "Android native availability: language=${availability.languageTag} " +
                "displayName=${availability.languageDisplayName} " +
                "recognitionAvailable=${availability.isRecognitionAvailable} " +
                "onDeviceAvailable=${availability.isOnDeviceRecognitionAvailable} " +
                "localeStatus=${availability.localeStatus}",
        )

        return availability
    }

    fun getCaptureAvailability(): AndroidNativeRecognitionAvailability {
        val baseAvailability = currentRecognitionAvailability()
        val cachedLocaleStatus = localeSupportCache
            ?.takeIf { it.languageTag == baseAvailability.languageTag }
            ?.localeStatus
            ?: AndroidNativeRecognitionLocaleStatus.Unknown
        val availability = baseAvailability.copy(localeStatus = cachedLocaleStatus)
        Log.i(
            TAG,
            "Android native capture availability: language=${availability.languageTag} " +
                "displayName=${availability.languageDisplayName} " +
                "recognitionAvailable=${availability.isRecognitionAvailable} " +
                "onDeviceAvailable=${availability.isOnDeviceRecognitionAvailable} " +
                "localeStatus=${availability.localeStatus}",
        )
        return availability
    }

    /** True when a live check has already answered for [languageTag], whatever its answer (#1973). */
    fun hasLocaleCheckResult(languageTag: String): Boolean =
        localeSupportCache?.languageTag == languageTag

    /**
     * Whether the prewarm should run the live check for [languageTag] (#1973): there is no answer yet,
     * or the answer was not Ready and is older than [UNCONFIRMED_LOCALE_RECHECK_MS]. Starts with online
     * recognition on reuse any answer, so this keeps a one-off timeout from deciding the backend for the
     * rest of the process.
     */
    fun localeCheckDue(languageTag: String): Boolean {
        val cache = localeSupportCache
        return isLocaleCheckDue(
            cachedLanguageTag = cache?.languageTag,
            cachedStatus = cache?.localeStatus,
            languageTag = languageTag,
            lastCheckEndMs = lastLiveCheckEndMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * True when a speech service other than Jandal's own is installed, so [createPlatformSpeechRecognizer]
     * can succeed. [SpeechRecognizer.isRecognitionAvailable] is not enough: the app's own exported
     * `JandalRecognitionService` makes it true on every phone.
     */
    fun hasExternalPlatformRecognizer(): Boolean {
        val (configured, available) = platformRecognitionServices()
        return selectPlatformRecognitionService(configured, available, context.packageName) != null
    }

    /**
     * How much of [settleMs] is still left since the last live check finished: its throwaway
     * recognizer is torn down asynchronously, and a session started inside that window can lose the
     * shared on-device service (#1008). 0 when no check ran or the window has passed.
     */
    fun liveCheckSettleRemainingMs(settleMs: Long): Long =
        settleRemainingMs(lastLiveCheckEndMs, SystemClock.elapsedRealtime(), settleMs)

    private fun currentRecognitionAvailability(): AndroidNativeRecognitionAvailability {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val isOnDeviceRecognitionAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val languageTag = locale.toLanguageTag()
        val languageDisplayName = locale.getDisplayName(locale).replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(locale) else char.toString()
        }

        return createRecognitionAvailability(
            isRecognitionAvailable = isRecognitionAvailable,
            isOnDeviceRecognitionAvailable = isOnDeviceRecognitionAvailable,
            languageTag = languageTag,
            languageDisplayName = languageDisplayName,
        )
    }


    fun createOnDeviceSpeechRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    fun createPlatformSpeechRecognizer(): SpeechRecognizer {
        val (configuredService, availableServices) = platformRecognitionServices()
        val selectedService = selectPlatformRecognitionService(
            configuredService = configuredService,
            availableServices = availableServices,
            selfPackageName = context.packageName,
        )
        Log.i(
            TAG,
            "Platform recognition service selection: configured=$configuredService " +
                "available=$availableServices selected=$selectedService",
        )
        val selectedComponent = selectedService?.let(ComponentName::unflattenFromString)
        if (selectedComponent == null) {
            Log.w(
                TAG,
                "No external platform recognition service available: configured=$configuredService available=$availableServices",
            )
            throw IllegalStateException(NO_EXTERNAL_RECOGNIZER_MESSAGE)
        }
        return SpeechRecognizer.createSpeechRecognizer(context, selectedComponent)
    }

    /** The user's configured speech service setting and every installed recognition service. */
    private fun platformRecognitionServices(): Pair<String?, List<String>> {
        val configuredService = Settings.Secure.getString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE_SETTING,
        )?.takeIf { it.isNotBlank() }
        val availableServices = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.ResolveInfoFlags.of(0),
        ).mapNotNull { resolveInfo ->
            resolveInfo.serviceInfo?.let { serviceInfo ->
                ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToShortString()
            }
        }
        return configuredService to availableServices
    }

    private suspend fun checkLocaleSupport(languageTag: String): AndroidNativeRecognitionLocaleStatus {
        // Run in an independent scope so the RecognitionSupportCallback closure
        // does NOT capture the caller's continuation chain (which would hold the
        // calling Activity alive via InternalSupportCallback in native code).
        val deferred = localeCheckScope.async {
            withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine { continuation ->
                        val recognizer = createOnDeviceSpeechRecognizer()
                        val supportIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                        }

                        fun cleanupRecognizer() {
                            runCatching { recognizer.cancel() }
                            runCatching { recognizer.destroy() }
                        }

                        fun complete(status: AndroidNativeRecognitionLocaleStatus) {
                            if (continuation.isActive) {
                                cleanupRecognizer()
                                continuation.resume(status)
                            } else {
                                cleanupRecognizer()
                            }
                        }

                        continuation.invokeOnCancellation {
                            cleanupRecognizer()
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
        return try {
            deferred.await()
        } catch (e: Exception) {
            Log.w(TAG, "checkLocaleSupport cancelled for $languageTag")
            AndroidNativeRecognitionLocaleStatus.Unknown
        }
    }
}


/**
 * Whether the live check is due for [languageTag] at [nowMs] (elapsedRealtime): never answered for
 * this language, or answered not-Ready at least [UNCONFIRMED_LOCALE_RECHECK_MS] ago. Ready is final.
 */
internal fun isLocaleCheckDue(
    cachedLanguageTag: String?,
    cachedStatus: AndroidNativeRecognitionLocaleStatus?,
    languageTag: String,
    lastCheckEndMs: Long,
    nowMs: Long,
): Boolean = when {
    cachedLanguageTag != languageTag -> true
    cachedStatus == AndroidNativeRecognitionLocaleStatus.Ready -> false
    else -> nowMs - lastCheckEndMs >= UNCONFIRMED_LOCALE_RECHECK_MS
}

/** What is left of [settleMs] at [nowMs] after a check that ended at [lastCheckEndMs]; 0 when none ran. */
internal fun settleRemainingMs(lastCheckEndMs: Long, nowMs: Long, settleMs: Long): Long =
    if (lastCheckEndMs == 0L) 0L else (settleMs - (nowMs - lastCheckEndMs)).coerceIn(0L, settleMs)

internal fun createRecognitionAvailability(
    isRecognitionAvailable: Boolean,
    isOnDeviceRecognitionAvailable: Boolean,
    languageTag: String,
    languageDisplayName: String,
    localeStatus: AndroidNativeRecognitionLocaleStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
): AndroidNativeRecognitionAvailability = AndroidNativeRecognitionAvailability(
    isRecognitionAvailable = isRecognitionAvailable,
    isOnDeviceRecognitionAvailable = isOnDeviceRecognitionAvailable,
    languageTag = languageTag,
    languageDisplayName = languageDisplayName,
    localeStatus = localeStatus,
)


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
