package com.propdf.editor.di

import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.editor.data.repository.*
import com.propdf.editor.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(
        impl: DocumentRepositoryImpl
    ): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(
        impl: FolderRepositoryImpl
    ): FolderRepository

    @Binds
    @Singleton
    abstract fun bindCloudRepository(
        impl: CloudRepositoryImpl
    ): CloudRepository

    @Binds
    @Singleton
    abstract fun bindPdfOperationsRepository(
        impl: com.propdf.editor.data.repository.PdfOperationsRepositoryImpl
    ): PdfOperationsRepository
}
