package com.propdf.core.domain.usecase

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface GetRecentFilesUseCase {
    operator fun invoke(limit: Int = 50): Flow<List<RecentFile>>
}

class GetRecentFilesUseCaseImpl @Inject constructor(
    private val repository: RecentFileRepository
) : GetRecentFilesUseCase {
    override fun invoke(limit: Int): Flow<List<RecentFile>> {
        return repository.observeRecentFiles(limit)
    }
}
