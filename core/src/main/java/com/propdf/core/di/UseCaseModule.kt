package com.propdf.core.di

import com.propdf.core.domain.usecase.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
abstract class UseCaseModule {

    @Binds
    @ViewModelScoped
    abstract fun bindGetRecentFilesUseCase(impl: GetRecentFilesUseCaseImpl): GetRecentFilesUseCase

    @Binds
    @ViewModelScoped
    abstract fun bindGetDashboardDataUseCase(impl: GetDashboardDataUseCaseImpl): GetDashboardDataUseCase

    @Binds
    @ViewModelScoped
    abstract fun bindOpenDocumentUseCase(impl: OpenDocumentUseCaseImpl): OpenDocumentUseCase

    @Binds
    @ViewModelScoped
    abstract fun bindSearchFilesUseCase(impl: SearchFilesUseCaseImpl): SearchFilesUseCase
}
