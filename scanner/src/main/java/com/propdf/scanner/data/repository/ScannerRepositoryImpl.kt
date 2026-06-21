package com.propdfeditor.scanner.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import com.propdfeditor.scanner.data.processing.OpenCvDocumentProcessor
import com.propdfeditor.scanner.domain.model.*
import com.propdfeditor.scanner.domain.repository.BatchProcessingProgress
import com.propdfeditor.scanner.domain.repository.ProcessingProgress
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of ScannerRepository.
 * Handles all image processing, file I/O, and memory management.
 *
 * Storage strategy:
 * - Original captures: /files/scanner/original/
 * - Processed images: /files/scanner/processed/
 * - Thumbnails: /files/scanner/thumbnails/
 * - All files use app-scoped storage (no external storage permission needed)
 */
@Singleton
class ScannerRepositoryImpl @Inject constructor(
    private val processor: OpenCvDocumentProcessor,
    private val dispatcherProvider: DispatcherProvider
) : ScannerRepository {

    companion object {
        private const val TAG = "ScannerRepositoryImpl"
        private const val MEMORY_THRESHOLD_MB = 128L
        private const val MAX_IMAGE_DIMENSION = 3000
        private const val COMPRESS_QUALITY = 85
        private const val THUMB_QUALITY = 70
        private const val THUMB_MAX_DIM = 256
    }

    private val processingDispatcher = dispatcherProvider.default()

    override suspend fun detectEdges(bitmap: Bitmap): EdgeDetectionResult {
        return processor.detectDocumentEdges(bitmap)
    }

    override suspend fun correctPerspective(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        return processor.applyPerspectiveCorrection(bitmap, corners)
    }

    override suspend fun autoCrop(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        return processor.autoCrop(bitmap, corners)
    }

    override suspend fun removeShadows(bitmap: Bitmap): Bitmap {
        return processor.removeShadows(bitmap)
    }

    override suspend fun enhanceReceipt(bitmap: Bitmap): Bitmap {
        return processor.enhanceReceipt(bitmap)
    }

    override suspend fun enhanceWhiteboard(bitmap: Bitmap): Bitmap {
        return processor.enhanceWhiteboard(bitmap)
    }

    override suspend fun enhanceContrast(bitmap: Bitmap, params: EnhancementParams): Bitmap {
        return processor.enhanceGeneral(bitmap, params)
    }

    override suspend fun generateThumbnail(bitmap: Bitmap, maxDimension: Int): Bitmap {
        return processor.generateThumbnail(bitmap, maxDimension)
    }

    override suspend fun saveProcessedImage(context: Context, bitmap: Bitmap, pageId: String): Uri {
        return withContext(processingDispatcher) {
            val dir = File(context.filesDir, "scanner/processed")
            dir.mkdirs()
            val file = File(dir, "processed_$pageId.jpg")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, out)
            }

            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        }
    }

    override suspend fun deletePage(page: ScannedPage): Boolean {
        return withContext(processingDispatcher) {
            var deleted = true

            page.processedUri?.let { uri ->
                try {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete processed file", e)
                    deleted = false
                }
            }

            page.thumbnailUri?.let { uri ->
                try {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete thumbnail file", e)
                    deleted = false
                }
            }

            page.originalUri.let { uri ->
                try {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete original file", e)
                    deleted = false
                }
            }

            deleted
        }
    }

    /**
     * Full processing pipeline with progress emission.
     * Memory-safe: processes on Default dispatcher, releases bitmaps eagerly.
     */
    override fun processImage(
        context: Context,
        bitmap: Bitmap,
        mode: ScanMode,
        pageId: String
    ): Flow<ProcessingProgress> = flow {

        emit(ProcessingProgress(ProcessingState.DETECTING, 0))

        // Step 1: Edge Detection
        val edgeResult = detectEdges(bitmap)
        emit(ProcessingProgress(ProcessingState.DETECTING, 25))

        // Step 2: Perspective Correction
        emit(ProcessingProgress(ProcessingState.CORRECTING, 30))
        val correctedBitmap = if (edgeResult.hasDetectedCorners) {
            correctPerspective(bitmap, edgeResult.corners)
        } else {
            bitmap
        }
        emit(ProcessingProgress(ProcessingState.CORRECTING, 50))

        // Step 3: Mode-specific enhancement
        emit(ProcessingProgress(ProcessingState.ENHANCING, 55))
        val enhancedBitmap = when (mode) {
            ScanMode.RECEIPT -> enhanceReceipt(correctedBitmap)
            ScanMode.WHITEBOARD -> enhanceWhiteboard(correctedBitmap)
            ScanMode.DOCUMENT -> {
                autoCrop(correctedBitmap, edgeResult.corners)
                val params = EnhancementParams(
                    contrast = 1.3f,
                    shadowRemoval = true,
                    sharpen = true
                )
                enhanceContrast(correctedBitmap, params)
            }
            ScanMode.PHOTO -> correctedBitmap
            ScanMode.ID_CARD -> {
                autoCrop(correctedBitmap, edgeResult.corners)
                val params = EnhancementParams(contrast = 1.2f, sharpen = true)
                enhanceContrast(correctedBitmap, params)
            }
            ScanMode.AUTO -> {
                val aspectRatio = correctedBitmap.width.toFloat() / correctedBitmap.height
                when {
                    aspectRatio < 0.6f -> enhanceReceipt(correctedBitmap)
                    edgeResult.confidence > 80 -> {
                        autoCrop(correctedBitmap, edgeResult.corners)
                        val params = EnhancementParams(contrast = 1.2f, shadowRemoval = true)
                        enhanceContrast(correctedBitmap, params)
                    }
                    else -> {
                        val params = EnhancementParams(contrast = 1.1f)
                        enhanceContrast(correctedBitmap, params)
                    }
                }
            }
            ScanMode.BATCH -> {
                val params = EnhancementParams(contrast = 1.15f)
                enhanceContrast(correctedBitmap, params)
            }
        }
        emit(ProcessingProgress(ProcessingState.ENHANCING, 80))

        // Step 4: Save processed image
        val processedUri = saveProcessedImage(context, enhancedBitmap, pageId)
        emit(ProcessingProgress(ProcessingState.ENHANCING, 90))

        // Step 5: Generate thumbnail
        val thumbnail = generateThumbnail(enhancedBitmap, THUMB_MAX_DIM)
        val thumbDir = File(context.filesDir, "scanner/thumbnails")
        thumbDir.mkdirs()
        val thumbFile = File(thumbDir, "thumb_$pageId.jpg")
        FileOutputStream(thumbFile).use { out ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
        }
        val thumbUri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            thumbFile
        )

        emit(ProcessingProgress(ProcessingState.COMPLETE, 100, processedUri, thumbUri))

    }.catch { e ->
        Log.e(TAG, "Processing failed for page $pageId", e)
        emit(ProcessingProgress(ProcessingState.FAILED, 0))
    }.flowOn(processingDispatcher)

    /**
     * Batch processing with sequential execution to prevent OOM.
     */
    override fun batchProcess(
        context: Context,
        bitmaps: List<Bitmap>,
        mode: ScanMode
    ): Flow<BatchProcessingProgress> = flow {
        val completedPages = mutableListOf<ScannedPage>()
        val failedPages = mutableListOf<String>()

        bitmaps.forEachIndexed { index, bitmap ->
            val pageId = UUID.randomUUID().toString()

            try {
                emit(
                    BatchProcessingProgress(
                        currentIndex = index,
                        totalCount = bitmaps.size,
                        currentStage = ProcessingState.DETECTING,
                        completedPages = completedPages.toList(),
                        failedPages = failedPages.toList()
                    )
                )

                processImage(context, bitmap, mode, pageId).collect { progress ->
                    if (progress.stage == ProcessingState.COMPLETE) {
                        val page = ScannedPage(
                            id = pageId,
                            originalUri = Uri.EMPTY,
                            processedUri = progress.resultUri,
                            thumbnailUri = progress.thumbnailUri,
                            processingState = ProcessingState.COMPLETE
                        )
                        completedPages.add(page)
                    } else if (progress.stage == ProcessingState.FAILED) {
                        failedPages.add(pageId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch processing failed for page $pageId", e)
                failedPages.add(pageId)
            }

            // Force GC between pages in batch mode
            if (index % 3 == 0) {
                System.gc()
            }
        }

        emit(
            BatchProcessingProgress(
                currentIndex = bitmaps.size,
                totalCount = bitmaps.size,
                currentStage = ProcessingState.COMPLETE,
                completedPages = completedPages.toList(),
                failedPages = failedPages.toList()
            )
        )
    }.flowOn(processingDispatcher)

    override fun hasSufficientMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val allocatedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - allocatedMemory
        return availableMemory > MEMORY_THRESHOLD_MB
    }

    override fun getRecommendedImageSize(): Size {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()

        return when {
            maxMemory > 512 * 1024 * 1024 -> Size(3000, 4000)
            maxMemory > 256 * 1024 * 1024 -> Size(2400, 3200)
            else -> Size(1920, 2560)
        }
    }

    /**
     * Save original capture to app storage.
     */
    suspend fun saveOriginalImage(context: Context, bitmap: Bitmap, pageId: String): Uri {
        return withContext(processingDispatcher) {
            val dir = File(context.filesDir, "scanner/original")
            dir.mkdirs()
            val file = File(dir, "original_$pageId.jpg")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        }
    }

    /**
     * Load bitmap with memory-efficient sampling.
     */
    suspend fun loadBitmap(uri: Uri, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
        return withContext(processingDispatcher) {
            try {
                val path = uri.path
                if (path == null) return@withContext null
                val file = File(path)
                if (!file.exists()) return@withContext null

                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, options)

                val sampleSize = calculateInSampleSize(options, maxDimension, maxDimension)

                val decodeOptions = BitmapFactory.Options()
                decodeOptions.inSampleSize = sampleSize
                decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
                BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap", e)
                null
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

/**
 * Dispatcher provider for testability.
 */
interface DispatcherProvider {
    fun main(): CoroutineDispatcher = Dispatchers.Main
    fun io(): CoroutineDispatcher = Dispatchers.IO
    fun default(): CoroutineDispatcher = Dispatchers.Default
    fun unconfined(): CoroutineDispatcher = Dispatchers.Unconfined
}
