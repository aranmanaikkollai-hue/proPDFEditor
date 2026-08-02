package com.propdf.core.domain.usecase

import com.propdf.core.domain.model.DashboardData
import com.propdf.core.domain.repository.RecentFileRepository
import com.propdf.core.domain.repository.SettingsRepository
import javax.inject.Inject

interface GetDashboardDataUseCase {
    suspend operator fun invoke(): Result<DashboardData>
}

class GetDashboardDataUseCaseImpl @Inject constructor(
    private val recentFileRepository: RecentFileRepository,
    private val settingsRepository: SettingsRepository
) : GetDashboardDataUseCase {
    override suspend fun invoke(): Result<DashboardData> {
        return runCatching {
            val recent = recentFileRepository.getRecentFiles(limit = 10)
            val pinned = recentFileRepository.getPinnedFiles()
            val favorites = recentFileRepository.getFavoriteFiles()
            val progress = recentFileRepository.getContinueReading()
            val storage = settingsRepository.getStorageStats()

            DashboardData(
                recentFiles = recent,
                pinnedFiles = pinned,
                favoriteFiles = favorites,
                continueReading = progress,
                storageUsed = storage.used,
                storageTotal = storage.total,
                recycleBinCount = storage.recycleBinCount
            )
        }
    }
}
