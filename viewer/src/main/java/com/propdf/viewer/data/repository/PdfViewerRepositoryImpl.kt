package com.propdf.viewer.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class PdfViewerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : PdfViewerRepository {

    companion object { private const val TAG = "PdfViewerRepo"; private const val CACHE_SIZE = 10 }
    private val pageBitmapCache = android.util.LruCache<Int, Bitmap>(CACHE_SIZE)
    private val pageTextCache = mutableMapOf<Int, String>()
    private val rendererMutex = Mutex()
    private var currentRenderer: PdfRenderer? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentFile: File? = null

    override suspend fun copyUriToCache(uri: Uri): AppResult<File> = withContext(dispatchers.io) {
        try {
            if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path))
                return@withContext if (file.exists() && file.canRead()) AppResult.Success(file) else AppResult.Error(AppException.FileNotFound())
            }
            val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "pdf_${System.currentTimeMillis()}.pdf"
            val dest = File(context.cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedInputStream(input).use { buffered ->
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var n: Int
                        while (buffered.read(buffer).also { n = it } >= 0) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } ?: return@withContext AppResult.Error(AppException.FileNotFound())
            if (dest.exists() && dest.length() > 0L) AppResult.Success(dest) else AppResult.Error(AppException.FileNotFound())
        } catch (e: Exception) {
            logger.e(TAG, "copyUriToCache failed", e); AppResult.Error(e.toAppException())
        }
    }

    override suspend fun getPageCount(file: File): AppResult<Int> = withContext(dispatchers.io) {
        try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { AppResult.Success(it.pageCount) } } }
        catch (e: Exception) { logger.e(TAG, "getPageCount failed", e); AppResult.Error(e.toAppException()) }
    }

    override suspend fun renderPage(file: File, pageIndex: Int, screenWidth: Int): AppResult<Bitmap> = withContext(dispatchers.io) {
        try {
            pageBitmapCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let { return@withContext AppResult.Success(it) }
            rendererMutex.withLock {
                val renderer = getOrCreateRendererLocked(file)
                val page = renderer.openPage(pageIndex)
                val scale = screenWidth.toFloat() / page.width.coerceAtLeast(1).toFloat()
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close(); pageBitmapCache.put(pageIndex, bitmap); AppResult.Success(bitmap)
            }
        } catch (e: Exception) { logger.e(TAG, "renderPage failed: page=$pageIndex", e); AppResult.Error(e.toAppException()) }
    }

    override suspend fun getPageText(file: File, pageIndex: Int): AppResult<String> = withContext(dispatchers.io) {
        try { pageTextCache[pageIndex]?.let { return@withContext AppResult.Success(it) }; PDDocument.load(file).use { doc ->
            val s=PDFTextStripper(); s.startPage=pageIndex+1; s.endPage=pageIndex+1; val t=s.getText(doc); pageTextCache[pageIndex]=t; AppResult.Success(t) } }
        catch (e: Exception) { logger.e(TAG, "getPageText failed", e); AppResult.Error(e.toAppException()) }
    }

    override suspend fun preloadPages(file: File, anchorPage: Int, screenWidth: Int): AppResult<Unit> = withContext(dispatchers.io) {
        try { val max=(getPageCount(file) as? AppResult.Success)?.data?.minus(1) ?: 0; for (i in (anchorPage-2).coerceAtLeast(0)..(anchorPage+2).coerceAtMost(max)) if (pageBitmapCache.get(i)==null) renderPage(file,i,screenWidth); AppResult.Success(Unit)} catch (e: Exception) { AppResult.Error(e.toAppException()) }
    }

    override fun clearCache() { pageBitmapCache.evictAll(); pageTextCache.clear(); currentRenderer?.close(); currentPfd?.close(); currentRenderer=null; currentPfd=null; currentFile=null }

    private fun getOrCreateRendererLocked(file: File): PdfRenderer {
        if (currentFile != file || currentRenderer == null) { currentRenderer?.close(); currentPfd?.close(); currentPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); currentRenderer = PdfRenderer(currentPfd!!); currentFile = file }
        return currentRenderer!!
    }
}
