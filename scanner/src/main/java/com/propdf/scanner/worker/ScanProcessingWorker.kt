package com.propdf.scanner.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.scanner.data.repository.ScannerRepository
import com.propdf.scanner.model.*
import com.propdf.scanner.processing.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class ScanProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scannerRepository: ScannerRepository,
    private val edgeDetector: EdgeDetector,
    private val perspectiveCorrector: PerspectiveCorrector,
    private val imageEnhancer: ImageEnhancer,
    private val scanModeDetector: ScanModeDetector
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_SCAN_MODE = "scan_mode"
        const val KEY_COLOR_FILTER = "color_filter"
        const val KEY_AUTO_ENHANCE = "auto_enhance"
        const val KEY_PAGE_NUMBER = "page_number"
        const val KEY_RESULT_PAGE_ID = "result_page_id"
        const val KEY_RESULT_PROCESSED_PATH = "result_processed_path"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        try {
            val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: return@withContext Result.failure()
            val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return@withContext Result.failure()
            val scanMode = ScanMode.valueOf(inputData.getString(KEY_SCAN_MODE) ?: ScanMode.AUTO.name)
            val colorFilter = ColorFilter.valueOf(inputData.getString(KEY_COLOR_FILTER) ?: ColorFilter.ORIGINAL.name)
            val autoEnhance = inputData.getBoolean(KEY_AUTO_ENHANCE, true)
            val pageNumber = inputData.getInt(KEY_PAGE_NUMBER, 0)

            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath) ?: return@withContext Result.failure()

            val edge = edgeDetector.detectEdges(bitmap)
            val correctedBitmap = if (edge != null) perspectiveCorrector.correctPerspective(bitmap, edge)
                else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)

            val croppedBitmap = perspectiveCorrector.autoCrop(correctedBitmap)
            if (correctedBitmap != bitmap && correctedBitmap != croppedBitmap) correctedBitmap.recycle()

            val detectedMode = if (scanMode == ScanMode.AUTO) scanModeDetector.detectScanMode(croppedBitmap) else scanMode
            val filteredBitmap = if (colorFilter != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(croppedBitmap, colorFilter)
                else if (autoEnhance) imageEnhancer.applyFilter(croppedBitmap, ColorFilter.MAGIC_COLOR) else croppedBitmap

            val finalBitmap = if (autoEnhance) imageEnhancer.autoRotate(filteredBitmap) else filteredBitmap

            val scannerDir = File(applicationContext.filesDir, "scanner").apply { if (!exists()) mkdirs() }
            val processedFile = File(scannerDir, "${java.util.UUID.randomUUID()}.jpg")
            java.io.FileOutputStream(processedFile).use { out ->
                finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }

            if (finalBitmap != filteredBitmap) filteredBitmap.recycle()
            if (filteredBitmap != croppedBitmap && colorFilter != ColorFilter.ORIGINAL) filteredBitmap.recycle()
            if (croppedBitmap != correctedBitmap) croppedBitmap.recycle()
            if (correctedBitmap != bitmap) correctedBitmap.recycle()
            bitmap.recycle()

            val page = ScannedPage(
                originalImagePath = imagePath, processedImagePath = processedFile.absolutePath,
                documentEdge = edge, colorFilter = if (autoEnhance) ColorFilter.MAGIC_COLOR else colorFilter, pageNumber = pageNumber
            )
            scannerRepository.addPageToDocument(documentId, page)

            Result.success(workDataOf(KEY_RESULT_PAGE_ID to page.id, KEY_RESULT_PROCESSED_PATH to processedFile.absolutePath))
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }
}
