package com.kernel.ai.core.inference.di

import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.LiteRtInferenceEngine
import com.kernel.ai.core.inference.MediaPipeEmbeddingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: LiteRtInferenceEngine): InferenceEngine

    @Binds
    @Singleton
    abstract fun bindEmbeddingEngine(impl: MediaPipeEmbeddingEngine): EmbeddingEngine
}
