package com.propdf.editor.di

import android.content.Context
import com.propdf.editor.data.storage.StorageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideStorageManager(
        @ApplicationContext context: Context
    ): StorageManager = StorageManager(context)
}
