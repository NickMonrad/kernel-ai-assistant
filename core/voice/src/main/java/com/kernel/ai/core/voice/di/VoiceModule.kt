package com.kernel.ai.core.voice.di

import com.kernel.ai.core.voice.FallbackVoiceOutputController
import com.kernel.ai.core.voice.SelectableVoiceInputController
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.OnnxWakeWordDetector
import com.kernel.ai.core.voice.ToneStartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.WakeWordDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    abstract fun bindVoiceInputController(
        impl: SelectableVoiceInputController,
    ): VoiceInputController

    @Binds
    @Singleton
    abstract fun bindStartListeningCuePlayer(
        impl: ToneStartListeningCuePlayer,
    ): StartListeningCuePlayer

    /**
     * Binds [FallbackVoiceOutputController] as the active [VoiceOutputController].
     *
     * [FallbackVoiceOutputController] respects the selected voice output engine preference:
     * Android TTS routes directly to [com.kernel.ai.core.voice.AndroidTextToSpeechController],
     * while Sherpa Piper tries the local experimental backend and transparently falls back to
     * Android TTS if the runtime assets are missing or fail.
     *
     * To restore the Android-TTS-only binding, change `impl` back to
     * `AndroidTextToSpeechController`.
     */
    @Binds
    @Singleton
    abstract fun bindVoiceOutputController(
        impl: FallbackVoiceOutputController,
    ): VoiceOutputController

    /**
     * Binds [OnnxWakeWordDetector] as the [WakeWordDetector].
     *
     * [OnnxWakeWordDetector.isAvailable] returns false and [WakeWordDetector.start] is a
     * no-op when any model file is absent. The feature activates automatically when all three
     * model files are present in assets/models/wakeword/ (#984, #985):
     *   - melspectrogram.onnx  (download from openWakeWord releases)
     *   - embedding_model.onnx (download from openWakeWord releases)
     *   - hey_jandal.onnx      (output of #984 training pipeline)
     */
    @Binds
    @Singleton
    abstract fun bindWakeWordDetector(
        impl: OnnxWakeWordDetector,
    ): WakeWordDetector
}
