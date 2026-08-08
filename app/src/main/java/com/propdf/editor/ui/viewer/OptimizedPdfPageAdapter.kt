package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PointF
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.pool.BitmapPool
import com.propdf.editor.core.render.BackgroundPdfRenderer
import kotlinx.coroutines.*

/**
 * Production-grade PDF page adapter with:
 * - Incremental loading: only visible pages render
 * - View recycling: bitmaps returned to pool on recycle
 * - Memory cache integration: check cache before rendering
 * - Background rendering: non-blocking with cancellation
 * - Night mode support with GPU-efficient color filter
 * - Annotation overlay integration per page
 */
class OptimizedPdfPageAdapter(
    private val renderer: BackgroundPdfRenderer,
    private val screenWidth: Int,
    private val scope: CoroutineScope,
    private val pool: BitmapPool,
    private val cache: LruBitmapCache,
    private val annotationManager: ViewerActivity.AnnotationManager,
    private val onPageVisible: (Int) -> Unit
) : RecyclerView.Adapter<OptimizedPdfPageAdapter.PageVH>() {

    private var nightMode = false
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        ))
    )

    override fun getItemCount() = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val frame = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -2)
            setBackgroundColor(Color.parseColor("#282828"))
        }
        return PageVH(frame)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.recycle()
    }

    fun setNightMode(enabled: Boolean) {
        if (nightMode != enabled) {
            nightMode = enabled
            notifyItemRangeChanged(0, itemCount)
        }
    }

    // ─── ViewHolder ────────────────────────────────────────────────
    inner class PageVH(private val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        private var currentJob: Job? = null
        private var pageImageView: android.widget.ImageView? = null
        private var overlay: ViewerActivity.AnnotOverlay? = null
        private var progressBar: android.widget.ProgressBar? = null
        private var currentPosition = -1

        // ─── Pinch-to-zoom (scale only; no pan in this pass — see notes below) ───
        private var scale = 1f
        private val minScale = 1f
        private val maxScale = 3f

        private val scaleGestureDetector = android.view.ScaleGestureDetector(
            frame.context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    scale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                    applyScale()
                    return true
                }
            }
        )

        private val doubleTapDetector = android.view.GestureDetector(
            frame.context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    resetZoom()
                    return true
                }
            }
        )

        init {
            // NOTE: this only ever receives events when AnnotOverlay declines them
            // (i.e. no drawing tool is active — see AnnotOverlay.onTouchEvent, which
            // already returns false in that case), so this cannot interfere with
            // annotation drawing. When not zoomed and not mid-pinch, this listener
            // also declines (returns false), so normal single-finger vertical
            // scrolling of the page list is unaffected.
            frame.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                doubleTapDetector.onTouchEvent(event)

                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        // Second finger down: this is the start of a pinch. Tell the
                        // RecyclerView not to steal it as a scroll gesture.
                        frame.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        frame.parent?.requestDisallowInterceptTouchEvent(false)
                }

                // Only consume the gesture ourselves while an actual pinch is in
                // progress or the page is currently zoomed in; otherwise decline so
                // the RecyclerView keeps handling normal vertical scroll exactly as
                // it did before this change.
                event.pointerCount > 1 || scale > minScale
            }
        }

        private fun applyScale() {
            pageImageView?.scaleX = scale
            pageImageView?.scaleY = scale
            overlay?.scaleX = scale
            overlay?.scaleY = scale
        }

        private fun resetZoom() {
            scale = 1f
            pageImageView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            overlay?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
        }

        fun bind(position: Int) {
            currentPosition = position
            cancelJob()
            scale = 1f

            // Clear previous content
            frame.removeAllViews()

            // Create views
            val imageView = android.widget.ImageView(frame.context).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -2)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
            }
            pageImageView = imageView

            val progress = android.widget.ProgressBar(frame.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            progressBar = progress

            // Annotation overlay
            val annotOverlay = (frame.context as ViewerActivity).AnnotOverlay(frame.context, position)
            overlay = annotOverlay

            frame.addView(imageView)
            frame.addView(annotOverlay)
            frame.addView(progress)

            // Apply night mode
            imageView.colorFilter = if (nightMode) nightFilter else null

            // Start async render
            currentJob = scope.launch {
                try {
                    val bmp = renderer.getPage(position, screenWidth)
                    if (isActive && bmp != null && !bmp.isRecycled) {
                        withContext(Dispatchers.Main) {
                            if (currentPosition == position) {
                                imageView.setImageBitmap(bmp)
                                progress.visibility = android.view.View.GONE
                                onPageVisible(position)
                            } else {
                                // ViewHolder was recycled during render — return bitmap to pool
                                pool.put(bmp)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Normal cancellation
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = android.view.View.GONE
                    }
                }
            }
        }

        fun recycle() {
            cancelJob()
            frame.parent?.requestDisallowInterceptTouchEvent(false)
            scale = 1f
            pageImageView?.let { iv ->
                (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bmp ->
                    if (!bmp.isRecycled) pool.put(bmp)
                }
                iv.setImageBitmap(null)
            }
            overlay?.release()
            overlay = null
            frame.removeAllViews()
            currentPosition = -1
        }

        private fun cancelJob() {
            currentJob?.cancel()
            currentJob = null
        }
    }
}
