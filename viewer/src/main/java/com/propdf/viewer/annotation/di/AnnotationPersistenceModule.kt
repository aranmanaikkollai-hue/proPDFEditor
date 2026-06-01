package com.propdf.viewer.annotation.di

import android.content.Context
import androidx.room.Room
import com.propdf.viewer.annotation.persistence.AnnotationDao
import com.propdf.viewer.annotation.persistence.AnnotationDatabase
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnnotationPersistenceModule {

    @Provides
    @Singleton
    fun provideAnnotationDatabase(
        @ApplicationContext context: Context
    ): AnnotationDatabase = Room.databaseBuilder(
        context,
        AnnotationDatabase::class.java,
        "viewer_annotations.db"
    ).build()

    @Provides
    @Singleton
    fun provideAnnotationDao(database: AnnotationDatabase): AnnotationDao = database.annotationDao()

    @Provides
    @Singleton
    fun provideAnnotationPersistenceManager(
        @ApplicationContext context: Context,
        annotationDao: AnnotationDao
    ): AnnotationPersistenceManager = AnnotationPersistenceManager(context, annotationDao)
}
