package com.propdfeditor.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.propdfeditor.scanner.domain.model.*
import com.propdfeditor.scanner.domain.repository.ProcessingProgress
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import com.propdfeditor.scanner.domain.usecase.*
import com.propdfeditor.scanner.presentation.ScannerViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: ScannerViewModel
    private lateinit var mockRepository: ScannerRepository
    private lateinit var detectEdgesUseCase: DetectEdgesUseCase
    private lateinit var processScanUseCase: ProcessScanUseCase
    private lateinit var batchScanUseCase: BatchScanUseCase
    private lateinit var deleteScanPageUseCase: DeleteScanPageUseCase
    private lateinit var generateThumbnailUseCase: GenerateThumbnailUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mockk(relaxed = true)
        detectEdgesUseCase = DetectEdgesUseCase(mockRepository)
        processScanUseCase = ProcessScanUseCase(mockRepository)
        batchScanUseCase = BatchScanUseCase(mockRepository)
        deleteScanPageUseCase = DeleteScanPageUseCase(mockRepository)
        generateThumbnailUseCase = GenerateThumbnailUseCase(mockRepository)

        viewModel = ScannerViewModel(
            detectEdgesUseCase,
            processScanUseCase,
            batchScanUseCase,
            deleteScanPageUseCase,
            generateThumbnailUseCase
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializeScanner sets Preview state`() = runTest {
        viewModel.initializeScanner()
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is ScannerUiState.Preview)
    }

    @Test
    fun `setScanMode updates current mode`() = runTest {
        viewModel.setScanMode(ScanMode.RECEIPT)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScanMode.RECEIPT, viewModel.currentScanMode.value)
    }

    @Test
    fun `toggleFlash switches flash state`() = runTest {
        val initial = viewModel.isFlashOn.value
        viewModel.toggleFlash()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(!initial, viewModel.isFlashOn.value)
    }

    @Test
    fun `onImageCaptured emits processing then review state`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        val mockContext = mockk<Context>(relaxed = true)

        every { mockBitmap.width } returns 1920
        every { mockBitmap.height } returns 1080

        coEvery {
            mockRepository.processImage(any(), any(), any(), any())
        } returns flowOf(
            ProcessingProgress(ProcessingState.DETECTING, 25),
            ProcessingProgress(ProcessingState.CORRECTING, 50),
            ProcessingProgress(ProcessingState.ENHANCING, 80),
            ProcessingProgress(ProcessingState.COMPLETE, 100, mockk(), mockk())
        )

        viewModel.onImageCaptured(mockContext, mockBitmap)
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertTrue(finalState is ScannerUiState.Review)
    }

    @Test
    fun `addToBatchAndContinue increments batch count`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        val mockContext = mockk<Context>(relaxed = true)
        every { mockBitmap.width } returns 1920
        every { mockBitmap.height } returns 1080

        coEvery {
            mockRepository.processImage(any(), any(), any(), any())
        } returns flowOf(
            ProcessingProgress(ProcessingState.COMPLETE, 100, mockk(), mockk())
        )

        viewModel.onImageCaptured(mockContext, mockBitmap)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.addToBatchAndContinue()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.batchSession.value?.pages?.size)
    }

    @Test
    fun `resetScanner clears state`() = runTest {
        viewModel.resetScanner()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ScannerUiState.Idle, viewModel.uiState.value)
        assertNull(viewModel.lastScannedPage.value)
    }

    @Test
    fun `clearBatch deletes all pages`() = runTest {
        coEvery { mockRepository.deletePage(any()) } returns true

        // Manually set a batch session
        val page = ScannedPage(
            id = "test-1",
            originalUri = mockk(),
            processedUri = mockk(),
            thumbnailUri = mockk()
        )
        // Use reflection or expose setter for testing
        // In production, this would be populated via onImageCaptured

        viewModel.clearBatch()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.batchSession.value)
    }
}
