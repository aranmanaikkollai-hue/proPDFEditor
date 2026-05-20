package com.propdf.viewer.presentation

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.annotation.ui.AnnotatedPageView
import com.propdf.viewer.gesture.UnifiedGestureCoordinator
import com.propdf.viewer.model.ViewerTheme
import com.propdf.viewer.render.RenderScheduler
import com.propdf.viewer.ui.PremiumPageView

/**
 * Premium page adapter for ViewPager2 with async rendering and annotation support.
 * Handles page bitmap loading, theme application, zoom state, and per-page
 * annotation lifecycle via AnnotatedPageView coordinators.
 */
class PremiumPageAdapter(
    private val onPageTap: (Float, Float) -> Unit,
    private val onPageScale: (Float) -> Unit,
    private val annotationManager: AnnotationManager,
    private val persistenceManager: AnnotationPersistenceManager
) : RecyclerView.Adapter<PremiumPageAdapter.PageViewHolder>() {

    private var pageCount = 0
    private var currentTheme = ViewerTheme.LIGHT
    private val pageBitmaps = mutableMapOf<Int, Bitmap>()
    private var pdfUri: Uri? = null
    private var currentTool: UnifiedGestureCoordinator.ToolMode = UnifiedGestureCoordinator.ToolMode.NONE
    private var currentColor: Int = android.graphics.Color.YELLOW
    private var currentStrokeWidth: Float = 3f

    fun submitPageCount(count: Int) {
        val oldCount = pageCount
        pageCount = count
        if (oldCount == 0) {
            notifyDataSetChanged()
        } else {
            notifyItemRangeChanged(0, count)
        }
    }

    fun setBitmap(pageIndex: Int, bitmap: Bitmap) {
        pageBitmaps[pageIndex] = bitmap
        notifyItemChanged(pageIndex, PAYLOAD_BITMAP)
    }

    fun setTheme(theme: ViewerTheme) {
        currentTheme = theme
        notifyItemRangeChanged(0, pageCount, PAYLOAD_THEME)
    }

    fun setPdfUri(uri: Uri) {
        pdfUri = uri
        notifyItemRangeChanged(0, pageCount, PAYLOAD_ANNOTATIONS)
    }

    fun setTool(tool: UnifiedGestureCoordinator.ToolMode) {
        currentTool = tool
        notifyItemRangeChanged(0, pageCount, PAYLOAD_TOOL)
    }

    fun setColor(color: Int) {
        currentColor = color
        notifyItemRangeChanged(0, pageCount, PAYLOAD_COLOR)
    }

    fun setStrokeWidth(width: Float) {
        currentStrokeWidth = width
        notifyItemRangeChanged(0, pageCount, PAYLOAD_STROKE)
    }

    fun undo() {
        annotationManager.undo()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ANNOTATIONS)
    }

    fun redo() {
        annotationManager.redo()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ANNOTATIONS)
    }

    fun clearPage(pageIndex: Int) {
        annotationManager.clearPage(pageIndex)
        notifyItemChanged(pageIndex, PAYLOAD_ANNOTATIONS)
    }

    fun clearAll() {
        annotationManager.clearAll()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ANNOTATIONS)
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view, onPageTap, onPageScale, annotationManager, persistenceManager)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val bitmap = pageBitmaps[position]
        holder.bind(bitmap, currentTheme, position, pdfUri, currentTool, currentColor, currentStrokeWidth)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when {
            payloads.contains(PAYLOAD_BITMAP) -> holder.setBitmap(pageBitmaps[position])
            payloads.contains(PAYLOAD_THEME) -> holder.applyTheme(currentTheme)
            payloads.contains(PAYLOAD_ANNOTATIONS) -> holder.loadAnnotations(pdfUri)
            payloads.contains(PAYLOAD_TOOL) -> holder.setTool(currentTool)
            payloads.contains(PAYLOAD_COLOR) -> holder.setColor(currentColor)
            payloads.contains(PAYLOAD_STROKE) -> holder.setStrokeWidth(currentStrokeWidth)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    class PageViewHolder(
        itemView: View,
        private val onPageTap: (Float, Float) -> Unit,
        private val onPageScale: (Float) -> Unit,
        annotationManager: AnnotationManager,
        persistenceManager: AnnotationPersistenceManager
    ) : RecyclerView.ViewHolder(itemView) {

        private val pageView: PremiumPageView = itemView.findViewById(R.id.pageView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val annotatedPageView: AnnotatedPageView

        init {
            pageView.setGestureListener(object : PremiumPageView.PageGestureListener {
                override fun onTap(x: Float, y: Float) {
                    onPageTap(x, y)
                }
                override fun onDoubleTap(x: Float, y: Float) {
                    val targetZoom = if (pageView.getCurrentScale() > 1.5f) 1.0f else 2.5f
                    pageView.setZoom(targetZoom, animate = true)
                }
                override fun onLongPress(x: Float, y: Float) {}
                override fun onScaleChanged(scale: Float) {
                    onPageScale(scale)
                }
            })

            val gestureCoordinator = UnifiedGestureCoordinator(
                annotationManager,
                RenderScheduler(),
                pageView.pdfCoordinateSpace
            )
            annotatedPageView = AnnotatedPageView(
                pageView = pageView,
                annotationManager = annotationManager,
                persistenceManager = persistenceManager,
                gestureCoordinator = gestureCoordinator,
                renderScheduler = RenderScheduler()
            )
        }

        fun bind(
            bitmap: Bitmap?,
            theme: ViewerTheme,
            position: Int,
            uri: Uri?,
            tool: UnifiedGestureCoordinator.ToolMode,
            color: Int,
            strokeWidth: Float
        ) {
            setBitmap(bitmap)
            applyTheme(theme)
            annotatedPageView.setPageIndex(position)
            annotatedPageView.setTool(tool)
            annotatedPageView.setColor(color)
            annotatedPageView.setStrokeWidth(strokeWidth)
            if (uri != null) {
                annotatedPageView.loadAnnotations(uri)
            }
        }

        fun setBitmap(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) {
                pageView.setPageBitmap(bitmap)
                pageView.isVisible = true
                progressBar.isVisible = false
            } else {
                pageView.isVisible = false
                progressBar.isVisible = true
            }
        }

        fun applyTheme(theme: ViewerTheme) {
            pageView.setTheme(theme)
            val backgroundColor = when (theme) {
                ViewerTheme.LIGHT -> android.R.color.white
                ViewerTheme.DARK -> android.R.color.black
                ViewerTheme.NIGHT -> R.color.night_background
                ViewerTheme.SEPIA -> R.color.sepia_background
                ViewerTheme.HIGH_CONTRAST -> android.R.color.black
            }
            itemView.setBackgroundResource(backgroundColor)
        }

        fun loadAnnotations(uri: Uri?) {
            uri?.let { annotatedPageView.loadAnnotations(it) }
        }

        fun setTool(tool: UnifiedGestureCoordinator.ToolMode) {
            annotatedPageView.setTool(tool)
        }

        fun setColor(color: Int) {
            annotatedPageView.setColor(color)
        }

        fun setStrokeWidth(width: Float) {
            annotatedPageView.setStrokeWidth(width)
        }

        fun recycle() {
            annotatedPageView.onDestroy()
        }
    }

    companion object {
        private const val PAYLOAD_BITMAP = "bitmap"
        private const val PAYLOAD_THEME = "theme"
        private const val PAYLOAD_ANNOTATIONS = "annotations"
        private const val PAYLOAD_TOOL = "tool"
        private const val PAYLOAD_COLOR = "color"
        private const val PAYLOAD_STROKE = "stroke"
    }
}
