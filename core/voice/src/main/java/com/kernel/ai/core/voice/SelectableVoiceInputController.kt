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
    private val whisperVoiceInputController: WhisperVoiceInputController,
    private val parakeetVoiceInputController: ParakeetVoiceInputController,
) : VoiceInputController {
    private val activeController = MutableStateFlow<VoiceInputController>(voskOfflineVoiceInputController)

    override val events: Flow<VoiceInputEvent> = merge(
        voskOfflineVoiceInputController.events.map { event -> voskOfflineVoiceInputController to event },
        nativeAndroidVoiceInputController.events.map { event -> nativeAndroidVoiceInputController to event },
        sherpaOnnxVoiceInputController.events.map { event -> sherpaOnnxVoiceInputController to event },
        whisperVoiceInputController.events.map { event -> whisperVoiceInputController to event },
        parakeetVoiceInputController.events.map { event -> parakeetVoiceInputController to event },
    ).filter { (controller, _) ->
        controller === activeController.value
    }.map { (_, event) ->
        event
    }
    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        val controller = when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Vosk -> voskOfflineVoiceInputController
            VoiceInputEngine.AndroidNative -> nativeAndroidVoiceInputController
            VoiceInputEngine.SherpaOnnx -> sherpaOnnxVoiceInputController
            VoiceInputEngine.WhisperCpp -> whisperVoiceInputController
            VoiceInputEngine.ParakeetCtc -> parakeetVoiceInputController
        }
        activeController.value = controller
        stopInactiveControllers(controller)
        return controller.startListening(mode)
    }
    override fun stopListening() {
        stopAllControllers()
    }
    private fun stopInactiveControllers(active: VoiceInputController) {
        if (active !== voskOfflineVoiceInputController) voskOfflineVoiceInputController.stopListening()
        if (active !== nativeAndroidVoiceInputController) nativeAndroidVoiceInputController.stopListening()
        if (active !== sherpaOnnxVoiceInputController) sherpaOnnxVoiceInputController.stopListening()
        if (active !== whisperVoiceInputController) whisperVoiceInputController.stopListening()
        if (active !== parakeetVoiceInputController) parakeetVoiceInputController.stopListening()
    }
    private fun stopAllControllers() {
        voskOfflineVoiceInputController.stopListening()
        nativeAndroidVoiceInputController.stopListening()
        sherpaOnnxVoiceInputController.stopListening()
        whisperVoiceInputController.stopListening()
        parakeetVoiceInputController.stopListening()
    }
}