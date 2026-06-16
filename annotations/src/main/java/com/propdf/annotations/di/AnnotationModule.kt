// annotations/src/main/java/com/propdf/annotations/di/AnnotationModule.kt
package com.propdf.annotations.di

import android.content.Context
import com.propdf.annotations.history.HistoryManager
import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.persistence.AnnotationDatabase
import com.propdf.annotations.persistence.AnnotationRepository
import com.propdf.annotations.transform.AnnotationTransformer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(ViewModelComponent::class)
object AnnotationModule {

    @Provides
    fun provideLayerManager(): LayerManager = LayerManager()

    @Provides
    fun provideHistoryManager(layerManager: LayerManager): HistoryManager {
        return HistoryManager(layerManager)
    }

    @Provides
    fun provideAnnotationTransformer(): AnnotationTransformer = AnnotationTransformer()

    @Provides
    fun provideAnnotationRepository(@ApplicationContext context: Context): AnnotationRepository {
        val dao = AnnotationDatabase.getInstance(context).annotationDao()
        return AnnotationRepository(dao)
    }
}
