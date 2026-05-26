package com.kernel.ai.core.voice.di

import com.kernel.ai.core.voice.FallbackVoiceOutputController
import com.kernel.ai.core.voice.SelectableVoiceInputController
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.TfLiteWakeWordDetector
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
     * Binds [TfLiteWakeWordDetector] as the [WakeWordDetector].
     *
     * [TfLiteWakeWordDetector.isAvailable] returns false and [WakeWordDetector.start] is a
     * no-op when the model file is absent. The feature activates automatically when
     * `app/src/main/assets/models/wakeword/hey_jandal_int8.tflite` is present (#984).
     */
    @Binds
    @Singleton
    abstract fun bindWakeWordDetector(
        impl: TfLiteWakeWordDetector,
    ): WakeWordDetector
}
