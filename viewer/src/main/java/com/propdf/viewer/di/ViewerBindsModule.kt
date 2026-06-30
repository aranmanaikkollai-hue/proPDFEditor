package com.propdf.viewer.di

import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ViewerBindsModule {

    @Binds
    @Singleton
    abstract fun bindPdfViewerRepository(
        impl: PdfViewerRepositoryImpl
    ): PdfViewerRepository
}
