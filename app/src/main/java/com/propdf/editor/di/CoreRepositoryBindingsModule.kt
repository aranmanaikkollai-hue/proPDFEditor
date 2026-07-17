package com.propdf.editor.di

import com.propdf.core.data.local.RecentFilesRepositoryImpl
import com.propdf.core.domain.repository.CollectionRepository
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.repository.TagRepository
import com.propdf.editor.data.repository.CollectionRepositoryImpl
import com.propdf.editor.data.repository.DocumentRepositoryImpl
import com.propdf.editor.data.repository.EditorDocumentRepositoryImpl
import com.propdf.editor.data.repository.TagRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreRepositoryBindingsModule {

    @Binds
    @Singleton
    abstract fun bindRecentFilesRepository(
        impl: RecentFilesRepositoryImpl
    ): RecentFilesRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(
        impl: DocumentRepositoryImpl
    ): com.propdf.core.domain.repository.DocumentRepository

    @Binds
    @Singleton
    abstract fun bindEditorDocumentRepository(
        impl: EditorDocumentRepositoryImpl
    ): com.propdf.editor.domain.repository.DocumentRepository

    @Binds
    @Singleton
    abstract fun bindCollectionRepository(
        impl: CollectionRepositoryImpl
    ): CollectionRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(
        impl: TagRepositoryImpl
    ): TagRepository

    // PdfViewerRepository is bound in com.propdf.viewer.di.ViewerBindsModule
    // (to PdfViewerRepositoryImpl). A duplicate @Binds for it used to live
    // here pointing at StubPdfViewerRepository, which caused a
    // [Dagger/DuplicateBindings] error once the real :viewer implementation
    // was wired up.
}
