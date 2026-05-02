package com.kernel.ai.core.voice

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first

@Singleton
class SelectableVoiceInputController @Inject constructor(
    private val voiceInputPreferences: VoiceInputPreferences,
    private val voskOfflineVoiceInputController: VoskOfflineVoiceInputController,
    private val nativeAndroidVoiceInputController: NativeAndroidVoiceInputController,
) : VoiceInputController {

    override val events: Flow<VoiceInputEvent> = merge(
        voskOfflineVoiceInputController.events,
        nativeAndroidVoiceInputController.events,
    )

    @Volatile
    private var activeController: VoiceInputController = voskOfflineVoiceInputController

    override suspend fun startListening(mode: VoiceCaptureMode): VoiceInputStartResult {
        val controller = when (voiceInputPreferences.selectedEngine.first()) {
            VoiceInputEngine.Vosk -> voskOfflineVoiceInputController
            VoiceInputEngine.AndroidNative -> nativeAndroidVoiceInputController
        }
        activeController = controller
        return controller.startListening(mode)
    }

    override fun stopListening() {
        activeController.stopListening()
    }
}
