package com.propdf.editor.data.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.OcrRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrIndexingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRepository: OcrRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) {
    private val processingSemaphore = Semaphore(permits = 2)
    private val renderWidth = 800
    private val renderHeight = 600
    private val bitmapPool = BitmapPool(maxSize = 3)

    suspend fun indexDocument(
        documentUri: Uri,
        documentId: String,
        pageCount: Int
    ): Result<String> = withContext(dispatchers.io) {
        processingSemaphore.withPermit {
            try {
                val textBuilder = StringBuilder()
                val pfd = openParcelFileDescriptor(documentUri)
                    ?: return@withPermit Result.failure(IOException("Cannot open PDF: $documentUri"))

                pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val actualPageCount = renderer.pageCount.coerceAtMost(pageCount)
                        for (pageIndex in 0 until actualPageCount) {
                            ensureActive()
                            val pageText = processPage(renderer, pageIndex)
                            if (pageText.isNotBlank()) {
                                textBuilder.append("[PAGE_${pageIndex + 1}] ")
                                textBuilder.append(pageText)
                                textBuilder.append("\n")
                            }
                            if (pageIndex % 5 == 0) System.gc()
                        }
                    }
                }
                Result.success(textBuilder.toString().trim())
            } catch (e: Exception) {
                logger.e("OcrIndexingEngine", "Failed to index document $documentId", e)
                Result.failure(e)
            }
        }
    }

    suspend fun indexDocumentPreview(
        documentUri: Uri,
        maxPages: Int = 3
    ): Result<String> = withContext(dispatchers.io) {
        processingSemaphore.withPermit {
            try {
                val textBuilder = StringBuilder()
                val pfd = openParcelFileDescriptor(documentUri)
                    ?: return@withPermit Result.failure(IOException("Cannot open PDF"))

                pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val pagesToProcess = minOf(renderer.pageCount, maxPages)
                        for (pageIndex in 0 until pagesToProcess) {
                            ensureActive()
                            val pageText = processPage(renderer, pageIndex)
                            if (pageText.isNotBlank()) {
                                textBuilder.append(pageText).append("\n")
                            }
                        }
                    }
                }
                Result.success(textBuilder.toString().trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun processPage(renderer: PdfRenderer, pageIndex: Int): String {
        val page = renderer.openPage(pageIndex)
        return try {
            val bitmap = bitmapPool.acquire(renderWidth, renderHeight)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val text = ocrRepository.recognizeText(bitmap)
            bitmapPool.release(bitmap)
            text.getOrNull() ?: ""
        } finally {
            page.close()
        }
    }

    private fun openParcelFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            when (uri.scheme) {
                "file" -> ParcelFileDescriptor.open(uri.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
                "content" -> context.contentResolver.openFileDescriptor(uri, "r")
                else -> null
            }
        } catch (e: Exception) {
            logger.e("OcrIndexingEngine", "Failed to open PDF descriptor", e)
            null
        }
    }

    private class BitmapPool(private val maxSize: Int) {
        private val pool = ArrayDeque<Bitmap>(maxSize)

        @Synchronized
        fun acquire(width: Int, height: Int): Bitmap {
            val existing = pool.find { it.width == width && it.height == height && !it.isRecycled }
            return if (existing != null) {
                pool.remove(existing)
                existing.eraseColor(0)
                existing
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }

        @Synchronized
        fun release(bitmap: Bitmap) {
            if (pool.size < maxSize && !bitmap.isRecycled) {
                pool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }
}
