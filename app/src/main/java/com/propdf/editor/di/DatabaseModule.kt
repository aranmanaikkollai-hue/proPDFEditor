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
            // Was "propdf_database" — identical to the SQLite filename used by
            // core.RecentFilesDatabase (see core/di/DatabaseModule.kt). Two
            // unrelated Room @Database classes with different entity sets
            // were opening the same physical file, which fails Room's schema
            // validation and crashes whichever one opens second. Renamed to
            // a name unique to this single-entity (ConversionTask) database.
            "conversion_tasks_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideConversionTaskDao(database: AppDatabase): ConversionTaskDao {
        return database.conversionTaskDao()
    }

    // ProPDFDatabase itself is already provided as a @Singleton by
    // core/di/DatabaseModule.kt — a second @Provides here for the same type
    // would be a duplicate Hilt binding. These functions just expose the
    // additional DAOs that database's own entity list didn't have accessors
    // for until now (see ProPDFDatabase.kt).

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
