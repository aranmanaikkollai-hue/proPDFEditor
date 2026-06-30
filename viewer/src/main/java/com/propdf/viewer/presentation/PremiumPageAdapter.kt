package com.propdf.viewer.presentation

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.annotation.render.UnifiedAnnotationRenderer
import com.propdf.viewer.model.ViewerTheme
import com.propdf.viewer.ui.PremiumPageView
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
    private var currentTool: AnnotationType? = null
    private val annotationRenderer = UnifiedAnnotationRenderer()

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

    fun setTool(tool: AnnotationType?) {
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
            zoomLevels.getOrDefault(position, 1.0f),
            annotationManager,
            annotationRenderer
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

        private val pageView: PremiumPageView = itemView.findViewById(R.id.pageView)

        fun bind(
            pageIndex: Int,
            bitmap: Bitmap?,
            theme: ViewerTheme,
            zoom: Float,
            annotationManager: AnnotationManager,
            annotationRenderer: UnifiedAnnotationRenderer
        ) {
            pageView.pageIndex = pageIndex
            pageView.annotationManager = annotationManager
            pageView.annotationRenderer = annotationRenderer
            updateBitmap(bitmap)
            updateTheme(theme)
            pageView.setZoom(zoom)

            pageView.setGestureListener(object : PremiumPageView.PageGestureListener {
                override fun onTap(x: Float, y: Float) = onPageTap(x, y)
                override fun onDoubleTap(x: Float, y: Float) = onPageTap(x, y)
                override fun onLongPress(x: Float, y: Float) = Unit
                override fun onScaleChanged(scale: Float) = onPageScale(scale)
            })
        }

        fun updateBitmap(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) {
                pageView.visibility = View.VISIBLE
                pageView.setPageBitmap(bitmap)
            } else {
                pageView.visibility = View.GONE
            }
        }

        fun updateTheme(theme: ViewerTheme) {
            pageView.setTheme(theme)
        }

        fun recycle() {
            pageView.setGestureListener(null)
        }
    }

    companion object {
        private const val PAYLOAD_BITMAP = "bitmap"
        private const val PAYLOAD_THEME = "theme"
    }
}
