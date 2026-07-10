package com.propdfeditor.scanner.data.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.core.content.FileProvider
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.model.EnhancementParams
import com.propdfeditor.scanner.domain.model.PointF
import com.propdfeditor.scanner.domain.model.ProcessingState
import com.propdfeditor.scanner.domain.model.ScanMode
import com.propdfeditor.scanner.domain.model.ScannedPage
import com.propdfeditor.scanner.domain.repository.BatchProcessingProgress
import com.propdfeditor.scanner.domain.repository.ProcessingProgress
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates [OpenCvDocumentProcessor]'s pure image-processing operations into the
 * full [ScannerRepository] contract: file persistence, Flow-based progress reporting
 * for single/batch scans, and device-memory checks. OpenCvDocumentProcessor supplies
 * the actual CV algorithms (edge detection, perspective correction, enhancement); this
 * class is the glue that turns those into the pipeline the UI/use-cases expect.
 */
@Singleton
class ScannerProcessingRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processor: OpenCvDocumentProcessor
) : ScannerRepository {

    override suspend fun detectEdges(bitmap: Bitmap): EdgeDetectionResult =
        processor.detectDocumentEdges(bitmap)

    override suspend fun correctPerspective(bitmap: Bitmap, corners: List<PointF>): Bitmap =
        processor.applyPerspectiveCorrection(bitmap, corners)

    override suspend fun autoCrop(bitmap: Bitmap, corners: List<PointF>): Bitmap =
        processor.autoCrop(bitmap, corners)

    override suspend fun removeShadows(bitmap: Bitmap): Bitmap =
        processor.removeShadows(bitmap)

    override suspend fun enhanceReceipt(bitmap: Bitmap): Bitmap =
        processor.enhanceReceipt(bitmap)

    override suspend fun enhanceWhiteboard(bitmap: Bitmap): Bitmap =
        processor.enhanceWhiteboard(bitmap)

    override suspend fun enhanceContrast(bitmap: Bitmap, params: EnhancementParams): Bitmap =
        processor.enhanceGeneral(bitmap, params)

    override suspend fun generateThumbnail(bitmap: Bitmap, maxDimension: Int): Bitmap =
        processor.generateThumbnail(bitmap, maxDimension)

    override suspend fun saveProcessedImage(context: Context, bitmap: Bitmap, pageId: String): Uri {
        val dir = File(context.filesDir, "scanner/processed").apply { if (!exists()) mkdirs() }
        val file = File(dir, "$pageId.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    override suspend fun deletePage(page: ScannedPage): Boolean {
        var allDeleted = true
        listOf(page.originalUri, page.processedUri, page.thumbnailUri).forEach { uri ->
            uri?.let { resolveFile(it)?.let { file -> if (file.exists() && !file.delete()) allDeleted = false } }
        }
        return allDeleted
    }

    override fun processImage(
        context: Context,
        bitmap: Bitmap,
        mode: ScanMode,
        pageId: String
    ): Flow<ProcessingProgress> = flow {
        emit(ProcessingProgress(ProcessingState.DETECTING, 10))
        val edges = processor.detectDocumentEdges(bitmap)

        emit(ProcessingProgress(ProcessingState.CORRECTING, 40))
        var result = if (edges.hasDetectedCorners) {
            processor.applyPerspectiveCorrection(bitmap, edges.corners)
        } else {
            bitmap
        }

        emit(ProcessingProgress(ProcessingState.ENHANCING, 70))
        result = when (mode) {
            ScanMode.RECEIPT -> processor.enhanceReceipt(result)
            ScanMode.WHITEBOARD -> processor.enhanceWhiteboard(result)
            ScanMode.DOCUMENT, ScanMode.AUTO -> processor.removeShadows(result)
            ScanMode.PHOTO, ScanMode.ID_CARD, ScanMode.BATCH -> result
        }

        val savedUri = saveProcessedImage(context, result, pageId)
        val thumbUri = saveThumbnail(context, processor.generateThumbnail(result, 256), pageId)

        emit(ProcessingProgress(ProcessingState.COMPLETE, 100, savedUri, thumbUri))
    }

    override fun batchProcess(
        context: Context,
        bitmaps: List<Bitmap>,
        mode: ScanMode
    ): Flow<BatchProcessingProgress> = flow {
        val completed = mutableListOf<ScannedPage>()
        val failed = mutableListOf<String>()

        bitmaps.forEachIndexed { index, bitmap ->
            val pageId = UUID.randomUUID().toString()
            try {
                var lastProgress: ProcessingProgress? = null
                processImage(context, bitmap, mode, pageId).collect { lastProgress = it }
                val resultUri = lastProgress?.resultUri
                if (resultUri != null) {
                    completed.add(
                        ScannedPage(
                            id = pageId,
                            originalUri = resultUri,
                            processedUri = resultUri,
                            thumbnailUri = lastProgress?.thumbnailUri,
                            scanMode = mode,
                            processingState = ProcessingState.COMPLETE,
                            width = bitmap.width,
                            height = bitmap.height
                        )
                    )
                } else {
                    failed.add(pageId)
                }
            } catch (e: Exception) {
                failed.add(pageId)
            }
            emit(BatchProcessingProgress(index + 1, bitmaps.size, ProcessingState.COMPLETE, completed.toList(), failed.toList()))
        }
    }

    override fun hasSufficientMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return !memoryInfo.lowMemory && memoryInfo.availMem > MIN_AVAILABLE_MEMORY_BYTES
    }

    override fun getRecommendedImageSize(): Size {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return if (memoryInfo.availMem > HIGH_MEMORY_THRESHOLD_BYTES) {
            Size(2480, 3508) // ~A4 at 300dpi
        } else {
            Size(1240, 1754) // ~A4 at 150dpi, safer for low-memory devices
        }
    }

    private suspend fun saveThumbnail(context: Context, bitmap: Bitmap, pageId: String): Uri {
        val dir = File(context.filesDir, "scanner/thumbnails").apply { if (!exists()) mkdirs() }
        val file = File(dir, "thumb_$pageId.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Resolves a content:// URI produced by [saveProcessedImage]/[saveThumbnail] back to its File. */
    private fun resolveFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        val name = uri.lastPathSegment ?: return null
        val candidates = listOf(
            File(File(context.filesDir, "scanner/processed"), name),
            File(File(context.filesDir, "scanner/thumbnails"), name)
        )
        return candidates.firstOrNull { it.exists() }
    }

    companion object {
        private const val MIN_AVAILABLE_MEMORY_BYTES = 100L * 1024 * 1024 // 100MB
        private const val HIGH_MEMORY_THRESHOLD_BYTES = 512L * 1024 * 1024 // 512MB
    }
}
