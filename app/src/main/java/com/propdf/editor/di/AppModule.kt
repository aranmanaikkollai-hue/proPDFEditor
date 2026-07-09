package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.worker.OcrJobManager
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
    fun provideDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(context, ProPDFDatabase::class.java, "propdf_database")
            .fallbackToDestructiveMigration().build()
    }

    // NOTE: DispatcherProvider is provided by :core's CoreModule.provideDispatcherProvider()
    // and RecentFilesDao by :core's DatabaseModule.provideRecentFilesDao(RecentFilesDatabase).
    // Re-declaring them here caused Dagger [Dagger/DuplicateBindings] errors.

    @Provides fun provideOcrJobDao(database: ProPDFDatabase): OcrJobDao = database.ocrJobDao()

    @Provides
    @Singleton
    fun provideOcrJobManager(@ApplicationContext context: Context, ocrJobDao: OcrJobDao): OcrJobManager {
        return OcrJobManager(context, ocrJobDao)
    }
}
