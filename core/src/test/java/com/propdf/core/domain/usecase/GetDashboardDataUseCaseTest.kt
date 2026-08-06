package com.propdf.core.domain.usecase

import com.propdf.core.domain.model.DashboardData
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileRepository
import com.propdf.core.domain.repository.SettingsRepository
import com.propdf.core.domain.repository.StorageStats
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GetDashboardDataUseCaseTest {

    private lateinit var recentFileRepository: RecentFileRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var useCase: GetDashboardDataUseCaseImpl

    @Before
    fun setup() {
        recentFileRepository = mockk()
        settingsRepository = mockk()
        useCase = GetDashboardDataUseCaseImpl(recentFileRepository, settingsRepository)
    }

    @Test
    fun `invoke returns dashboard data`() = runTest {
        val recent = listOf(RecentFile("uri", "Test", 100, 1))
        coEvery { recentFileRepository.getRecentFiles(10) } returns recent
        coEvery { recentFileRepository.getPinnedFiles() } returns emptyList()
        coEvery { recentFileRepository.getFavoriteFiles() } returns emptyList()
        coEvery { recentFileRepository.getContinueReading() } returns null
        coEvery { settingsRepository.getStorageStats() } returns StorageStats(1000, 10000, 0)

        val result = useCase()

        assertTrue(result.isSuccess)
        val data = result.getOrNull()!!
        assertEquals(1, data.recentFiles.size)
        assertEquals(1000, data.storageUsed)
    }

    @Test
    fun `invoke returns failure on exception`() = runTest {
        coEvery { recentFileRepository.getRecentFiles(any()) } throws RuntimeException("DB error")

        val result = useCase()

        assertTrue(result.isFailure)
    }
}
