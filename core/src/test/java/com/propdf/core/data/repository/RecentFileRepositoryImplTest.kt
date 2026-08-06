package com.propdf.core.data.repository

import com.propdf.core.data.database.dao.RecentFileDao
import com.propdf.core.data.database.entity.RecentFileEntity
import com.propdf.core.domain.model.RecentFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentFileRepositoryImplTest {

    private lateinit var recentFileDao: RecentFileDao
    private lateinit var repository: RecentFileRepositoryImpl

    @Before
    fun setup() {
        recentFileDao = mockk(relaxed = true)
        repository = RecentFileRepositoryImpl(recentFileDao)
    }

    @Test
    fun `observeRecentFiles maps entities to domain`() = runTest {
        val entities = listOf(
            RecentFileEntity(
                uri = "content://test.pdf",
                name = "Test",
                size = 1000,
                lastOpened = 1000,
                thumbnailUri = null
            )
        )
        coEvery { recentFileDao.observeRecent(10) } returns flowOf(entities)

        val result = repository.observeRecentFiles(10).first()

        assertEquals(1, result.size)
        assertEquals("Test", result.first().name)
    }

    @Test
    fun `getPinnedFiles returns only pinned`() = runTest {
        val pinned = listOf(
            RecentFileEntity(
                uri = "uri1",
                name = "Pinned",
                size = 100,
                lastOpened = 1,
                isPinned = true
            )
        )
        coEvery { recentFileDao.getPinned() } returns pinned

        val result = repository.getPinnedFiles()

        assertEquals(1, result.size)
        assertTrue(result.first().isPinned)
    }

    @Test
    fun `addToRecent inserts entity`() = runTest {
        val file = RecentFile(
            uri = "uri1",
            name = "Test",
            size = 100,
            lastOpened = 1
        )

        repository.addToRecent(file)

        coVerify { recentFileDao.insert(any()) }
    }

    @Test
    fun `pinFile updates dao`() = runTest {
        repository.pinFile("uri1", true)
        coVerify { recentFileDao.setPinned("uri1", true) }
    }

    @Test
    fun `favoriteFile updates dao`() = runTest {
        repository.favoriteFile("uri1", true)
        coVerify { recentFileDao.setFavorite("uri1", true) }
    }
}
