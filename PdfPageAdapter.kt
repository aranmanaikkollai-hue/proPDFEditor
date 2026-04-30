package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * PdfPageAdapter - lazy, memory-efficient PDF page renderer.
 * Renders one page per RecyclerView item, only when visible.
 * RGB_565 uses half the RAM of ARGB_8888.
 * Render jobs cancelled immediately on recycle.
 */
class PdfPageAdapter(
    private val renderer    : PdfRenderer,
    private val screenWidth : Int,
    private val scope       : CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    private var activeColor = Color.BLUE

    var nightMode: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                notifyItemRangeChanged(0, itemCount, "night")
            }
        }

    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f, -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        ))
    )

    // ---- ViewHolder ------------------------------------------------

    inner class PageVH(val view: AnnotatedPageView) : RecyclerView.ViewHolder(view) {
        var job: Job? = null

        fun bind(pos: Int) {
            job?.cancel()
            job = null
            view.setTool(activeTool, activeColor)
            applyNight()
            job = scope.launch {
                val bmp = render(pos)
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        view.showBitmap(bmp)
                        applyNight()
                    }
                } else {
                    bmp.recycle()
                }
            }
        }

        fun applyNight() {
            view.pageImageView.colorFilter = if (nightMode) nightFilter else null
        }
    }

    // ---- Adapter ---------------------------------------------------

    override fun getItemCount() = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PageVH(AnnotatedPageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        })

    override fun onBindViewHolder(holder: PageVH, position: Int) =
        holder.bind(position)

    override fun onBindViewHolder(holder: PageVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(position)
            return
        }
        payloads.forEach { p ->
            when (p) {
                "night" -> holder.applyNight()
                "tool"  -> holder.view.setTool(activeTool, activeColor)
            }
        }
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.job?.cancel()
        holder.job = null
        holder.view.recycle()
    }

    // ---- Render ----------------------------------------------------

    private suspend fun render(index: Int): Bitmap = withContext(Dispatchers.Default) {
        synchronized(renderer) {
            val page   = renderer.openPage(index)
            val scale  = screenWidth.toFloat() / page.width
            val bmpW   = screenWidth
            val bmpH   = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        }
    }

    // ---- Tool control ----------------------------------------------

    fun setActiveTool(tool: String, color: Int) {
        activeTool  = tool
        activeColor = color
        notifyItemRangeChanged(0, itemCount, "tool")
    }

    fun undoOnPage(pageIdx: Int, rv: RecyclerView) {
        (rv.findViewHolderForAdapterPosition(pageIdx) as? PageVH)
            ?.view?.undoAnnotation()
    }

    fun getAnnotationsForPage(
        pageIdx: Int,
        rv: RecyclerView
    ): Pair<List<AnnotationCanvasView.Stroke>, Float>? {
        val holder  = rv.findViewHolderForAdapterPosition(pageIdx) as? PageVH ?: return null
        val strokes = holder.view.getAnnotationStrokes()
        if (strokes.isEmpty()) return null
        val scale = synchronized(renderer) {
            val page = renderer.openPage(pageIdx)
            val s    = screenWidth.toFloat() / page.width
            page.close()
            s
        }
        return Pair(strokes, scale)
    }
}
