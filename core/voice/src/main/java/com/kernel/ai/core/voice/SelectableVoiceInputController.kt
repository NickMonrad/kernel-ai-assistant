package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voskOfflineVoiceInputController: VoskOfflineVoiceInputController,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
    private val sherpaOnnxVoiceInputController: SherpaOnnxVoiceInputController,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(voskOfflineVoiceInputController)

    override val events: Flow<VoiceInputEvent> = merge(
        voskOfflineVoiceInputController.events.map { event -> voskOfflineVoiceInputController to event },
        nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
        sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
    ).filter { (controller, _) ->
        controller === activeController.value
    }.map { (_, event) ->
        event
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        val controller = when (val engine = voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Vosk -> voskOfflineVoiceInputController
            VoiceInputEngine.AndroidNative -> nativeAndroidVoiceInputController
            else -> sherpaOnnxVoiceInputController // All Sherpa families + any future non-Vosk/AndroidNative.
        }
        activeController.value = controller
        stopInactiveControllers(controller)
        return controller.startListening(mode)
    }

    override fun stopListening() {
        stopAllControllers()
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

    private fun stopInactiveControllers(active: VoiceInputController) {
        if (active !== voskOfflineVoiceInputController) voskOfflineVoiceInputController.stopListening()
        if (active !== nativeAndroidVoiceInputController) nativeAndroidVoiceInputController.stopListening()
        if (active !== sherpaOnnxVoiceInputController) sherpaOnnxVoiceInputController.stopListening()
    }

    private fun stopAllControllers() {
        voskOfflineVoiceInputController.stopListening()
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
    }
}
