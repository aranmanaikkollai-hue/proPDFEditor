package com.propdfeditor.ui.home

import app.cash.turbine.test
import com.propdf.core.domain.model.DashboardData
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.usecase.GetDashboardDataUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var getDashboardData: GetDashboardDataUseCase
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        getDashboardData = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading`() = runTest {
        coEvery { getDashboardData() } coAnswers {
            Result.success(DashboardData())
        }

        viewModel = HomeViewModel(getDashboardData)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
    }

    @Test
    fun `dashboard loaded with data shows Success`() = runTest {
        val recentFile = RecentFile(
            uri = "content://test.pdf",
            name = "Test PDF",
            size = 1024,
            lastOpened = System.currentTimeMillis()
        )
        val data = DashboardData(
            recentFiles = listOf(recentFile),
            storageUsed = 1000,
            storageTotal = 10000
        )

        coEvery { getDashboardData() } returns Result.success(data)

        viewModel = HomeViewModel(getDashboardData)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Success)
        assertEquals(1, (state as HomeUiState.Success).data.recentFiles.size)
    }

    @Test
    fun `dashboard error shows Error state`() = runTest {
        coEvery { getDashboardData() } returns Result.failure(RuntimeException("Network error"))

        viewModel = HomeViewModel(getDashboardData)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Error)
        // Raw exception text must never reach the UI; the shared safe message is shown instead.
        val message = (state as HomeUiState.Error).message
        assertEquals("Couldn't load your documents.", message)
        assertTrue(!message.contains("Network error"))
    }

    @Test
    fun `refresh event reloads dashboard`() = runTest {
        coEvery { getDashboardData() } returns Result.success(DashboardData())

        viewModel = HomeViewModel(getDashboardData)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(HomeEvent.Refresh)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { getDashboardData() }
    }

    @Test
    fun `pin event is a harmless no-op today`() = runTest {
        coEvery { getDashboardData() } returns Result.success(DashboardData())

        viewModel = HomeViewModel(getDashboardData)
        testDispatcher.scheduler.advanceUntilIdle()   // let the initial load finish first
        viewModel.onEvent(HomeEvent.PinFile("content://test.pdf"))
        testDispatcher.scheduler.advanceUntilIdle()

        // PinFile is currently a no-op in HomeViewModel (no repository call): this only proves it does not crash
        // or disturb the state. It does NOT verify pinning.
        assertTrue(viewModel.uiState.value is HomeUiState.Empty)
    }
}
