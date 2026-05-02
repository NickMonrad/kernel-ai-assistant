package com.kernel.ai.core.voice.di

import com.kernel.ai.core.voice.AndroidTextToSpeechController
import com.kernel.ai.core.voice.SelectableVoiceInputController
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
    abstract fun bindVoiceOutputController(
        impl: AndroidTextToSpeechController,
    ): VoiceOutputController
}
