package com.propdf.scanner.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.propdf.scanner.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BatchProcessor(private val context: Context) {
    private val edgeDetector = EdgeDetector()
    private val perspectiveCorrector = PerspectiveCorrector()
    private val imageEnhancer = ImageEnhancer()
    private val scanModeDetector = ScanModeDetector()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _state = MutableStateFlow<BatchScanState>(BatchScanState.Idle)
    val state: StateFlow<BatchScanState> = _state.asStateFlow()

    private var job: Job? = null

    suspend fun processBatch(
        imagePaths: List<String>,
        scanMode: ScanMode = ScanMode.AUTO,
        colorFilter: ColorFilter = ColorFilter.ORIGINAL,
        autoEnhance: Boolean = true
    ): ScannedDocument = withContext(Dispatchers.Default) {
        _state.value = BatchScanState.Scanning(0, imagePaths.size)
        _progress.value = 0

        val pages = mutableListOf<ScannedPage>()
        val scannerDir = File(context.filesDir, "scanner").apply { if (!exists()) mkdirs() }

        imagePaths.forEachIndexed { index, imagePath ->
            _state.value = BatchScanState.Scanning(index + 1, imagePaths.size)
            _progress.value = ((index + 1) * 100) / imagePaths.size
            pages.add(processSingleImage(imagePath, index + 1, scanMode, colorFilter, autoEnhance, scannerDir))
        }

        val document = ScannedDocument(name = "Batch Scan ${System.currentTimeMillis()}", pages = pages, scanMode = scanMode)
        _state.value = BatchScanState.Complete(document)
        _progress.value = 100
        document
    }

    private suspend fun processSingleImage(
        imagePath: String, pageNumber: Int, scanMode: ScanMode,
        colorFilter: ColorFilter, autoEnhance: Boolean, outputDir: File
    ): ScannedPage = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: throw IllegalStateException("Failed to decode: $imagePath")
        try {
            val edge = edgeDetector.detectEdges(bitmap)
            val correctedBitmap = if (edge != null) perspectiveCorrector.correctPerspective(bitmap, edge) else bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val croppedBitmap = perspectiveCorrector.autoCrop(correctedBitmap)
            if (correctedBitmap != bitmap && correctedBitmap != croppedBitmap) correctedBitmap.recycle()

            val detectedMode = if (scanMode == ScanMode.AUTO) scanModeDetector.detectScanMode(croppedBitmap) else scanMode
            val filteredBitmap = if (colorFilter != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(croppedBitmap, colorFilter)
                else if (autoEnhance) imageEnhancer.applyFilter(croppedBitmap, ColorFilter.MAGIC_COLOR) else croppedBitmap

            val finalBitmap = if (autoEnhance) imageEnhancer.autoRotate(filteredBitmap) else filteredBitmap

            val processedFile = File(outputDir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(processedFile).use { out -> finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }

            if (finalBitmap != filteredBitmap) filteredBitmap.recycle()
            if (filteredBitmap != croppedBitmap && colorFilter != ColorFilter.ORIGINAL) filteredBitmap.recycle()
            if (croppedBitmap != correctedBitmap) croppedBitmap.recycle()
            if (correctedBitmap != bitmap) correctedBitmap.recycle()
            bitmap.recycle()

            ScannedPage(originalImagePath = imagePath, processedImagePath = processedFile.absolutePath,
                documentEdge = edge, colorFilter = if (autoEnhance) ColorFilter.MAGIC_COLOR else colorFilter, pageNumber = pageNumber)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = BatchScanState.Idle
        _progress.value = 0
    }
}
