package com.propdf.editor.di

import android.content.Context
import androidx.room.Room
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.local.dao.*
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
    fun provideDatabase(@ApplicationContext context: Context): ProPDFDatabase {
        return Room.databaseBuilder(
            context,
            ProPDFDatabase::class.java,
            "propdf_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePdfDocumentDao(database: ProPDFDatabase): PdfDocumentDao = database.pdfDocumentDao()

    @Provides
    fun provideDocumentTagDao(database: ProPDFDatabase): DocumentTagDao = database.documentTagDao()

    @Provides
    fun provideDocumentCollectionDao(database: ProPDFDatabase): DocumentCollectionDao = database.documentCollectionDao()

    @Provides
    fun provideRecentActivityDao(database: ProPDFDatabase): RecentActivityDao = database.recentActivityDao()

    @Provides
    fun provideFormFieldDao(database: ProPDFDatabase): FormFieldDao = database.formFieldDao()

    @Provides
    fun provideFormDataDao(database: ProPDFDatabase): FormDataDao = database.formDataDao()
}
