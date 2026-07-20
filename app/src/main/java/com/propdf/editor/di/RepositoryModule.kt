package com.propdf.editor.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * ConversionRepository has its own @Inject constructor (and depends on the converter
 * classes, which likewise self-provide via ConverterModule), so Hilt provides it
 * automatically. No @Provides method needed here (a duplicate one previously caused
 * KSP "error.NonExistentClass" failures).
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule
