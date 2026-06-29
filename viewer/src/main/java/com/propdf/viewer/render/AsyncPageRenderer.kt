package com.propdf.viewer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

/**
 * Lifecycle-safe PDF renderer that serializes PdfRenderer access while keeping
 * request scheduling asynchronous and priority-aware.
 *
 * PdfRenderer is not safe to access concurrently. A single render worker drains
 * a priority queue and a lock guards every renderer/page open/render/close
 * critical section. Stale queued requests are discarded when a document changes.
 *
 * Features:
 * - Thread-safe document lifecycle management
 * - Priority-based render queue (CRITICAL > HIGH > NORMAL > LOW)
 * - Bitmap pooling for memory efficiency
 * - Theme rendering (Night, Sepia, High Contrast)
 * - Thumbnail generation at reduced resolution
 * - Page size caching for layout calculations
 * - Automatic stale request cleanup on document change
 */
class AsyncPageRenderer(
    context: Context,
    private val cacheManager: PageCacheManager,
    maxConcurrentRenders: Int = 1
) {
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<RenderKey, Job>()
    private val renderQueue = PriorityBlockingQueue<QueuedRenderRequest>()
    private val sequence = AtomicLong(0L)
    private val generation = AtomicInteger(0)
    private val rendererLock = ReentrantLock()

    private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
    val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pageCount: Int = 0
    private var currentTheme = ViewerTheme.LIGHT
    private var nightModePaint: Paint? = null
    private var sepiaPaint: Paint? = null

    // Bitmap pool for reuse - prevents GC pressure
    private val bitmapPool = BitmapPool(maxSize = 4)

    private val pageSizes = mutableListOf<Pair<Int, Int>>()

    data class RenderRequest(
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val priority: RenderPriority = RenderPriority.NORMAL,
        val isThumbnail: Boolean = false
    )

    sealed class RenderState {
        object Idle : RenderState()
        data class Rendering(val pageIndex: Int, val isThumbnail: Boolean) : RenderState()
        data class Complete(val pageIndex: Int, val bitmap: Bitmap, val isThumbnail: Boolean) : RenderState()
        data class Error(val pageIndex: Int, val throwable: Throwable, val isThumbnail: Boolean) : RenderState()
    }

    init {
        startRenderWorker()
    }

    /**
     * Opens a PDF document for rendering.
     * @return Number of pages in the document
     */
    fun openDocument(file: File): Int = rendererLock.withLock {
        closeDocumentLocked()
        parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
        pageCount = pdfRenderer?.pageCount ?: 0

        // Cache page sizes for layout calculations
        pageSizes.clear()
        pdfRenderer?.let { renderer ->
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    pageSizes.add(page.width to page.height)
                }
            }
        }

        generation.incrementAndGet()
        pageCount
    }

    /**
     * Returns cached page sizes for layout calculations.
     */
    fun getPageSizes(): List<Pair<Int, Int>> = pageSizes.toList()

    /**
     * Closes the current document and cancels all pending renders.
     */
    fun closeDocument() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        renderQueue.clear()
        rendererLock.withLock {
            closeDocumentLocked()
            generation.incrementAndGet()
        }
        _renderState.value = RenderState.Idle
    }

    /**
     * Queues a page for rendering. Higher priority requests are processed first.
     */
    fun requestRender(request: RenderRequest) {
        if (request.pageIndex < 0 || request.width <= 0 || request.height <= 0) return

        val key = RenderKey(request.pageIndex, request.isThumbnail)
        activeJobs[key]?.cancel()
        renderQueue.removeIf { it.key == key }
        renderQueue.offer(
            QueuedRenderRequest(
                request = request,
                generation = generation.get(),
                sequence = sequence.incrementAndGet()
            )
        )
    }

    /**
     * Cancels rendering for a specific page.
     */
    fun cancelRender(pageIndex: Int) {
        RenderKey(pageIndex, false).also { key ->
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
        RenderKey(pageIndex, true).also { key ->
            activeJobs[key]?.cancel()
            activeJobs.remove(key)
        }
        renderQueue.removeIf { it.request.pageIndex == pageIndex }
    }

    /**
     * Renders a thumbnail synchronously for the thumbnail sidebar.
     * Uses bitmap pooling for memory efficiency.
     */
    fun renderThumbnail(pageIndex: Int, maxWidth: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= pageCount) return null

        return rendererLock.withLock {
            try {
                val renderer = pdfRenderer ?: return@withLock null
                val (pageWidth, pageHeight) = pageSizes.getOrNull(pageIndex) ?: (1 to 1)

                val scale = maxWidth.toFloat() / pageWidth.coerceAtLeast(1)
                val thumbWidth = maxWidth
                val thumbHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = bitmapPool.acquire(thumbWidth, thumbHeight)
                    ?: Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)

                bitmap.eraseColor(backgroundColorForTheme(currentTheme))
                val canvas = Canvas(bitmap)

                renderer.openPage(pageIndex).use { page ->
                    val renderRect = Rect(0, 0, thumbWidth, thumbHeight)
                    page.render(bitmap, renderRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }

                applyThemeFilter(canvas, thumbWidth, thumbHeight)
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Sets the current rendering theme (Light, Dark, Night, Sepia, High Contrast).
     */
    fun setTheme(theme: ViewerTheme) {
        currentTheme = theme
        when (theme) {
            ViewerTheme.NIGHT -> {
                nightModePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
                sepiaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            while (isActive) {
                val queued = withContext(Dispatchers.IO) {
                    renderQueue.poll(250L, TimeUnit.MILLISECONDS)
                } ?: continue

                if (queued.generation != generation.get()) continue

                val key = queued.key
                val job = launch {
                    try {
                        _renderState.value = RenderState.Rendering(
                            queued.request.pageIndex,
                            queued.request.isThumbnail
                        )
                        val bitmap = renderPageInternal(queued.request, queued.generation)
                        if (bitmap != null && isActive) {
                            if (queued.request.isThumbnail) {
                                cacheManager.putThumbnail(queued.request.pageIndex, bitmap)
                            } else {
                                cacheManager.putPage(queued.request.pageIndex, bitmap)
                            }
                            _renderState.value = RenderState.Complete(
                                queued.request.pageIndex,
                                bitmap,
                                queued.request.isThumbnail
                            )
                        } else if (bitmap != null && !bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _renderState.value = RenderState.Error(
                            queued.request.pageIndex,
                            e,
                            queued.request.isThumbnail
                        )
                    }
                }

                activeJobs[key] = job
                job.join()
                activeJobs.remove(key)
            }
        }
    }

    private suspend fun renderPageInternal(request: RenderRequest, requestGeneration: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            rendererLock.withLock {
                coroutineContext.ensureActive()
                if (requestGeneration != generation.get()) return@withContext null

                val renderer = pdfRenderer ?: return@withContext null
                if (request.pageIndex !in 0 until renderer.pageCount) return@withContext null

                renderer.openPage(request.pageIndex).use { page ->
                    val bitmap = bitmapPool.acquire(request.width, request.height)
                        ?: Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)

                    try {
                        bitmap.eraseColor(backgroundColorForTheme(currentTheme))
                        val canvas = Canvas(bitmap)
                        val pageWidth = page.width.toFloat().coerceAtLeast(1f)
                        val pageHeight = page.height.toFloat().coerceAtLeast(1f)
                        val scale = minOf(request.width / pageWidth, request.height / pageHeight)
                        val scaledWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
                        val scaledHeight = (pageHeight * scale).toInt().coerceAtLeast(1)
                        val offsetX = (request.width - scaledWidth) / 2
                        val offsetY = (request.height - scaledHeight) / 2
                        val destRect = Rect(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight)

                        coroutineContext.ensureActive()

                        page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val themePaint = when (currentTheme) {
                            ViewerTheme.NIGHT, ViewerTheme.HIGH_CONTRAST -> nightModePaint
                            ViewerTheme.SEPIA -> sepiaPaint
                            else -> null
                        }

                        if (themePaint != null) {
                            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), themePaint)
                        }

                        return@withContext bitmap
                    } catch (e: Exception) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        throw e
                    }
                }
            }
        }

    private fun backgroundColorForTheme(theme: ViewerTheme): Int = when (theme) {
        ViewerTheme.LIGHT -> Color.WHITE
        ViewerTheme.DARK -> Color.parseColor("#121212")
        ViewerTheme.NIGHT -> Color.parseColor("#000000")
        ViewerTheme.SEPIA -> Color.parseColor("#F4ECD8")
        ViewerTheme.HIGH_CONTRAST -> Color.BLACK
    }

    private fun applyThemeFilter(canvas: Canvas, width: Int, height: Int) {
        val paint = when (currentTheme) {
            ViewerTheme.NIGHT, ViewerTheme.HIGH_CONTRAST -> nightModePaint
            ViewerTheme.SEPIA -> sepiaPaint
            else -> null
        }
        paint?.let {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it)
        }
    }

    private fun closeDocumentLocked() {
        pdfRenderer?.close()
        pdfRenderer = null
        parcelFileDescriptor?.close()
        parcelFileDescriptor = null
        pageSizes.clear()
    }

    fun release() {
        closeDocument()
        renderScope.cancel()
    }

    // Inner classes
    private data class RenderKey(val pageIndex: Int, val isThumbnail: Boolean)

    private data class QueuedRenderRequest(
        val request: RenderRequest,
        val generation: Int,
        val sequence: Long
    ) : Comparable<QueuedRenderRequest> {
        val key = RenderKey(request.pageIndex, request.isThumbnail)

        override fun compareTo(other: QueuedRenderRequest): Int {
            val priorityCompare = other.request.priority.ordinal.compareTo(request.priority.ordinal)
            if (priorityCompare != 0) return priorityCompare
            return sequence.compareTo(other.sequence)
        }
    }

    /**
     * Simple bitmap pool for reuse to prevent GC pressure during rapid scrolling.
     */
    class BitmapPool(private val maxSize: Int) {
        private val pool = mutableListOf<Bitmap>()

        fun acquire(width: Int, height: Int): Bitmap? {
            synchronized(pool) {
                val iterator = pool.iterator()
                while (iterator.hasNext()) {
                    val bitmap = iterator.next()
                    if (!bitmap.isRecycled && bitmap.width == width && bitmap.height == height) {
                        iterator.remove()
                        return bitmap
                    }
                }
            }
            return null
        }

        fun release(bitmap: Bitmap) {
            if (bitmap.isRecycled) return
            synchronized(pool) {
                if (pool.size < maxSize) {
                    pool.add(bitmap)
                } else {
                    bitmap.recycle()
                }
            }
        }

        fun releaseSync(bitmap: Bitmap) {
            release(bitmap)
        }

        fun trim(percent: Float) {
            synchronized(pool) {
                val targetSize = (pool.size * (1 - percent)).toInt()
                while (pool.size > targetSize) {
                    pool.removeFirstOrNull()?.recycle()
                }
            }
        }

        fun clear() {
            synchronized(pool) {
                pool.forEach { it.recycle() }
                pool.clear()
            }
        }
    }
}
