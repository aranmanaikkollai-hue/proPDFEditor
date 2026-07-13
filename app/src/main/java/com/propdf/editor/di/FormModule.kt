package com.propdf.editor.di

import com.propdf.core.domain.repository.PdfFormRepository
import com.propdf.editor.feature.forms.engine.PdfFormEngine
import com.propdf.editor.feature.forms.repository.PdfFormRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FormModule {

    @Binds
    @Singleton
    abstract fun bindPdfFormRepository(
        impl: PdfFormRepositoryImpl
    ): PdfFormRepository

    companion object {
        @Provides
        @Singleton
        fun providePdfFormEngine(): PdfFormEngine = PdfFormEngine()
    }
}
