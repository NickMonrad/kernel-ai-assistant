package com.kernel.ai.feature.convert.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

private val Context.convertPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "convert_prefs",
)

@Module
@InstallIn(SingletonComponent::class)
object ConvertPreferencesModule {

    @Provides
    @Singleton
    @Named("convert")
    fun provideConvertDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.convertPreferencesDataStore
}
