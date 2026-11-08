package com.kernel.ai.auth

import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds [HuggingFaceAuthManager] (app-module implementation) to the
 * [HuggingFaceAuthRepository] interface defined in :core:inference.
 *
 * Because [HuggingFaceAuthManager] is annotated with `@Singleton @Inject constructor`,
 * Hilt can construct it automatically — this module only needs to declare the binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindHuggingFaceAuthRepository(
        impl: HuggingFaceAuthManager,
    ): HuggingFaceAuthRepository
}
