package com.propdf.editor.di

import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.editor.data.repository.PdfOperationsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfOperationsModule {

    @Binds
    @Singleton
    abstract fun bindPdfOperationsRepository(
        impl: PdfOperationsRepositoryImpl
    ): PdfOperationsRepository
}
