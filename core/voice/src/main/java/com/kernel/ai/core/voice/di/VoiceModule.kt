package com.kernel.ai.core.voice.di

import com.kernel.ai.core.voice.FallbackVoiceOutputController
import com.kernel.ai.core.voice.SelectableVoiceInputController
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.ToneStartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
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
}
