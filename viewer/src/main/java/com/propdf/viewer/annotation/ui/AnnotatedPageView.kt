package com.propdf.viewer.annotation.ui
import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.propdf.viewer.annotation.manager.AnnotationManager
import com.propdf.viewer.annotation.persistence.AnnotationPersistenceManager
import com.propdf.viewer.annotation.render.UnifiedAnnotationRenderer
import com.propdf.viewer.annotation.touch.AnnotationTouchEngine
import com.propdf.viewer.coords.PdfCoordinateSpace
import com.propdf.viewer.gesture.UnifiedGestureCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
/**
* AnnotatedPageView is a lightweight View that renders annotations on the SAME canvas
* as the PDF page. It does NOT use SurfaceView or any overlay architecture.
*
* INTEGRATION with PremiumPageView:
* 1. PremiumPageView creates AnnotatedPageView as a child
* 2. AnnotatedPageView receives the SAME PdfCoordinateSpace as PremiumPageView
* 3. Both use the same transform matrix - zero desync
* 4. PremiumPageView calls annotatedPageView.render(canvas) in its onDraw()
* 5. Touch events go through UnifiedGestureCoordinator
*
* This eliminates ALL overlay problems:
* - No flickering (single canvas)
* - No misalignment (shared matrix)
* - No gesture conflicts (single handler)
* - No lifecycle leaks (no SurfaceView threads)
* - No ViewPager2 issues (regular View)
*/
class AnnotatedPageView @JvmOverloads constructor(
context: Context,
attrs: AttributeSet? = null
) : View(context, attrs) {
private val renderer = UnifiedAnnotationRenderer()
private lateinit var gestureCoordinator: UnifiedGestureCoordinator
private lateinit var annotationManager: AnnotationManager
private lateinit var persistenceManager: AnnotationPersistenceManager
private lateinit var coordSpace: PdfCoordinateSpace
private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
private var currentPdfUri: Uri? = null
private var currentPageIndex: Int = 0
// Callbacks for PremiumPageView integration
var onRequestTransformUpdate: (() -> Unit)? = null
var onRequestInvalidate: (() -> Unit)? = null
fun initialize(
annotationManager: AnnotationManager,
persistenceManager: AnnotationPersistenceManager,
coordSpace: PdfCoordinateSpace
) {
this.annotationManager = annotationManager
this.persistenceManager = persistenceManager
this.coordSpace = coordSpace
gestureCoordinator = UnifiedGestureCoordinator(
context = context,
annotationManager = annotationManager,
coordSpace = coordSpace
)
// Wire callbacks
gestureCoordinator.onRequestInvalidate = {
onRequestInvalidate?.invoke()
}
gestureCoordinator.onAnnotationCreated = { annotation ->
// Auto-save on creation
currentPdfUri?.let { uri ->
scope.launch {
persistenceManager.saveAnnotations(uri.toString(), annotationManager.annotations.value)
}
}
}
// Observe annotation changes
scope.launch {
annotationManager.annotations.collectLatest {
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
}
setWillNotDraw(false)
}
/** Called by PremiumPageView during onDraw() - renders onto same canvas */
fun renderAnnotations(canvas: Canvas) {
if (!::annotationManager.isInitialized) return
if (!::coordSpace.isInitialized) return
val annotations = annotationManager.getAnnotationsForPage(currentPageIndex)
if (annotations.isEmpty() && annotationManager.selectedAnnotationId == null) return
renderer.render(
canvas = canvas,
annotations = annotations,
coordSpace = coordSpace,
selectedId = annotationManager.selectedAnnotationId,
pageWidthPoints = coordSpace.pageWidthPoints,
pageHeightPoints = coordSpace.pageHeightPoints
)
}
/** Single entry point for touch - delegates to gesture coordinator */
override fun onTouchEvent(event: MotionEvent): Boolean {
if (!::gestureCoordinator.isInitialized) return false
return gestureCoordinator.onTouchEvent(event)
}
// ==================== TOOL CONTROL ====================
fun setTool(tool: AnnotationTouchEngine.ToolMode) {
if (::gestureCoordinator.isInitialized) {
gestureCoordinator.setTool(tool)
}
}
fun getTool(): AnnotationTouchEngine.ToolMode {
return if (::gestureCoordinator.isInitialized) gestureCoordinator.getTool()
else AnnotationTouchEngine.ToolMode.NONE
}
// ==================== ANNOTATION OPERATIONS ====================
fun loadAnnotations(pdfUri: Uri, pageIndex: Int) {
currentPdfUri = pdfUri
currentPageIndex = pageIndex
gestureCoordinator.pageIndex = pageIndex
scope.launch {
val annotations = persistenceManager.loadAnnotations(pdfUri.toString())
annotations.forEach { ann -> annotationManager.addAnnotation(ann) }
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
}
fun saveAnnotations() {
currentPdfUri?.let { uri ->
scope.launch {
persistenceManager.saveAnnotations(uri.toString(), annotationManager.annotations.value)
}
}
}
fun undo() {
annotationManager.undo()
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
fun redo() {
annotationManager.redo()
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
fun clearPage() {
annotationManager.clearPage(currentPageIndex)
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
fun clearAll() {
annotationManager.clearAll()
renderer.markFullRedraw()
onRequestInvalidate?.invoke()
}
fun canUndo(): Boolean = annotationManager.canUndo()
fun canRedo(): Boolean = annotationManager.canRedo()
// ==================== LIFECYCLE ====================
fun setPageIndex(pageIndex: Int) {
currentPageIndex = pageIndex
if (::gestureCoordinator.isInitialized) {
gestureCoordinator.pageIndex = pageIndex
}
}
fun onPause() {
saveAnnotations()
}
fun onDestroy() {
saveAnnotations()
if (::gestureCoordinator.isInitialized) {
gestureCoordinator.dispose()
}
scope.cancel()
}
}
