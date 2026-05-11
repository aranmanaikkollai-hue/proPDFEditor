package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * RecyclerView adapter for PDF pages with viewport-based lazy rendering.
 * Only renders visible pages + prefetch window.
 * Supports pinch zoom via matrix transformation.
 */
class PdfPageAdapter(
    private val renderEngine: PdfRenderEngine,
    private val bitmapPool: BitmapPool,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var zoomScale: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.5f, 5.0f)
            if (field != clamped) {
                field = clamped
                // Invalidate all visible holders for re-render at new zoom
                notifyItemRangeChanged(0, itemCount, PAYLOAD_ZOOM)
            }
        }

    var isDarkTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_THEME)
            }
        }

    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private val placeholderColor = android.graphics.Color.parseColor("#1A1A1A")
    private val placeholderColorLight = android.graphics.Color.parseColor("#F0F0F0")

    override fun getItemCount(): Int = renderEngine.pageCount.coerceAtLeast(0)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ZOOM) || payloads.contains(PAYLOAD_THEME)) {
            holder.updateZoomOrTheme(position)
        } else {
            holder.bind(position)
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
    }

    fun cancelAllJobs() {
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.pageImage)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.pageProgress)
        private val pageNumberLabel: TextView = itemView.findViewById(R.id.pageNumber)
        private var currentBitmap: Bitmap? = null
        private var currentPage: Int = -1

        fun bind(pageIndex: Int) {
            recycle()
            currentPage = pageIndex
            pageNumberLabel.text = "${pageIndex + 1}"
            progressBar.visibility = View.VISIBLE
            imageView.setImageDrawable(ColorDrawable(if (isDarkTheme) placeholderColor else placeholderColorLight))

            val job = scope.launch(Dispatchers.IO) {
                val pageSize = renderEngine.getPageSize(pageIndex)
                if (pageSize == null) {
                    withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
                    return@launch
                }

                val containerWidth = itemView.width.coerceAtLeast(100)
                val baseScale = containerWidth.toFloat() / pageSize.width.coerceAtLeast(1)
                val finalScale = baseScale * zoomScale
                val w = (pageSize.width * finalScale).toInt().coerceAtLeast(1)
                val h = (pageSize.height * finalScale).toInt().coerceAtLeast(1)

                val bitmap = renderEngine.renderPage(pageIndex, w, h)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (bitmap != null && !bitmap.isRecycled) {
                        currentBitmap = bitmap
                        imageView.setImageBitmap(bitmap)
                        applyNightModeIfNeeded(bitmap)
                    }
                }
            }
            renderJobs[pageIndex] = job
            job.invokeOnCompletion { renderJobs.remove(pageIndex) }
        }

        fun updateZoomOrTheme(pageIndex: Int) {
            currentBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    if (imageView.drawable == null || payloadsRequireRebind()) {
                        bind(pageIndex)
                    } else {
                        applyNightModeIfNeeded(bmp)
                    }
                }
            }
        }

        private fun payloadsRequireRebind(): Boolean {
            // If zoom changed significantly, we need to re-render
            return true // Simplified: always rebind on zoom change for quality
        }

        private fun applyNightModeIfNeeded(bitmap: Bitmap) {
            if (!isDarkTheme) {
                imageView.setImageBitmap(bitmap)
                return
            }
            // Apply night mode via ColorMatrix on ImageView instead of creating new bitmap
            val matrix = android.graphics.ColorMatrix().apply {
                set(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            imageView.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }

        fun recycle() {
            renderJobs[currentPage]?.cancel()
            renderJobs.remove(currentPage)
            currentBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    imageView.setImageDrawable(null)
                    bitmapPool.recycle(bmp)
                }
            }
            currentBitmap = null
            imageView.colorFilter = null
            currentPage = -1
        }
    }

    companion object {
        const val PAYLOAD_ZOOM = "zoom"
        const val PAYLOAD_THEME = "theme"
    }
}
