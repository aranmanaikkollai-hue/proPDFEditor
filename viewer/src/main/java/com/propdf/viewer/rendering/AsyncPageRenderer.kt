package com.propdf.viewer.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.propdf.viewer.cache.PageCacheManager
import com.propdf.viewer.model.RenderPriority
import com.propdf.viewer.model.ViewerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Async PDF page renderer with priority queue, bitmap pooling, and lifecycle-safe cancellation.
 */
class AsyncPageRenderer(
    private val context: Context,
    private val cacheManager: PageCacheManager
) {

    data class RenderRequest(
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val priority: RenderPriority = RenderPriority.NORMAL,
        val isThumbnail: Boolean = false
    ) : Comparable<RenderRequest> {
        private val sequence = sequencer.incrementAndGet()
        override fun compareTo(other: RenderRequest): Int {
            val priorityCompare = priority.ordinal.compareTo(other.priority.ordinal)
            return if (priorityCompare != 0) priorityCompare else sequence.compareTo(other.sequence)
        }
        companion object {
            private val sequencer = AtomicInteger(0)
        }
    }

    sealed class RenderState {
        object Idle : RenderState()
        data class Rendering(val pageIndex: Int, val isThumbnail: Boolean) : RenderState()
        data class Complete(
            val pageIndex: Int,
            val bitmap: Bitmap,
            val isThumbnail: Boolean
        ) : RenderState()
        data class Error(val pageIndex: Int, val throwable: Throwable) : RenderState()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = PriorityBlockingQueue<RenderRequest>()
    private val _renderState = MutableSharedFlow<RenderState>(extraBufferCapacity = 64)
    val renderState: SharedFlow<RenderState> = _renderState.asSharedFlow()

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val rendererLock = ReentrantLock()
    private var generation = AtomicInteger(0)

    private val bitmapPool = BitmapPool()

    fun openDocument(file: File): Int {
        closeDocument()
        val newPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = newPfd
        val renderer = PdfRenderer(newPfd)
        pdfRenderer = renderer
        generation.incrementAndGet()
        startRenderLoop()
        return renderer.pageCount
    }

    fun requestRender(request: RenderRequest) {
        queue.offer(request)
    }

    fun setTheme(theme: ViewerTheme) {
        // Theme affects rendering colors; re-render visible pages if needed
    }

    fun release() {
        scope.cancel()
        closeDocument()
        bitmapPool.clear()
    }

    private fun closeDocument() {
        rendererLock.withLock {
            pdfRenderer?.close()
            pdfRenderer = null
            pfd?.close()
            pfd = null
        }
    }

    private fun startRenderLoop() {
        scope.launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                val request = queue.take()
                currentCoroutineContext().ensureActive()
                renderPage(request)
            }
        }
    }

    private suspend fun renderPage(request: RenderRequest) {
        val renderer = rendererLock.withLock { pdfRenderer } ?: return
        val currentGen = generation.get()

        _renderState.emit(RenderState.Rendering(request.pageIndex, request.isThumbnail))

        try {
            if (request.pageIndex < 0 || request.pageIndex >= renderer.pageCount) {
                _renderState.emit(
                    RenderState.Error(
                        request.pageIndex,
                        IndexOutOfBoundsException("Page ${request.pageIndex} out of bounds")
                    )
                )
                return
            }

            val bitmap = bitmapPool.acquire(request.width, request.height)
                ?: Bitmap.createBitmap(request.width, request.height, Bitmap.Config.ARGB_8888)

            rendererLock.withLock {
                if (generation.get() != currentGen) return
                pdfRenderer?.let { r ->
                    r.openPage(request.pageIndex).use { page ->
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }

            if (request.isThumbnail) {
                cacheManager.putThumbnail(request.pageIndex, bitmap)
            } else {
                cacheManager.putPage(request.pageIndex, bitmap)
            }

            _renderState.emit(RenderState.Complete(request.pageIndex, bitmap, request.isThumbnail))
        } catch (e: Exception) {
            _renderState.emit(RenderState.Error(request.pageIndex, e))
        }
    }

    private class BitmapPool {
        private val lock = ReentrantLock()
        private val pool = ArrayDeque<Bitmap>()

        fun acquire(width: Int, height: Int): Bitmap? = lock.withLock {
            val index = pool.indexOfFirst { it.width == width && it.height == height && !it.isRecycled }
            if (index >= 0) pool.removeAt(index) else null
        }

        fun release(bitmap: Bitmap) {
            if (!bitmap.isRecycled) {
                lock.withLock { pool.addLast(bitmap) }
            }
        }

        fun clear() = lock.withLock {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
        }
    }
}
