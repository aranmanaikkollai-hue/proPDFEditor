package com.propdf.core.domain.usecase

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface SearchFilesUseCase {
    operator fun invoke(query: String): Flow<List<RecentFile>>
}

class SearchFilesUseCaseImpl @Inject constructor(
    private val repository: RecentFileRepository
) : SearchFilesUseCase {
    override fun invoke(query: String): Flow<List<RecentFile>> {
        return repository.searchFiles(query)
    }
}
