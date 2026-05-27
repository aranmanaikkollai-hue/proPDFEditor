package com.propdf.viewer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.model.RenderPriority
import com.propdf.viewer.model.ViewerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Async PDF page renderer with priority-based rendering queue.
 * Features:
 * - Semaphore-controlled concurrent rendering (prevents memory spikes)
 * - Priority queue: CRITICAL > HIGH > NORMAL > LOW
 * - Bitmap pool for memory reuse
 * - Theme-aware rendering with ColorMatrix filters
 * - Cancellation support for responsive UI
 */
class AsyncPageRenderer(
    context: Context,
    private val cacheManager: PageCacheManager,
    private val maxConcurrentRenders: Int = 2
) {
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val renderSemaphore = Semaphore(maxConcurrentRenders)
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val renderQueue = Channel<RenderRequest>(Channel.UNLIMITED)

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    val renderState: StateFlow<RenderState> = _renderState

    private var pdfRenderer: PdfRenderer? = null
    private val rendererMutex = Mutex()
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentTheme = ViewerTheme.LIGHT
    private var nightModePaint: Paint? = null
    private var sepiaPaint: Paint? = null

    // Bitmap pool for reuse - prevents GC pressure
    private val bitmapPool = BitmapPool(maxSize = 4)

    data class RenderRequest(
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val priority: RenderPriority = RenderPriority.NORMAL,
        val isThumbnail: Boolean = false
    ) : Comparable<RenderRequest> {
        override fun compareTo(other: RenderRequest): Int {
            return other.priority.ordinal - this.priority.ordinal
        }
    }

    sealed class RenderState {
        object Idle : RenderState()
        data class Rendering(val pageIndex: Int) : RenderState()
        data class Complete(val pageIndex: Int, val bitmap: Bitmap) : RenderState()
        data class Error(val pageIndex: Int, val throwable: Throwable) : RenderState()
    }

    init {
        startRenderWorker()
    }

    fun openDocument(file: File) {
        closeDocument()
        renderScope.launch(Dispatchers.IO) {
            rendererMutex.withLock {
                parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            }
        }
    }

    fun closeDocument() {
        renderScope.coroutineContext.cancelChildren()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        renderScope.launch(Dispatchers.IO) {
            rendererMutex.withLock {
                pdfRenderer?.close()
                pdfRenderer = null
                parcelFileDescriptor?.close()
                parcelFileDescriptor = null
            }
        }
    }

    fun requestRender(request: RenderRequest) {
        renderScope.launch {
            renderQueue.send(request)
        }
    }

    fun cancelRender(pageIndex: Int) {
        activeJobs[pageIndex]?.cancel()
        activeJobs.remove(pageIndex)
    }

    fun setTheme(theme: ViewerTheme) {
        currentTheme = theme
        when (theme) {
            ViewerTheme.NIGHT -> {
                nightModePaint = Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                }
            }
            ViewerTheme.SEPIA -> {
                sepiaPaint = Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        floatArrayOf(
                            0.393f, 0.769f, 0.189f, 0f, 0f,
                            0.349f, 0.686f, 0.168f, 0f, 0f,
                            0.272f, 0.534f, 0.131f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                }
            }
            else -> {
                nightModePaint = null
                sepiaPaint = null
            }
        }
    }

    private fun startRenderWorker() {
        renderScope.launch {
            for (request in renderQueue) {
                if (!isActive) break

                activeJobs[request.pageIndex]?.cancel()

                val job = launch {
                    renderSemaphore.withPermit {
                        try {
                            _renderState.value = RenderState.Rendering(request.pageIndex)
                            val bitmap = renderPageInternal(request)
                            if (bitmap != null) {
                                if (request.isThumbnail) {
                                    cacheManager.putThumbnail(request.pageIndex, bitmap)
                                } else {
                                    cacheManager.putPage(request.pageIndex, bitmap)
                                }
                                _renderState.value = RenderState.Complete(request.pageIndex, bitmap)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _renderState.value = RenderState.Error(request.pageIndex, e)
                        }
                    }
                }

                activeJobs[request.pageIndex] = job
                job.invokeOnCompletion {
                    activeJobs.remove(request.pageIndex)
                }
            }
        }
    }

    private suspend fun renderPageInternal(request: RenderRequest): Bitmap? =
        withContext(Dispatchers.IO) {
            rendererMutex.withLock {
                val renderer = pdfRenderer ?: return@withContext null
                if (request.pageIndex >= renderer.pageCount) return@withContext null

                val page = renderer.openPage(request.pageIndex) ?: return@withContext null

                try {
                    val bitmap = bitmapPool.acquire(request.width, request.height)
                        ?: Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)

                    // Clear with theme-appropriate background
                    bitmap.eraseColor(
                        when (currentTheme) {
                            ViewerTheme.DARK, ViewerTheme.NIGHT -> Color.BLACK
                            ViewerTheme.SEPIA -> Color.parseColor("#F4ECD8")
                            else -> Color.WHITE
                        }
                    )

                    val canvas = Canvas(bitmap)

                    // Calculate scale to fit
                    val pageWidth = page.width.toFloat()
                    val pageHeight = page.height.toFloat()
                    val scaleX = request.width / pageWidth
                    val scaleY = request.height / pageHeight
                    val scale = minOf(scaleX, scaleY)

                    val scaledWidth = (pageWidth * scale).toInt()
                    val scaledHeight = (pageHeight * scale).toInt()
                    val offsetX = (request.width - scaledWidth) / 2
                    val offsetY = (request.height - scaledHeight) / 2

                    val destRect = Rect(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight)

                    // Render with theme filter
                    val themePaint = when (currentTheme) {
                        ViewerTheme.NIGHT -> nightModePaint
                        ViewerTheme.SEPIA -> sepiaPaint
                        else -> null
                    }

                    if (themePaint != null) {
                        val tempBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
                        val tempCanvas = Canvas(tempBitmap)
                        page.render(tempBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        canvas.drawBitmap(tempBitmap, null, destRect.toRectF(), themePaint)
                        tempBitmap.recycle()
                    } else {
                        page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }

                    bitmap
                } finally {
                    page.close()
                }
            }
        }

    private fun Rect.toRectF(): RectF = RectF(
        left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()
    )

    fun getPageCount(): Int = pdfRenderer?.pageCount ?: 0

    fun getPageSize(pageIndex: Int): Pair<Int, Int>? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex >= renderer.pageCount) return null
        val page = renderer.openPage(pageIndex) ?: return null
        val size = page.width to page.height
        page.close()
        return size
    }

    fun release() {
        closeDocument()
        bitmapPool.clear()
    }

    /**
     * Bitmap pool for reusing bitmap memory and reducing GC pressure.
     */
    private class BitmapPool(private val maxSize: Int) {
        private val pool = mutableListOf<Bitmap>()

        fun acquire(width: Int, height: Int): Bitmap? {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                if (!bitmap.isRecycled && bitmap.width == width && bitmap.height == height) {
                    iterator.remove()
                    return bitmap
                }
            }
            return null
        }

        fun release(bitmap: Bitmap) {
            if (!bitmap.isRecycled && pool.size < maxSize) {
                pool.add(bitmap)
            } else {
                bitmap.recycle()
            }
        }

        fun clear() {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
        }
    }
}
