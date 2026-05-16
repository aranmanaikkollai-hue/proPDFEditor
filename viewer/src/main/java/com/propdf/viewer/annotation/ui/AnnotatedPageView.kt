package com.propdf.viewer.annotation.ui

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.annotation.touch.AnnotationTouchEngine
import com.propdf.viewer.ui.PremiumPageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
* Composite view that layers annotation overlay on top of a PDF page view.
* Integrates the annotation system with the existing PremiumPageView.
* Handles coordinate synchronization, touch routing, and lifecycle.
*/
class AnnotatedPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private lateinit var pageView: PremiumPageView
    private lateinit var overlayView: AnnotationOverlayView
    private lateinit var annotationManager: AnnotationManager
    private lateinit var persistenceManager: AnnotationPersistenceManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentPdfUri: Uri? = null
    private var currentPageIndex: Int = 0

    val touchEngine: AnnotationTouchEngine?
        get() = if (::overlayView.isInitialized) overlayView.touchEngine else null

    fun initialize(
        pageView: PremiumPageView,
        annotationManager: AnnotationManager,
        persistenceManager: AnnotationPersistenceManager
    ) {
        this.pageView = pageView
        this.annotationManager = annotationManager
        this.persistenceManager = persistenceManager

        removeAllViews()

        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        overlayView = AnnotationOverlayView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            initialize(annotationManager)

            onViewerTouchEvent = { event ->
                pageView.dispatchTouchEvent(event)
            }
        }
        addView(overlayView)

        pageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                syncTransform()
            }
        }
    }

    fun loadAnnotations(pdfUri: Uri) {
        currentPdfUri = pdfUri
        scope.launch {
            val annotations = persistenceManager.loadAnnotations(pdfUri.toString())
            annotations.forEach { ann -> annotationManager.addAnnotation(ann) }
            overlayView.requestRender()
        }
    }

    fun saveAnnotations() {
        currentPdfUri?.let { uri ->
            scope.launch {
                persistenceManager.saveAnnotations(uri.toString(), annotationManager.annotations.value)
            }
        }
    }

    fun setTool(tool: AnnotationTouchEngine.ToolMode) {
        overlayView.setTouchEngine(tool)
    }

    fun setColor(color: Int) {
        overlayView.setAnnotationColor(color)
    }

    fun setStrokeWidth(width: Float) {
        overlayView.setAnnotationStrokeWidth(width)
    }

    fun undo() {
        annotationManager.undo()
        overlayView.requestRender()
    }

    fun redo() {
        annotationManager.redo()
        overlayView.requestRender()
    }

    fun clearPage() {
        annotationManager.clearPage(currentPageIndex)
        overlayView.requestRender()
    }

    fun clearAll() {
        annotationManager.clearAll()
        overlayView.requestRender()
    }

    fun canUndo(): Boolean = annotationManager.canUndo()
    fun canRedo(): Boolean = annotationManager.canRedo()

    private fun syncTransform() {
        if (!::pageView.isInitialized || !::overlayView.isInitialized) return

        val scale = pageView.getCurrentScale()
        val offsetX = pageView.scrollX.toFloat()
        val offsetY = pageView.scrollY.toFloat()
        val pageWidth = pageView.measuredWidth.toFloat()
        val pageHeight = pageView.measuredHeight.toFloat()

        overlayView.updatePageTransform(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ::overlayView.isInitialized) {
            val handled = overlayView.onTouchEvent(ev)
            if (handled) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    fun onResume() {
        syncTransform()
        overlayView.requestRender()
    }

    fun onPause() {
        saveAnnotations()
    }

    fun onDestroy() {
        saveAnnotations()
        if (::overlayView.isInitialized) overlayView.dispose()
        scope.cancel()
    }
}
