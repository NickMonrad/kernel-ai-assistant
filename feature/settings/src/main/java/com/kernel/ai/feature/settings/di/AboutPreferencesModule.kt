package com.kernel.ai.feature.settings.di

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

private val Context.aboutPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "about_prefs",
)

@Module
@InstallIn(SingletonComponent::class)
object AboutPreferencesModule {

    @Provides
    @Singleton
    @Named("about")
    fun provideAboutDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.aboutPreferencesDataStore
}
