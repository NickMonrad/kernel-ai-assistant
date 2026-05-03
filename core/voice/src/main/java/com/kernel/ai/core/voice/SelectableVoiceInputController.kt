package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voskOfflineVoiceInputController: VoskOfflineVoiceInputController,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
) : VoiceInputController {

    private val activeController = MutableStateFlow<VoiceInputController>(voskOfflineVoiceInputController)

    override val events: Flow<VoiceInputEvent> = activeController.flatMapLatest { controller ->
        controller.events
    }

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        val controller = when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Vosk -> voskOfflineVoiceInputController
            VoiceInputEngine.AndroidNative -> nativeAndroidVoiceInputController
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
    }

    private fun stopAllControllers() {
        voskOfflineVoiceInputController.stopListening()
        nativeAndroidVoiceInputController.stopListening()
    }
}
