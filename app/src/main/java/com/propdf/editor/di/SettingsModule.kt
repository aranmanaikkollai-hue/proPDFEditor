package com.propdf.editor.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * SettingsDataStore has its own @Inject constructor, so Hilt provides it automatically.
 * No @Provides method needed here (a duplicate one previously caused KSP failures).
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule
