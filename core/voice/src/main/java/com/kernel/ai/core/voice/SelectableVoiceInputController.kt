package com.kernel.ai.core.voice

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

private const val TAG = "SelectableVoiceInput"

internal const val AUTO_STT_UNAVAILABLE_MESSAGE =
    "Voice input is still getting ready. The offline speech model is downloading, so try again in a minute."

/** Which recognizer [VoiceInputEngine.Auto] uses for one capture session. */
internal enum class AutoSttRoute {
    /** Android native, starting on-device. Platform fallback follows the user's setting. */
    Native,

    /** Offline Sherpa-ONNX Zipformer. */
    Zipformer,

    /** No usable recognizer yet (Zipformer still downloading, no on-device support). */
    Unavailable,
}

/**
 * Picks the recognizer for [VoiceInputEngine.Auto].
 *
 * Interactive captures prefer Android's on-device recognizer (best accuracy, still offline) and
 * fall back to Zipformer. Alert/wake commands only use Android native first when the user allows
 * the online platform service: that path starts on the platform recognizer (#1433), so without
 * that permission they prefer the offline Zipformer model.
 */
internal fun resolveAutoSttRoute(
    mode: VoiceCaptureMode,
    nativeOnDeviceReady: Boolean,
    zipformerReady: Boolean,
    allowOnlineFallback: Boolean,
): AutoSttRoute = when {
    mode == VoiceCaptureMode.AlertCommand && allowOnlineFallback && nativeOnDeviceReady -> AutoSttRoute.Native
    mode == VoiceCaptureMode.AlertCommand && zipformerReady -> AutoSttRoute.Zipformer
    nativeOnDeviceReady -> AutoSttRoute.Native
    zipformerReady -> AutoSttRoute.Zipformer
    else -> AutoSttRoute.Unavailable
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(sherpaOnnxVoiceInputController)

    override val events: Flow<VoiceInputEvent> = merge(
        nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
        sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
    ).filter { (controller, _) ->
        controller === activeController.value
    }.map { (_, event) ->
        event
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        val allowOnlineFallback = voiceInputPreferences.allowOnlineSpeechFallback.first()
        return when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Auto -> startAuto(mode, allowOnlineFallback)
            VoiceInputEngine.AndroidNative -> startNative(mode, allowOnlineFallback)
            else -> startSherpa(mode) // All Sherpa families.
        }
    }

    private suspend fun startAuto(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
        // Use the cached locale check when there is one; only run the slower live check for
        // interactive captures that have no cached answer yet.
        val availability = withContext(Dispatchers.Main.immediate) {
            val cached = androidNativeRecognitionSupport.getCaptureAvailability()
            if (
                cached.localeStatus == AndroidNativeRecognitionLocaleStatus.Unknown &&
                !shouldUseCachedCaptureAvailability(mode)
            ) {
                androidNativeRecognitionSupport.getAvailability()
            } else {
                cached
            }
        }
        val nativeOnDeviceReady = availability.blockingReason == null &&
            availability.localeStatus == AndroidNativeRecognitionLocaleStatus.Ready
        val zipformerReady = sherpaOnnxVoiceInputController.isSpecAvailable(SherpaSttModelSpec.ZIPFORMER)
        val route = resolveAutoSttRoute(
            mode = mode,
            nativeOnDeviceReady = nativeOnDeviceReady,
            zipformerReady = zipformerReady,
            allowOnlineFallback = allowOnlineFallback,
        )
        Log.i(
            TAG,
            "Auto STT route=$route mode=$mode nativeOnDeviceReady=$nativeOnDeviceReady " +
                "zipformerReady=$zipformerReady allowOnlineFallback=$allowOnlineFallback",
        )
        return when (route) {
            AutoSttRoute.Native -> startNative(mode, allowOnlineFallback)
            AutoSttRoute.Zipformer -> startSherpa(mode)
            AutoSttRoute.Unavailable -> VoiceInputStartResult.Unavailable(AUTO_STT_UNAVAILABLE_MESSAGE)
        }
    }

    private suspend fun startNative(mode: VoiceCaptureMode, allowOnlineFallback: Boolean): VoiceInputStartResult {
        activate(nativeAndroidVoiceInputController)
        return nativeAndroidVoiceInputController.startListening(mode, allowOnlineFallback)
    }

    private suspend fun startSherpa(mode: VoiceCaptureMode): VoiceInputStartResult {
        activate(sherpaOnnxVoiceInputController)
        return sherpaOnnxVoiceInputController.startListening(mode)
    }

    private fun activate(controller: VoiceInputController) {
        activeController.value = controller
        if (controller !== nativeAndroidVoiceInputController) nativeAndroidVoiceInputController.stopListening()
        if (controller !== sherpaOnnxVoiceInputController) sherpaOnnxVoiceInputController.stopListening()
    }

    override fun stopListening() {
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
    }

    /**
     * Wake-word verification always runs through the dedicated Sherpa wake
     * recognizer ([SherpaOnnxVoiceInputController.transcribeBlocking]),
     * independent of the selected interactive STT engine. The interactive
     * selection and [activeController] are deliberately untouched so the
     * currently selected engine keeps its normal capture behaviour.
     */
    override suspend fun transcribeBlocking(pcm: ShortArray): String? =
        sherpaOnnxVoiceInputController.transcribeBlocking(pcm)
}
