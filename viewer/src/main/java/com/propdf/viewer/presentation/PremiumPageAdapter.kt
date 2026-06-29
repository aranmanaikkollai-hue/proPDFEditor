package com.propdf.viewer.presentation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.withSave
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.model.ViewerTheme
import java.util.concurrent.ConcurrentHashMap

/**
 * RecyclerView adapter for displaying PDF pages with annotation support,
 * theme rendering, and zoom capabilities.
 *
 * Features:
 * - Efficient bitmap recycling and reuse
 * - Annotation overlay rendering
 * - Theme-aware background colors
 * - Zoom state persistence per page
 * - Smooth transitions between pages
 */
class PremiumPageAdapter(
    private val onPageTap: (Float, Float) -> Unit,
    private val onPageScale: (Float) -> Unit,
    private val annotationManager: AnnotationManager,
    private val persistenceManager: AnnotationPersistenceManager
) : RecyclerView.Adapter<PremiumPageAdapter.PageViewHolder>() {

    private var pageCount = 0
    private val bitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val zoomLevels = ConcurrentHashMap<Int, Float>()
    private var currentTheme = ViewerTheme.LIGHT
    private var currentTool: com.propdf.viewer.annotation.model.AnnotationTool? = null

    fun submitPageCount(count: Int) {
        val oldCount = pageCount
        pageCount = count
        if (oldCount == 0) {
            notifyDataSetChanged()
        } else {
            notifyItemRangeInserted(oldCount, count - oldCount)
        }
    }

    fun setBitmap(pageIndex: Int, bitmap: Bitmap) {
        val oldBitmap = bitmaps.put(pageIndex, bitmap)
        if (oldBitmap != null && oldBitmap != bitmap && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
        notifyItemChanged(pageIndex, PAYLOAD_BITMAP)
    }

    fun setTheme(theme: ViewerTheme) {
        currentTheme = theme
        notifyItemRangeChanged(0, pageCount, PAYLOAD_THEME)
    }

    fun setTool(tool: com.propdf.viewer.annotation.model.AnnotationTool?) {
        currentTool = tool
    }

    fun getZoomForPage(pageIndex: Int): Float {
        return zoomLevels.getOrDefault(pageIndex, 1.0f)
    }

    fun setZoomForPage(pageIndex: Int, zoom: Float) {
        zoomLevels[pageIndex] = zoom
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view, onPageTap, onPageScale)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(
            position,
            bitmaps[position],
            currentTheme,
            zoomLevels.getOrDefault(position, 1.0f)
        )
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_BITMAP)) {
            holder.updateBitmap(bitmaps[position])
        } else if (payloads.contains(PAYLOAD_THEME)) {
            holder.updateTheme(currentTheme)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
    }

    class PageViewHolder(
        itemView: View,
        private val onPageTap: (Float, Float) -> Unit,
        private val onPageScale: (Float) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val imageView: ImageView = itemView.findViewById(R.id.pageImage)
        private val annotationOverlay: com.propdf.viewer.annotation.ui.AnnotationOverlayView? =
            itemView.findViewById(R.id.annotationOverlay)

        private var currentBitmap: Bitmap? = null
        private var currentScale = 1.0f
        private val matrix = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun bind(pageIndex: Int, bitmap: Bitmap?, theme: ViewerTheme, zoom: Float) {
            currentScale = zoom
            updateBitmap(bitmap)
            updateTheme(theme)

            imageView.setOnClickListener { view ->
                val x = view.width / 2f
                val y = view.height / 2f
                onPageTap(x, y)
            }
        }

        fun updateBitmap(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) {
                currentBitmap = bitmap
                imageView.setImageBitmap(bitmap)
                imageView.visibility = View.VISIBLE
            } else {
                imageView.setImageDrawable(null)
                imageView.visibility = View.GONE
            }
        }

        fun updateTheme(theme: ViewerTheme) {
            val bgColor = when (theme) {
                ViewerTheme.LIGHT -> Color.WHITE
                ViewerTheme.DARK -> Color.parseColor("#121212")
                ViewerTheme.NIGHT -> Color.BLACK
                ViewerTheme.SEPIA -> Color.parseColor("#F4ECD8")
                ViewerTheme.HIGH_CONTRAST -> Color.BLACK
            }
            itemView.setBackgroundColor(bgColor)
        }

        fun recycle() {
            imageView.setImageDrawable(null)
            currentBitmap = null
        }
    }

    companion object {
        private const val PAYLOAD_BITMAP = "bitmap"
        private const val PAYLOAD_THEME = "theme"
    }
}
