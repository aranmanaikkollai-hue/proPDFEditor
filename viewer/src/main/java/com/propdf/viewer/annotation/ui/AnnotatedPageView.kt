package com.propdf.viewer.annotation.ui

import android.net.Uri
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.annotation.render.UnifiedAnnotationRenderer
import com.propdf.viewer.gesture.UnifiedGestureCoordinator
import com.propdf.viewer.render.RenderScheduler
import com.propdf.viewer.ui.PremiumPageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lifecycle coordinator that wires the annotation system to a PremiumPageView.
 *
 * Replaces the old FrameLayout + SurfaceView overlay architecture with a
 * zero-overhead coordinator that delegates rendering to PremiumPageView.onDraw()
 * and touch handling to UnifiedGestureCoordinator.
 */
class AnnotatedPageView(
    private val pageView: PremiumPageView,
    private val annotationManager: AnnotationManager,
    private val persistenceManager: AnnotationPersistenceManager,
    private val gestureCoordinator: UnifiedGestureCoordinator,
    private val renderScheduler: RenderScheduler,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    private var currentPdfUri: Uri? = null
    private var currentPageIndex: Int = -1
    private var autosaveJob: Job? = null

    init {
        pageView.annotationManager = annotationManager
        pageView.annotationRenderer = UnifiedAnnotationRenderer()
        gestureCoordinator.attachTo(pageView)

        // Auto-invalidate page view whenever annotations change
        scope.launch {
            annotationManager.annotations.collect {
                pageView.postInvalidateOnAnimation()
                scheduleAutosave()
            }
        }
    }

    fun setPageIndex(index: Int) {
        currentPageIndex = index
        pageView.pageIndex = index
        gestureCoordinator.setPageIndex(index)
    }

    fun loadAnnotations(pdfUri: Uri) {
        currentPdfUri = pdfUri
        scope.launch {
            val annotations = persistenceManager.loadAnnotations(pdfUri.toString())
            annotationManager.replaceAnnotations(annotations)
            pageView.postInvalidateOnAnimation()
        }
    }

    private fun scheduleAutosave() {
        val uri = currentPdfUri ?: return
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(300L)
            persistenceManager.saveAnnotations(uri.toString(), annotationManager.annotations.value)
        }
    }

    fun saveAnnotations() {
        currentPdfUri?.let { uri ->
            scope.launch {
                persistenceManager.saveAnnotations(uri.toString(), annotationManager.annotations.value)
            }
        }
    }

    fun setTool(tool: UnifiedGestureCoordinator.ToolMode) {
        if (tool == UnifiedGestureCoordinator.ToolMode.NONE) {
            gestureCoordinator.setMode(UnifiedGestureCoordinator.Mode.VIEWER)
        } else {
            gestureCoordinator.setMode(UnifiedGestureCoordinator.Mode.ANNOTATION)
            gestureCoordinator.setTool(tool)
        }
    }

    fun setColor(color: Int) = gestureCoordinator.setColor(color)
    fun setStrokeWidth(width: Float) = gestureCoordinator.setStrokeWidth(width)

    fun undo() {
        annotationManager.undo()
        pageView.postInvalidateOnAnimation()
    }

    fun redo() {
        annotationManager.redo()
        pageView.postInvalidateOnAnimation()
    }

    fun clearPage() {
        annotationManager.clearPage(currentPageIndex)
        pageView.postInvalidateOnAnimation()
    }

    fun clearAll() {
        annotationManager.clearAll()
        pageView.postInvalidateOnAnimation()
    }

    fun canUndo(): Boolean = annotationManager.canUndo()
    fun canRedo(): Boolean = annotationManager.canRedo()

    fun onResume() {
        pageView.postInvalidateOnAnimation()
    }

    fun onPause() {
        saveAnnotations()
    }

    fun onDestroy() {
        saveAnnotations()
        gestureCoordinator.detach()
        renderScheduler.dispose()
        scope.cancel()
    }
}
