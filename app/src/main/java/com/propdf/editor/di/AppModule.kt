package com.propdf.editor.di

import android.content.Context
import com.propdf.core.saf.SafEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSafEngine(@ApplicationContext context: Context): SafEngine {
        return SafEngine(context)
    }
}
