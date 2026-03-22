package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.editor.data.local.*
import com.propdf.editor.data.repository.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(
            context,
            ProPDFDatabase::class.java,
            ProPDFDatabase.DATABASE_NAME
        ).fallbackToDestructiveMigration().build()
    }

    @Provides @Singleton
    fun provideAnnotationDao(db: ProPDFDatabase) = db.annotationDao()

    @Provides @Singleton
    fun provideBookmarkDao(db: ProPDFDatabase) = db.bookmarkDao()

    @Provides @Singleton
    fun provideRecentFileDao(db: ProPDFDatabase) = db.recentFileDao()

    @Provides @Singleton
    fun providePdfOperationsManager(@ApplicationContext context: Context) =
        PdfOperationsManager(context)

    @Provides @Singleton
    fun provideOcrManager(@ApplicationContext context: Context) =
        OcrManager(context)

    @Provides @Singleton
    fun provideScannerProcessor() = ScannerProcessor()
}
