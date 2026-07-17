package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.local.dao.DocumentCollectionDao
import com.propdf.core.data.local.dao.DocumentTagDao
import com.propdf.core.data.local.dao.FormDataDao
import com.propdf.core.data.local.dao.FormFieldDao
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.core.data.local.dao.RecentActivityDao
import com.propdf.editor.data.local.AppDatabase
import com.propdf.editor.data.local.ConversionTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "propdf_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideConversionTaskDao(database: AppDatabase): ConversionTaskDao {
        return database.conversionTaskDao()
    }

    @Provides
    @Singleton
    fun provideProPDFDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(
            context,
            ProPDFDatabase::class.java,
            "propdf_core_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePdfDocumentDao(database: ProPDFDatabase): PdfDocumentDao =
        database.pdfDocumentDao()

    @Provides
    fun provideDocumentTagDao(database: ProPDFDatabase): DocumentTagDao =
        database.documentTagDao()

    @Provides
    fun provideDocumentCollectionDao(database: ProPDFDatabase): DocumentCollectionDao =
        database.documentCollectionDao()

    @Provides
    fun provideRecentActivityDao(database: ProPDFDatabase): RecentActivityDao =
        database.recentActivityDao()

    @Provides
    fun provideFormFieldDao(database: ProPDFDatabase): FormFieldDao =
        database.formFieldDao()

    @Provides
    fun provideFormDataDao(database: ProPDFDatabase): FormDataDao =
        database.formDataDao()
}
