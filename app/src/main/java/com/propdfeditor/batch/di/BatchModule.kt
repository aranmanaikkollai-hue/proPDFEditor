package com.propdfeditor.batch.di

import android.content.Context
import com.propdfeditor.batch.data.database.BatchDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BatchModule {

    @Provides
    @Singleton
    fun provideBatchDatabase(@ApplicationContext context: Context): BatchDatabase {
        return BatchDatabase.getInstance(context)
    }
}
