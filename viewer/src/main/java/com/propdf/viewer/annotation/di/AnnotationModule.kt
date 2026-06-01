package com.propdf.viewer.annotation.di

import com.propdf.viewer.annotation.export.AnnotationFlattener
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.coords.PdfCoordinateSpace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import android.content.Context

@Module
@InstallIn(ActivityComponent::class)
object AnnotationModule {

    @Provides
    @ActivityScoped
    fun provideAnnotationManager(): AnnotationManager = AnnotationManager()

    @Provides
    @ActivityScoped
    fun provideAnnotationFlattener(
        @ActivityContext context: Context
    ): AnnotationFlattener = AnnotationFlattener(context)

    @Provides
    @ActivityScoped
    fun providePdfCoordinateSpace(): PdfCoordinateSpace = PdfCoordinateSpace()
}
