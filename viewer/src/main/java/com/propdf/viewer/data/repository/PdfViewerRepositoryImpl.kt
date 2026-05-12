package com.propdf.viewer.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfViewerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : PdfViewerRepository {

    companion object {
        private const val TAG = "PdfViewerRepo"
        private const val CACHE_SIZE = 10
    }

    private val pageBitmapCache = android.util.LruCache<Int, Bitmap>(CACHE_SIZE)
    private val pageTextCache = mutableMapOf<Int, String>()
    private val rendererMutex = Mutex()
    private var currentRenderer: PdfRenderer? = null
    private var currentFile: File? = null

    override suspend fun copyUriToCache(uri: Uri): AppResult<File> = withContext(dispatchers.io) {
        try {
            val file = if (uri.scheme == "file") {
                File(uri.path ?: return@withContext AppResult.Error(AppException.FileNotFound()))
            } else {
                val safeName = "pdf_${System.currentTimeMillis()}.pdf"
                val dest = File(context.cacheDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                dest
            }
            if (file.exists() && file.length() > 0) {
                AppResult.Success(file)
            } else {
                AppResult.Error(AppException.FileNotFound())
            }
        } catch (e: Exception) {
            logger.e(TAG, "copyUriToCache failed", e)
            AppResult.Error(e.toAppException())
        }
    }

    override suspend fun getPageCount(file: File): AppResult<Int> = withContext(dispatchers.io) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            AppResult.Success(count)
        } catch (e: Exception) {
            logger.e(TAG, "getPageCount failed", e)
            AppResult.Error(e.toAppException())
        }
    }

    override suspend fun renderPage(file: File, pageIndex: Int, screenWidth: Int): AppResult<Bitmap> =
        withContext(dispatchers.io) {
            try {
                // Check cache
                pageBitmapCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let {
                    return@withContext AppResult.Success(it)
                }

                val renderer = getOrCreateRenderer(file)
                val page = renderer.openPage(pageIndex)
                val scale = screenWidth.toFloat() / page.width.coerceAtLeast(1).toFloat()
                val bmpW = (page.width * scale).toInt().coerceAtLeast(1)
                val bmpH = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                pageBitmapCache.put(pageIndex, bitmap)
                AppResult.Success(bitmap)
            } catch (e: Exception) {
                logger.e(TAG, "renderPage failed: page=$pageIndex", e)
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun getPageText(file: File, pageIndex: Int): AppResult<String> =
        withContext(dispatchers.io) {
            try {
                pageTextCache[pageIndex]?.let { return@withContext AppResult.Success(it) }
                val doc = PDDocument.load(file)
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(doc)
                doc.close()
                pageTextCache[pageIndex] = text
                AppResult.Success(text)
            } catch (e: Exception) {
                logger.e(TAG, "getPageText failed", e)
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun preloadPages(file: File, anchorPage: Int, screenWidth: Int): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val start = (anchorPage - 2).coerceAtLeast(0)
                val end = (anchorPage + 2).coerceAtMost(getPageCount(file).let {
                    if (it is AppResult.Success) it.data - 1 else 0
                })
                for (idx in start..end) {
                    if (pageBitmapCache.get(idx) == null) {
                        renderPage(file, idx, screenWidth)
                    }
                }
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override fun clearCache() {
        pageBitmapCache.evictAll()
        pageTextCache.clear()
        currentRenderer?.close()
        currentRenderer = null
        currentFile = null
    }

    private suspend fun getOrCreateRenderer(file: File): PdfRenderer = rendererMutex.withLock {
        if (currentFile != file || currentRenderer == null) {
            currentRenderer?.close()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            currentRenderer = PdfRenderer(pfd)
            currentFile = file
        }
        currentRenderer!!
    }
}
