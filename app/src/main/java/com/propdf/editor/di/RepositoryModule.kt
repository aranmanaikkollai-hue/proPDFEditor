package com.propdf.editor.di

import com.propdf.core.domain.repository.*
import com.propdf.editor.data.repository.*
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
    abstract fun bindTagRepository(
        impl: TagRepositoryImpl
    ): TagRepository

    @Binds
    @Singleton
    abstract fun bindCollectionRepository(
        impl: CollectionRepositoryImpl
    ): CollectionRepository

    @Binds
    @Singleton
    abstract fun bindActivityRepository(
        impl: ActivityRepositoryImpl
    ): ActivityRepository

    // Binds the small app-level com.propdf.editor.domain.repository.DocumentRepository
    // interface (used by DocumentUseCases, DocumentManagerViewModel, and
    // DocumentScanWorker) to its adapter implementation. Referenced with its fully
    // qualified name here because the simple name "DocumentRepository" already
    // resolves unambiguously to com.propdf.core.domain.repository.DocumentRepository
    // via the wildcard import above, used by bindDocumentRepository().
    @Binds
    @Singleton
    abstract fun bindEditorDocumentRepository(
        impl: EditorDocumentRepositoryImpl
    ): com.propdf.editor.domain.repository.DocumentRepository
}
