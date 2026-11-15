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

private val Context.listsPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lists_prefs",
)

@Module
@InstallIn(SingletonComponent::class)
object ListsPreferencesModule {

    @Provides
    @Singleton
    @Named("lists")
    fun provideListsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.listsPreferencesDataStore
}
