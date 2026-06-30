package com.propdf.annotations.di

import android.content.Context
import com.propdf.annotations.export.PdfAnnotationExporter
import com.propdf.annotations.history.HistoryManager
import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.persistence.AnnotationDatabase
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.annotations.transform.AnnotationTransformer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module for annotation system.
 * Provides singleton instances of all core components.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnnotationModule {

    @Provides
    @Singleton
    fun provideAnnotationDatabase(
        @ApplicationContext context: Context
    ): AnnotationDatabase {
        return AnnotationDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideAnnotationDao(
        database: AnnotationDatabase
    ) = database.annotationDao()

    @Provides
    @Singleton
    fun provideAnnotationRepository(
        dao: com.propdf.annotations.persistence.AnnotationDao
    ): AnnotationRepository {
        return AnnotationRepository(dao)
    }

    @Provides
    @Singleton
    fun provideLayerManager(): LayerManager {
        return LayerManager()
    }

    @Provides
    @Singleton
    fun provideAnnotationTransformer(): AnnotationTransformer {
        return AnnotationTransformer()
    }

    @Provides
    @Singleton
    fun provideHistoryManager(
        layerManager: LayerManager
    ): HistoryManager {
        return HistoryManager(
            layerManager = layerManager,
            onPersist = {
                // Auto-save callback - actual persistence handled by ViewModel
            }
        )
    }

    @Provides
    @Singleton
    fun providePdfAnnotationExporter(
        @ApplicationContext context: Context,
        repository: AnnotationRepository
    ): PdfAnnotationExporter {
        return PdfAnnotationExporter(context, repository)
    }
}
