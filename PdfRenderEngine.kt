package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Size
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Production-grade PDF render engine.
 * Thread-safe wrapper around Android PdfRenderer with bitmap pooling support.
 * Designed for 1000+ page PDFs with low memory footprint.
 */
class PdfRenderEngine(private val bitmapPool: BitmapPool) {

    private val lock = ReentrantReadWriteLock()
    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private val isOpen = AtomicBoolean(false)

    /** Page count; -1 if closed */
    val pageCount: Int
        get() = lock.read { pdfRenderer?.pageCount ?: -1 }

    /**
     * Open a PDF file for rendering.
     * Thread-safe. Closes previous document if any.
     */
    fun open(file: File): Boolean {
        close()
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            lock.write {
                parcelFileDescriptor = pfd
                pdfRenderer = renderer
                isOpen.set(true)
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Close document and release all native resources.
     * Idempotent and thread-safe.
     */
    fun close() {
        lock.write {
            isOpen.set(false)
            try { pdfRenderer?.close() } catch (_: Exception) {}
            try { parcelFileDescriptor?.close() } catch (_: Exception) {}
            pdfRenderer = null
            parcelFileDescriptor = null
        }
    }

    /**
     * Get page size in points (1/72 inch).
     * Returns null if document closed or page invalid.
     */
    fun getPageSize(pageIndex: Int): Size? = lock.read {
        if (!isOpen.get()) return@read null
        val renderer = pdfRenderer ?: return@read null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@read null
        try {
            renderer.openPage(pageIndex).use { page ->
                Size(page.width, page.height)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Render a page into a bitmap.
     * Uses pooled bitmap if available, otherwise creates new.
     * The returned bitmap MUST be released back to the pool via [BitmapPool.recycle].
     *
     * @param pageIndex Page index (0-based)
     * @param targetWidth Desired width in pixels
     * @param targetHeight Desired height in pixels (0 = auto from aspect ratio)
     * @return Rendered bitmap, or null on failure
     */
    fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int = 0): Bitmap? {
        if (targetWidth <= 0) return null
        return lock.read {
            if (!isOpen.get()) return@read null
            val renderer = pdfRenderer ?: return@read null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@read null

            try {
                renderer.openPage(pageIndex).use { page ->
                    val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                    val w = targetWidth
                    val h = if (targetHeight > 0) targetHeight else (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = bitmapPool.obtain(w, h)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Render a page at a specific zoom scale.
     * @param scale Scale factor (1.0 = original size)
     */
    fun renderPageScaled(pageIndex: Int, scale: Float): Bitmap? {
        val size = getPageSize(pageIndex) ?: return null
        val w = (size.width * scale).toInt().coerceAtLeast(1)
        val h = (size.height * scale).toInt().coerceAtLeast(1)
        return renderPage(pageIndex, w, h)
    }
}
