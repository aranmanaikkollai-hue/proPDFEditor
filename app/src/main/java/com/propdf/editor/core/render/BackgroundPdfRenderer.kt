package com.propdf.editor.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.core.pool.BitmapPool
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade background PDF renderer with:
 * - Incremental loading (only render visible + nearby pages)
 * - Lazy loading (render on-demand, cache results)
 * - Background rendering via optimized coroutine channels
 * - Concurrent page rendering limited by Semaphore (prevents ANR/OOM)
 * - Automatic quality reduction under memory pressure
 * - GPU-friendly ARGB_8888 with RGB_565 fallback
 */
class BackgroundPdfRenderer(
    private val file: File,
    private val cache: LruBitmapCache,
    private val pool: BitmapPool
) {
    companion object {
        private const val TAG = "BgPdfRenderer"
        private const val MAX_CONCURRENT_RENDERS = 2 // Prevent ANR from overloading GPU
        private const val PRELOAD_RANGE = 2 // Pages ahead/behind to preload
        private const val MAX_RENDER_DIMENSION = 4096 // Prevent GPU texture limit crashes
    }

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderSemaphore = Semaphore(MAX_CONCURRENT_RENDERS)
    private val pageMutexes = ConcurrentHashMap<Int, Mutex>()
    private val closed = AtomicBoolean(false)

    private val renderScope = CoroutineScope(
        SupervisorJob() + ThreadPoolManager.RenderDispatcher + CoroutineName("pdf-render")
    )

    // Channel for ordered background render requests (backpressure via buffer)
    private val renderQueue = Channel<RenderRequest>(Channel.BUFFERED)

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    init {
        openRenderer()
        startRenderWorker()
    }

    // ─── Public API ────────────────────────────────────────────────

    /**
     * Get rendered page bitmap. Checks cache first, renders if needed.
     * This is the primary lazy-loading entry point.
     */
    suspend fun getPage(
        pageIndex: Int,
        targetWidth: Int,
        quality: RenderQuality = RenderQuality.NORMAL
    ): Bitmap? {
        if (closed.get() || pageIndex < 0 || pageIndex >= pageCount) return null

        val cacheKey = "${file.name}_$pageIndex" + "_${targetWidth}_$quality"

        // Check cache first
        cache.get(cacheKey)?.let { return it }

        // Acquire render permit (prevents too many concurrent renders)
        return renderSemaphore.withPermit {
            // Double-check after acquiring permit
            cache.get(cacheKey)?.let { return@withPermit it }

            renderPage(pageIndex, targetWidth, quality)?.also { bmp ->
                cache.put(cacheKey, bmp)
            }
        }
    }

    /**
     * Incremental preload: queue nearby pages for background rendering.
     * Non-blocking — returns immediately.
     */
    fun preloadPages(anchorPage: Int, targetWidth: Int) {
        if (closed.get()) return

        val start = (anchorPage - PRELOAD_RANGE).coerceAtLeast(0)
        val end = (anchorPage + PRELOAD_RANGE).coerceAtMost(pageCount - 1)

        for (i in start..end) {
            if (i == anchorPage) continue // Already rendering
            val request = RenderRequest(i, targetWidth, RenderQuality.LOW)
            renderQueue.trySend(request) // Non-blocking send
        }
    }

    /**
     * Cancel all pending background renders.
     */
    fun cancelPending() {
        renderQueue.cancel()
    }

    /**
     * Close renderer and release all resources.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            renderQueue.close()
            renderScope.cancel()
            renderer?.close()
            pfd?.close()
        }
    }

    // ─── Internal ──────────────────────────────────────────────────

    private fun openRenderer() {
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open PDF renderer", e)
        }
    }

    private fun startRenderWorker() {
        renderScope.launch {
            for (request in renderQueue) {
                if (closed.get()) break
                try {
                    val cacheKey = "${file.name}_${request.pageIndex}_${request.targetWidth}_${request.quality}"
                    // Skip if already cached
                    if (cache.get(cacheKey) != null) continue

                    renderPage(request.pageIndex, request.targetWidth, request.quality)?.let {
                        cache.put(cacheKey, it)
                    }
                } catch (e: CancellationException) {
                    // Normal cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "Background render failed for page ${request.pageIndex}", e)
                }
            }
        }
    }

    private suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        quality: RenderQuality
    ): Bitmap? = withContext(ThreadPoolManager.RenderDispatcher) {
        val mutex = pageMutexes.getOrPut(pageIndex) { Mutex() }
        mutex.withLock {
            val renderer = this@BackgroundPdfRenderer.renderer ?: return@withContext null

            try {
                val page = renderer.openPage(pageIndex)

                // Calculate dimensions with quality modifier
                val scale = (targetWidth.toFloat() / page.width) * quality.scaleFactor
                val bmpW = (page.width * scale).toInt().coerceIn(1, MAX_RENDER_DIMENSION)
                val bmpH = (page.height * scale).toInt().coerceIn(1, MAX_RENDER_DIMENSION)

                // Try pooled bitmap first
                val config = if (quality == RenderQuality.LOW) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                var bitmap = pool.get(bmpW, bmpH, config)

                if (bitmap == null) {
                    // Fallback: allocate new
                    bitmap = try {
                        Bitmap.createBitmap(bmpW, bmpH, config)
                    } catch (oom: OutOfMemoryError) {
                        // Emergency: try RGB_565
                        try {
                            Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
                        } catch (e: OutOfMemoryError) {
                            Log.e(TAG, "Fatal OOM rendering page $pageIndex")
                            return@withContext null
                        }
                    }
                }

                val safeBitmap = bitmap ?: return@withContext null

                // Clear and render
                safeBitmap.eraseColor(Color.WHITE)
                page.render(safeBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                safeBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Render error page $pageIndex", e)
                null
            }
        }
    }

    enum class RenderQuality(val scaleFactor: Float) {
        LOW(0.5f),      // Thumbnails, background preload
        NORMAL(1.0f),   // Standard viewing
        HIGH(1.5f)      // Zoomed in
    }

    private data class RenderRequest(
        val pageIndex: Int,
        val targetWidth: Int,
        val quality: RenderQuality
    )
}
