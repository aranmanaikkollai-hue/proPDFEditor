package com.propdfeditor.ui.filemanager

import app.cash.turbine.test
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileRepository
import com.propdf.core.domain.usecase.GetRecentFilesUseCase
import com.propdf.core.domain.usecase.OpenDocumentUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileManagerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var getRecentFiles: GetRecentFilesUseCase
    private lateinit var openDocument: OpenDocumentUseCase
    private lateinit var recentFileRepository: RecentFileRepository
    private lateinit var viewModel: FileManagerViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getRecentFiles = mockk()
        openDocument = mockk(relaxed = true)
        recentFileRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadRecentFiles emits Success with sorted files`() = runTest {
        val files = listOf(
            RecentFile("uri1", "Alpha.pdf", 1000, 1000),
            RecentFile("uri2", "Beta.pdf", 2000, 2000)
        )
        coEvery { getRecentFiles(any()) } returns flowOf(files)

        viewModel = FileManagerViewModel(getRecentFiles, openDocument, recentFileRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is FileManagerUiState.Success)
        assertEquals(2, (state as FileManagerUiState.Success).files.size)
    }

    @Test
    fun `search filters files by name`() = runTest {
        val files = listOf(
            RecentFile("uri1", "Report.pdf", 1000, 1000),
            RecentFile("uri2", "Invoice.pdf", 2000, 2000)
        )
        coEvery { getRecentFiles(any()) } returns flowOf(files)

        viewModel = FileManagerViewModel(getRecentFiles, openDocument, recentFileRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.search("Report")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is FileManagerUiState.Success)
        assertEquals(1, (state as FileManagerUiState.Success).files.size)
        assertEquals("Report.pdf", state.files.first().name)
    }

    @Test
    fun `delete file delegates to repository`() = runTest {
        coEvery { getRecentFiles(any()) } returns flowOf(emptyList())

        viewModel = FileManagerViewModel(getRecentFiles, openDocument, recentFileRepository)
        viewModel.deleteFile("uri1")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { recentFileRepository.deleteFile("uri1") }
    }

    @Test
    fun `pin file delegates to repository`() = runTest {
        coEvery { getRecentFiles(any()) } returns flowOf(emptyList())

        viewModel = FileManagerViewModel(getRecentFiles, openDocument, recentFileRepository)
        viewModel.pinFile("uri1")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { recentFileRepository.pinFile("uri1", true) }
    }
}
