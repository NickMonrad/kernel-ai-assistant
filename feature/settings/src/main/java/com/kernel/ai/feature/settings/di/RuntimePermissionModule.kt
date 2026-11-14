package com.kernel.ai.feature.settings.di

import android.content.Context
import com.kernel.ai.core.permissions.PermissionRequestStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object RuntimePermissionModule {

    @Provides
    fun providePermissionRequestStore(@ApplicationContext context: Context): PermissionRequestStore =
        PermissionRequestStore(context)
}
