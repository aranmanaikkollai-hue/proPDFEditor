package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.data.local.RecentFilesDao
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.worker.OcrJobManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(context, ProPDFDatabase::class.java, "propdf_database")
            .fallbackToDestructiveMigration().build()
    }

    @Provides fun provideRecentFilesDao(database: ProPDFDatabase): RecentFilesDao = database.recentFilesDao()
    @Provides fun provideOcrJobDao(database: ProPDFDatabase): OcrJobDao = database.ocrJobDao()

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Main
        override val unconfined = Dispatchers.Unconfined
    }

    @Provides
    @Singleton
    fun provideOcrJobManager(@ApplicationContext context: Context, ocrJobDao: OcrJobDao): OcrJobManager {
        return OcrJobManager(context, ocrJobDao)
    }
}
