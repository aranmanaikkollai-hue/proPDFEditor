package com.propdf.share.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * NearbyShareManager, LanShareServer, and QrCodeGenerator all have their own
 * @Inject constructor, so Hilt provides them automatically. No @Provides methods
 * needed here (duplicates previously caused KSP "error.NonExistentClass" failures).
 */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule
