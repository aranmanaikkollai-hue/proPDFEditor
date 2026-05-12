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

class PdfRenderEngine(private val bitmapPool: BitmapPool) {

    private val lock = ReentrantReadWriteLock()
    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private val isOpen = AtomicBoolean(false)

    val pageCount: Int
        get() = lock.read { pdfRenderer?.pageCount ?: -1 }

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
        } catch (_: IOException) { false }
        catch (_: SecurityException) { false }
    }

    fun close() {
        lock.write {
            isOpen.set(false)
            try { pdfRenderer?.close() } catch (_: Exception) {}
            try { parcelFileDescriptor?.close() } catch (_: Exception) {}
            pdfRenderer = null
            parcelFileDescriptor = null
        }
    }

    fun getPageSize(pageIndex: Int): Size? = lock.read {
        if (!isOpen.get()) return@read null
        val renderer = pdfRenderer ?: return@read null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@read null
        try {
            renderer.openPage(pageIndex).use { page ->
                Size(page.width, page.height)
            }
        } catch (_: Exception) { null }
    }

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
            } catch (_: Exception) { null }
        }
    }

    fun renderPageScaled(pageIndex: Int, scale: Float): Bitmap? {
        val size = getPageSize(pageIndex) ?: return null
        val w = (size.width * scale).toInt().coerceAtLeast(1)
        val h = (size.height * scale).toInt().coerceAtLeast(1)
        return renderPage(pageIndex, w, h)
    }
}
