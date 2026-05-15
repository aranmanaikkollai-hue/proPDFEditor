package com.propdf.viewer.presentation

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.model.ViewerTheme
import com.propdf.viewer.ui.PremiumPageView

/**
 * Premium page adapter for ViewPager2 with async rendering support.
 * Handles page bitmap loading, theme application, and zoom state.
 * Uses payload-based partial updates to prevent full rebind flicker.
 */
class PremiumPageAdapter(
    private val onPageTap: (Float, Float) -> Unit,
    private val onPageScale: (Float) -> Unit
) : RecyclerView.Adapter<PremiumPageAdapter.PageViewHolder>() {

    private var pageCount = 0
    private var currentTheme = ViewerTheme.LIGHT
    private val pageBitmaps = mutableMapOf<Int, Bitmap>()

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

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view, onPageTap, onPageScale)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val bitmap = pageBitmaps[position]
        holder.bind(bitmap, currentTheme)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_BITMAP)) {
            val bitmap = pageBitmaps[position]
            holder.setBitmap(bitmap)
        } else if (payloads.contains(PAYLOAD_THEME)) {
            holder.applyTheme(currentTheme)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class PageViewHolder(
        itemView: View,
        private val onPageTap: (Float, Float) -> Unit,
        private val onPageScale: (Float) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val pageView: PremiumPageView = itemView.findViewById(R.id.pageView)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)

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
        }

        fun bind(bitmap: Bitmap?, theme: ViewerTheme) {
            setBitmap(bitmap)
            applyTheme(theme)
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
    }

    companion object {
        private const val PAYLOAD_BITMAP = "bitmap"
        private const val PAYLOAD_THEME = "theme"
    }
}
