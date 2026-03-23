package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorMatrix
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * PdfPageAdapter — RecyclerView adapter for lazy PDF rendering.
 *
 * Design goals:
 * - Only renders the page when the ViewHolder becomes visible (lazy)
 * - Immediately cancels/recycles when ViewHolder scrolls off screen
 * - Uses Bitmap.Config.RGB_565 (~50% less RAM than ARGB_8888)
 * - Renders at 80% of screen width for a good quality/memory balance
 * - Night mode applied via ColorFilter — no extra bitmap allocation
 * - Each page gets its own AnnotatedPageView (correct annotation coordinates)
 * - Thread-safe: rendering on Dispatchers.Default, UI updates on Main
 */
class PdfPageAdapter(
    private val renderer   : PdfRenderer,
    private val screenWidth: Int,
    private val coroutineScope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    // ── Configuration ─────────────────────────────────────────
    /** Render at this fraction of screen width. 0.8 = 80% */
    private val renderScaleFactor = 0.85f

    /** Active tool pushed down to newly bound ViewHolders */
    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    private var activeColor = android.graphics.Color.BLUE
    private var activeWidth = 6f

    /** Night mode: apply invert ColorFilter instead of re-rendering */
    var nightMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_NIGHT_MODE)
            }
        }

    companion object {
        private const val PAYLOAD_NIGHT_MODE = "night_mode"
        private const val PAYLOAD_TOOL       = "tool"
    }

    // ── Night mode ColorFilter (reused object — no GC pressure) ──
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        ))
    )

    // ── ViewHolder ────────────────────────────────────────────
    inner class PageViewHolder(val pageView: AnnotatedPageView)
        : RecyclerView.ViewHolder(pageView) {

        /** Coroutine job for the in-flight render of this page */
        var renderJob: Job? = null

        fun bind(pageIndex: Int) {
            // Cancel any previous render job for this holder
            renderJob?.cancel()
            renderJob = null

            // Push current tool state
            pageView.setTool(activeTool, activeColor, activeWidth)

            // Apply night mode ColorFilter (no re-render needed)
            applyNightFilter()

            // Launch async render
            renderJob = coroutineScope.launch {
                val bitmap = renderPage(pageIndex)
                // Only update UI if this job wasn't cancelled
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        pageView.showBitmap(bitmap)
                        applyNightFilter()
                    }
                } else {
                    bitmap.recycle()
                }
            }
        }

        fun applyNightFilter() {
            pageView.pageImageView.colorFilter = if (nightMode) nightFilter else null
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null
            pageView.recycle()
        }
    }

    // ── Adapter implementation ────────────────────────────────

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val pageView = AnnotatedPageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return PageViewHolder(pageView)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    /**
     * Partial-update path: called with a payload so we don't
     * re-render the page bitmap when only the tool or filter changes.
     */
    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        for (payload in payloads) {
            when (payload) {
                PAYLOAD_NIGHT_MODE -> holder.applyNightFilter()
                PAYLOAD_TOOL       -> holder.pageView.setTool(activeTool, activeColor, activeWidth)
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    override fun onFailedToRecycleView(holder: PageViewHolder): Boolean {
        holder.recycle()
        return true
    }

    // ── Rendering ─────────────────────────────────────────────

    /**
     * Render a single PDF page to a Bitmap.
     * Runs on Dispatchers.Default (CPU-bound).
     * Uses RGB_565 to halve memory vs ARGB_8888.
     * PdfRenderer is NOT thread-safe → synchronized on the renderer object.
     */
    private suspend fun renderPage(pageIndex: Int): Bitmap = withContext(Dispatchers.Default) {
        synchronized(renderer) {
            val page = renderer.openPage(pageIndex)
            try {
                val targetWidth  = (screenWidth * renderScaleFactor).toInt()
                val scale        = targetWidth.toFloat() / page.width
                val targetHeight = (page.height * scale).toInt()

                // RGB_565 = 2 bytes/pixel vs 4 bytes/pixel for ARGB_8888
                val bitmap = Bitmap.createBitmap(
                    targetWidth, targetHeight,
                    Bitmap.Config.RGB_565
                )
                // Fill white first (PDF pages may have transparent background)
                bitmap.eraseColor(android.graphics.Color.WHITE)

                page.render(
                    bitmap, null, null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                bitmap
            } finally {
                // Always close the page — PdfRenderer only allows one open at a time
                page.close()
            }
        }
    }

    // ── Tool control ──────────────────────────────────────────

    /**
     * Push the active tool to all currently bound ViewHolders
     * without re-rendering the PDF page bitmaps.
     */
    fun setActiveTool(tool: String, color: Int, widthPx: Float = 6f) {
        activeTool  = tool
        activeColor = color
        activeWidth = widthPx
        notifyItemRangeChanged(0, itemCount, PAYLOAD_TOOL)
    }

    /** Undo on the currently visible page (page index). */
    fun undoOnPage(pageIndex: Int, recyclerView: RecyclerView) {
        findHolder(recyclerView, pageIndex)?.pageView?.undoAnnotation()
    }

    fun redoOnPage(pageIndex: Int, recyclerView: RecyclerView) {
        findHolder(recyclerView, pageIndex)?.pageView?.redoAnnotation()
    }

    private fun findHolder(rv: RecyclerView, position: Int): PageViewHolder? =
        rv.findViewHolderForAdapterPosition(position) as? PageViewHolder

    /**
     * Collect annotation strokes from a specific page ViewHolder.
     * Returns (strokes, renderScale) so caller can map to PDF coords.
     */
    fun getAnnotationsForPage(
        pageIndex: Int,
        recyclerView: RecyclerView
    ): Pair<List<AnnotationCanvasView.Stroke>, Float>? {
        val holder = findHolder(recyclerView, pageIndex) ?: return null
        val strokes = holder.pageView.getAnnotationStrokes()
        if (strokes.isEmpty()) return null

        // Calculate scale that was used during rendering
        synchronized(renderer) {
            val page = renderer.openPage(pageIndex)
            val scale = (screenWidth * renderScaleFactor) / page.width
            page.close()
            return Pair(strokes, scale)
        }
    }
}
