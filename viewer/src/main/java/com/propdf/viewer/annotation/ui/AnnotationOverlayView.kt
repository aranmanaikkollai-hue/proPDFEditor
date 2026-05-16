package com.propdf.viewer.annotation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.render.AnnotationRenderer
import com.propdf.viewer.annotation.touch.AnnotationTouchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val renderer = AnnotationRenderer()
    lateinit var touchEngine: AnnotationTouchEngine
        private set
    private lateinit var annotationManager: AnnotationManager

    private val renderScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var renderJob: Job? = null

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    var pageIndex: Int = 0
    var pdfPageWidth: Float = 1f
    var pdfPageHeight: Float = 1f

    var pdfScaleX: Float = 1f
    var pdfScaleY: Float = 1f
    var offsetX: Float = 0f
    var offsetY: Float = 0f

    var onViewerTouchEvent: ((MotionEvent) -> Boolean)? = null

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        setWillNotDraw(false)
    }

    fun initialize(annotationManager: AnnotationManager) {
        this.annotationManager = annotationManager
        this.touchEngine = AnnotationTouchEngine(context, annotationManager)

        touchEngine.pageIndex = pageIndex
        touchEngine.pageWidth = pdfPageWidth
        touchEngine.pageHeight = pdfPageHeight

        renderScope.launch {
            annotationManager.annotations.collectLatest {
                requestRender()
            }
        }
    }

    fun setTouchEngine(tool: AnnotationTouchEngine.ToolMode) {
        if (::touchEngine.isInitialized) {
            touchEngine.setTool(tool)
        }
    }

    fun setAnnotationColor(color: Int) {
        if (::touchEngine.isInitialized) {
            touchEngine.setColor(color)
        }
    }

    fun setAnnotationStrokeWidth(width: Float) {
        if (::touchEngine.isInitialized) {
            touchEngine.setStrokeWidth(width)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderJob?.cancel()
    }

    fun requestRender() {
        if (!holder.surface.isValid) return

        renderJob?.cancel()
        renderJob = renderScope.launch {
            val canvas = holder.lockCanvas() ?: return@launch
            try {
                renderInternal(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun renderInternal(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (!::annotationManager.isInitialized) return

        val pageLeft = offsetX
        val pageTop = offsetY
        val pageRight = pageLeft + pdfPageWidth * pdfScaleX
        val pageBottom = pageTop + pdfPageHeight * pdfScaleY

        canvas.save()
        canvas.clipRect(pageLeft, pageTop, pageRight, pageBottom)

        val annotations = annotationManager.getAnnotationsForPage(pageIndex)

        val pageWidthPx = pageRight - pageLeft
        val pageHeightPx = pageBottom - pageTop

        renderer.render(
            canvas = canvas,
            annotations = annotations,
            pageWidth = pageWidthPx,
            pageHeight = pageHeightPx,
            selectedId = annotationManager.selectedAnnotationId
        )

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::touchEngine.isInitialized) return false

        val x = event.x
        val y = event.y
        val pageLeft = offsetX
        val pageTop = offsetY
        val pageRight = pageLeft + pdfPageWidth * pdfScaleX
        val pageBottom = pageTop + pdfPageHeight * pdfScaleY

        val withinPage = x >= pageLeft && x <= pageRight && y >= pageTop && y <= pageBottom

        if (!withinPage) {
            return onViewerTouchEvent?.invoke(event) ?: false
        }

        val localEvent = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            x - pageLeft,
            y - pageTop,
            event.pressure,
            event.size,
            event.metaState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags
        )

        val handled = touchEngine.onTouchEvent(localEvent, this)
        localEvent.recycle()

        if (!handled) {
            return onViewerTouchEvent?.invoke(event) ?: false
        }

        return true
    }

    fun dispose() {
        renderScope.cancel()
        renderJob?.cancel()
        if (::touchEngine.isInitialized) {
            touchEngine.dispose()
        }
    }

    fun updatePageTransform(
        pageWidth: Float,
        pageHeight: Float,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        this.pdfPageWidth = pageWidth
        this.pdfPageHeight = pageHeight
        this.pdfScaleX = scale
        this.pdfScaleY = scale
        this.offsetX = offsetX
        this.offsetY = offsetY

        if (::touchEngine.isInitialized) {
            touchEngine.pageWidth = pageWidth
            touchEngine.pageHeight = pageHeight
        }

        requestRender()
    }
}
