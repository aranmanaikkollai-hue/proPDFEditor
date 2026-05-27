package com.propdf.viewer.annotation.di

import android.content.Context
import androidx.room.Room
import com.propdf.viewer.annotation.export.AnnotationFlattener
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationDao
import com.propdf.viewer.annotation.persistence.AnnotationDatabase
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.coords.PdfCoordinateSpace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped

@Module
@InstallIn(ActivityComponent::class)
object AnnotationModule {

    @Provides
    @ActivityScoped
    fun provideAnnotationManager(): AnnotationManager = AnnotationManager()

    @Provides
    @ActivityScoped
    fun provideAnnotationDatabase(
        @ActivityContext context: Context
    ): AnnotationDatabase = Room.databaseBuilder(
        context,
        AnnotationDatabase::class.java,
        "viewer_annotations.db"
    ).fallbackToDestructiveMigration().build()

    @Provides
    @ActivityScoped
    fun provideAnnotationDao(database: AnnotationDatabase): AnnotationDao = database.annotationDao()

    @Provides
    @ActivityScoped
    fun provideAnnotationPersistenceManager(
        annotationDao: AnnotationDao
    ): AnnotationPersistenceManager = AnnotationPersistenceManager(annotationDao)

    @Provides
    @ActivityScoped
    fun provideAnnotationFlattener(
        @ActivityContext context: Context
    ): AnnotationFlattener = AnnotationFlattener(context)

    @Provides
    @ActivityScoped
    fun providePdfCoordinateSpace(): PdfCoordinateSpace = PdfCoordinateSpace()
}
