package com.propdf.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.propdf.editor.ui.tools.ToolsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ViewerActivity : AppCompatActivity() {
// -------------------------------------------------------
// ANNOTATION DATA MODEL
// -------------------------------------------------------
enum class AnnotationType {
FREEHAND, HIGHLIGHT, UNDERLINE, STRIKEOUT, ERASER,
RECT, CIRCLE, ARROW, TEXT, STAMP, IMAGE
}

data class Annotation(
val id: String = UUID.randomUUID().toString(),
val pageIndex: Int,
val type: AnnotationType,
val points: List<PointF>,
val color: Int,
val strokeWidth: Float,
val alpha: Int = 255,
val text: String? = null
)

// -------------------------------------------------------
// ANNOTATION MANAGER
// -------------------------------------------------------
inner class AnnotationManager {
private val store = mutableMapOf<Int, MutableList<Annotation>>()
private val undoList = ArrayDeque<Annotation>()
private val redoList = ArrayDeque<Annotation>()

fun add(ann: Annotation) {
store.getOrPut(ann.pageIndex) { mutableListOf() }.add(ann)
undoList.addLast(ann)
redoList.clear()
updateUndoRedoBtns()
}

fun removeAnnotation(pageIndex: Int, ann: Annotation) {
store[pageIndex]?.remove(ann)
}

fun undo() {
val last = undoList.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
store[last.pageIndex]?.remove(last)
redoList.addLast(last)
updateUndoRedoBtns()
annotOverlays[last.pageIndex]?.invalidate()
}

fun redo() {
val ann = redoList.removeLastOrNull() ?: run { toast("Nothing to redo"); return }
store.getOrPut(ann.pageIndex) { mutableListOf() }.add(ann)
undoList.addLast(ann)
updateUndoRedoBtns()
annotOverlays[ann.pageIndex]?.invalidate()
}

fun get(page: Int): List<Annotation> = store[page] ?: emptyList()
fun getAllPages(): Map<Int, MutableList<Annotation>> = store
fun hasAny(): Boolean = store.values.any { it.isNotEmpty() }
fun undoCount() = undoList.size
fun redoCount() = redoList.size

fun toJson(): String {
val root = JSONObject()
val pages = JSONObject()
store.forEach { (page, anns) ->
val arr = JSONArray()
anns.forEach { a ->
val obj = JSONObject()
obj.put("id", a.id)
obj.put("type", a.type.name)
obj.put("color", a.color)
obj.put("strokeWidth", a.strokeWidth)
obj.put("alpha", a.alpha)
if (a.text != null) obj.put("text", a.text)
val pts = JSONArray()
a.points.forEach { p -> pts.put(JSONObject().put("x", p.x).put("y", p.y)) }
obj.put("points", pts)
arr.put(obj)
}
pages.put(page.toString(), arr)
}
root.put("pages", pages)
root.put("version", 1)
return root.toString()
}

fun fromJson(json: String) {
try {
val root = JSONObject(json)
val pages = root.getJSONObject("pages")
pages.keys().forEach { pageKey ->
val page = pageKey.toIntOrNull() ?: return@forEach
val arr = pages.getJSONArray(pageKey)
val list = mutableListOf<Annotation>()
for (i in 0 until arr.length()) {
val obj = arr.getJSONObject(i)
val ptsArr = obj.getJSONArray("points")
val pts = (0 until ptsArr.length()).map { j ->
val p = ptsArr.getJSONObject(j)
PointF(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
}
list.add(Annotation(
id = obj.getString("id"),
pageIndex = page,
type = AnnotationType.valueOf(obj.getString("type")),
points = pts,
color = obj.getInt("color"),
strokeWidth = obj.getDouble("strokeWidth").toFloat(),
alpha = obj.optInt("alpha", 255),
text = if (obj.has("text")) obj.getString("text") else null
))
}
store[page] = list
}
} catch (_: Exception) {}
}
}

// -------------------------------------------------------
// ANNOTATION RENDERER
// -------------------------------------------------------
inner class AnnotationRenderer {
fun render(
canvas: Canvas,
annotations: List<Annotation>,
livePoints: List<PointF> = emptyList(),
liveTool: AnnotationType? = null,
liveColor: Int = Color.BLACK,
liveWeight: Float = 5f
) {
val sc = canvas.saveLayer(null, null)
annotations.forEach { ann -> drawAnnotation(canvas, ann) }
if (liveTool != null && livePoints.size >= 2) {
val paint = buildPaint(liveTool, liveColor, liveWeight, 255)
val path = pointsToPath(liveTool, livePoints)
canvas.drawPath(path, paint)
}
canvas.restoreToCount(sc)
}

fun drawAnnotation(canvas: Canvas, ann: Annotation) {
if (ann.points.isEmpty()) return
when (ann.type) {
AnnotationType.TEXT -> drawText(canvas, ann)
AnnotationType.STAMP -> drawStamp(canvas, ann)
else -> {
val paint = buildPaint(ann.type, ann.color, ann.strokeWidth, ann.alpha)
val path = pointsToPath(ann.type, ann.points)
canvas.drawPath(path, paint)
}
}
}

private fun pointsToPath(type: AnnotationType, points: List<PointF>): Path {
val path = Path()
when (type) {
AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT -> {
if (points.isEmpty()) return path
path.moveTo(points[0].x, points[0].y)
for (i in 1 until points.size - 1) {
val mx = (points[i].x + points[i + 1].x) / 2f
val my = (points[i].y + points[i + 1].y) / 2f
path.quadTo(points[i].x, points[i].y, mx, my)
}
if (points.size > 1) path.lineTo(points.last().x, points.last().y)
}
AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT -> {
if (points.isEmpty()) return path
val yOff = if (type == AnnotationType.STRIKEOUT) 0f else dp(2).toFloat()
path.moveTo(points.first().x, points.first().y + yOff)
path.lineTo(points.last().x, points.last().y + yOff)
}
AnnotationType.RECT -> {
if (points.size >= 2) path.addRect(
minOf(points[0].x, points[1].x), minOf(points[0].y, points[1].y),
maxOf(points[0].x, points[1].x), maxOf(points[0].y, points[1].y),
Path.Direction.CW
)
}
AnnotationType.CIRCLE -> {
if (points.size >= 2) path.addOval(
RectF(
minOf(points[0].x, points[1].x), minOf(points[0].y, points[1].y),
maxOf(points[0].x, points[1].x), maxOf(points[0].y, points[1].y)
),
Path.Direction.CW
)
}
AnnotationType.ARROW -> {
if (points.size >= 2) {
val sx = points[0].x; val sy = points[0].y
val ex = points.last().x; val ey = points.last().y
path.moveTo(sx, sy); path.lineTo(ex, ey)
val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
val aLen = dp(14).toFloat(); val aAng = Math.PI / 6
path.moveTo(ex, ey)
path.lineTo(
(ex - aLen * Math.cos(angle - aAng)).toFloat(),
(ey - aLen * Math.sin(angle - aAng)).toFloat()
)
path.moveTo(ex, ey)
path.lineTo(
(ex - aLen * Math.cos(angle + aAng)).toFloat(),
(ey - aLen * Math.sin(angle + aAng)).toFloat()
)
}
}
else -> {
if (points.isNotEmpty()) {
path.moveTo(points[0].x, points[0].y)
points.drop(1).forEach { path.lineTo(it.x, it.y) }
}
}
}
return path
}

private fun buildPaint(type: AnnotationType, color: Int, weight: Float, alpha: Int): Paint {
return Paint(Paint.ANTI_ALIAS_FLAG).apply {
this.color = color; this.alpha = alpha
strokeWidth = weight; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
when (type) {
AnnotationType.HIGHLIGHT -> {
style = Paint.Style.STROKE
this.alpha = 90; strokeWidth = weight * 4
}
AnnotationType.UNDERLINE -> {
style = Paint.Style.STROKE
strokeWidth = weight * 1.5f; this.alpha = 220
}
AnnotationType.STRIKEOUT -> {
style = Paint.Style.STROKE
strokeWidth = weight * 1.5f; this.alpha = 220
}
AnnotationType.ERASER -> {
style = Paint.Style.STROKE; strokeWidth = weight * 5f
xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
}
else -> { style = Paint.Style.STROKE }
}
}
}

private fun drawText(canvas: Canvas, ann: Annotation) {
if (ann.text.isNullOrEmpty() || ann.points.isEmpty()) return
// Parse formatting prefix: "BIU:center:16|actual text"
val rawText = ann.text
val parts = rawText.split("|", limit = 2)
val displayText = if (parts.size == 2) parts[1] else rawText
val fmtStr = if (parts.size == 2) parts[0] else ""
val fmtParts = fmtStr.split(":")
val flags = if (fmtParts.isNotEmpty()) fmtParts[0] else ""
val align = if (fmtParts.size > 1) fmtParts[1] else "left"
val fontSize = if (fmtParts.size > 2) fmtParts[2].toFloatOrNull() ?: (ann.strokeWidth * 3f) else ann.strokeWidth * 3f
val bold = "B" in flags; val italic = "I" in flags; val underline = "U" in flags

val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = ann.color; textSize = fontSize; style = Paint.Style.FILL
typeface = when {
bold && italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
bold -> Typeface.DEFAULT_BOLD
italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
else -> Typeface.DEFAULT
}
if (underline) isUnderlineText = true
}

val bg = Paint().apply { color = Color.argb(160, 255, 255, 200); style = Paint.Style.FILL }
val fm = paint.fontMetrics; val tw = paint.measureText(displayText)
val x = ann.points[0].x; val y = ann.points[0].y
val ax = when (align) { "center" -> x - tw / 2; "right" -> x - tw; else -> x }
canvas.drawRect(ax - dp(6), y + fm.top - dp(4), ax + tw + dp(6), y + fm.bottom + dp(4), bg)
canvas.drawText(displayText, ax, y, paint)
}

private fun drawStamp(canvas: Canvas, ann: Annotation) {
if (ann.text.isNullOrEmpty() || ann.points.isEmpty()) return
val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = Color.RED; alpha = 160; textSize = dp(32).toFloat()
style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD
}
val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
color = Color.RED; alpha = 160; style = Paint.Style.STROKE
strokeWidth = dp(2).toFloat()
}
val tw = paint.measureText(ann.text);
val fm = paint.fontMetrics
val x = ann.points[0].x;
val y = ann.points[0].y
canvas.drawRect(x - dp(6), y + fm.top - dp(6), x + tw + dp(6), y + fm.bottom + dp(6), border)
canvas.drawText(ann.text, x, y, paint)
}
}

// -------------------------------------------------------
// ANNOTATION OVERLAY VIEW
// -------------------------------------------------------
inner class AnnotOverlay(context: Context, val pageIdx: Int) : View(context) {
val livePoints = mutableListOf<PointF>()
var liveTool: AnnotationType? = null
var startX = 0f; var startY = 0f; var lastX = 0f; var lastY = 0f
private val renderer = AnnotationRenderer()

override fun onTouchEvent(ev: MotionEvent): Boolean {
val toolStr = activeTool ?: return false
if (toolStr in listOf("save", "image", "stamp", "text", "move_text", "move_shape")) return false
val type = toolStringToType(toolStr) ?: return false
val x = ev.x; val y = ev.y
when (ev.action) {
MotionEvent.ACTION_DOWN -> {
livePoints.clear(); livePoints.add(PointF(x, y))
startX = x; startY = y; lastX = x; lastY = y
performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}
MotionEvent.ACTION_MOVE -> {
when (type) {
AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT -> livePoints.add(PointF(x, y))
AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT,
AnnotationType.RECT, AnnotationType.CIRCLE, AnnotationType.ARROW -> {
livePoints.clear()
livePoints.add(PointF(startX, startY))
livePoints.add(PointF(x, y))
}
else -> livePoints.add(PointF(x, y))
}
lastX = x; lastY = y; invalidate()
}
MotionEvent.ACTION_UP -> {
if (type == AnnotationType.ERASER) {
val bounds = RectF(
x - dp(28).toFloat(), y - dp(28).toFloat(),
x + dp(28).toFloat(), y + dp(28).toFloat()
)
val pageAnns = annotationManager.get(pageIdx).toMutableList()
val toRemove = pageAnns.filter { ann -> ann.points.any { p -> bounds.contains(p.x, p.y) } }
toRemove.forEach { ann -> annotationManager.removeAnnotation(pageIdx, ann) }
livePoints.clear(); invalidate(); return true
}

val finalPts = ArrayList(livePoints)
if (finalPts.isEmpty()) return true
val col = if (type == AnnotationType.HIGHLIGHT) highlightColor else activeColor
val ann = Annotation(
pageIndex = pageIdx,
type = type,
points = finalPts,
color = col,
strokeWidth = strokeWidth,
alpha = if (type == AnnotationType.HIGHLIGHT) 90 else 255
)
annotationManager.add(ann)
livePoints.clear(); invalidate()
}
}
return true
}

override fun onDraw(canvas: Canvas) {
renderer.render(
canvas,
annotationManager.get(pageIdx),
livePoints,
activeTool?.let { toolStringToType(it) },
if (activeTool == "highlight") highlightColor else activeColor,
strokeWidth
)
}
}

// -------------------------------------------------------
// STATE
// -------------------------------------------------------
private var pdfUri: Uri? = null
private var pdfPassword: String? = null
private var pdfFile: File? = null
private var pdfRenderer: PdfRenderer? = null
private var currentPage = 0
private var totalPages = 0
private var searchResultIdx = 0
private var searchResults: List<Int> = emptyList()
private var lastSearchQuery = ""
private var annotToolbarExpanded = false
private var activeAnnotGroup = "markup"
private var activeTool: String? = null
private var activeColor = Color.parseColor("#007AFF")
private var highlightColor = Color.parseColor("#FFFF00")
private var strokeWidth = 5f
private var readingMode = "normal"
private val annotationManager = AnnotationManager()
private val annotOverlays = mutableMapOf<Int, AnnotOverlay>()
private val bookmarkedPages = mutableSetOf<Int>()

private val COLOR_PALETTE = listOf(
"#FFFF00", "#FF6B35", "#E53935", "#AD1457", "#6A1B9A",
"#1565C0", "#007AFF", "#00897B", "#2E7D32", "#F9A825",
"#FF8F00", "#4E342E", "#FFFFFF", "#9E9E9E", "#000000",
"#00BCD4", "#8BC34A", "#FF4081", "#AA00FF", "#EF6719"
)
private val ANNOT_GROUPS = linkedMapOf(
"markup" to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
"shapes" to listOf("rect", "circle", "arrow"),
"inserts" to listOf("text", "stamp", "image"),
"manage" to listOf("move_text", "move_shape", "save")
)
private val TOOL_LABEL = mapOf(
"freehand" to "Pen", "highlight" to "High.", "underline" to "Under.",
"strikeout" to "Strk", "eraser" to "Erase", "rect" to "Rect",
"circle" to "Circle", "arrow" to "Arrow", "text" to "Text",
"stamp" to "Stamp", "image" to "Image", "move_text" to "MoveT",
"move_shape" to "MoveS", "save" to "Save"
)
private val TOOL_ICON = mapOf(
"freehand" to android.R.drawable.ic_menu_edit,
"highlight" to android.R.drawable.ic_menu_view,
"underline" to android.R.drawable.ic_menu_info_details,
"strikeout" to android.R.drawable.ic_menu_delete,
"eraser" to android.R.drawable.ic_menu_close_clear_cancel,
"rect" to android.R.drawable.ic_menu_crop,
"circle" to android.R.drawable.ic_menu_search,
"arrow" to android.R.drawable.ic_media_next,
"text" to android.R.drawable.ic_dialog_info,
"stamp" to android.R.drawable.ic_menu_send,
"image" to android.R.drawable.ic_menu_gallery,
"move_text" to android.R.drawable.ic_dialog_map,
"move_shape" to android.R.drawable.ic_menu_compass,
"save" to android.R.drawable.ic_menu_save
)

private fun toolStringToType(tool: String): AnnotationType? = when (tool) {
"freehand" -> AnnotationType.FREEHAND
"highlight" -> AnnotationType.HIGHLIGHT
"underline" -> AnnotationType.UNDERLINE
"strikeout" -> AnnotationType.STRIKEOUT
"eraser" -> AnnotationType.ERASER
"rect" -> AnnotationType.RECT
"circle" -> AnnotationType.CIRCLE
"arrow" -> AnnotationType.ARROW
"text" -> AnnotationType.TEXT
"stamp" -> AnnotationType.STAMP
"image" -> AnnotationType.IMAGE
else -> null
}

// Views
private lateinit var rootLayout: LinearLayout
private lateinit var scrollView: ScrollView
private lateinit var pageContainer: LinearLayout
private lateinit var searchBar: LinearLayout
private lateinit var annotToolbarWrap: LinearLayout
private lateinit var annotExpandBtn: TextView
private lateinit var annotBar: LinearLayout
private lateinit var pageCounter: TextView
private lateinit var searchInput: EditText
private lateinit var searchCountLabel: TextView
private lateinit var annotSubMenuRow: LinearLayout
private lateinit var annotGroupNavBar: LinearLayout
private lateinit var annotWeightValue: TextView
private lateinit var annotWeightBar: SeekBar
private lateinit var undoBtn: TextView
private lateinit var redoBtn: TextView
private val annotSwatchViews = mutableListOf<View>()

// -------------------------------------------------------
// LIFECYCLE
// -------------------------------------------------------
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
val uriStr = intent.getStringExtra(EXTRA_URI)
pdfUri = if (uriStr != null) Uri.parse(uriStr) else intent.data
pdfPassword = intent.getStringExtra(EXTRA_PASSWORD)
buildUI()
loadPdf()
loadAnnotationsFromCache()
}

override fun onDestroy() {
super.onDestroy()
closePdfRenderer()
}

override fun onBackPressed() {
if (searchBar.visibility == View.VISIBLE) {
hideSearchBar(); return
}
if (annotToolbarExpanded) {
collapseAnnotToolbar(); return
}
super.onBackPressed()
}

// -------------------------------------------------------
// ROOT UI
// -------------------------------------------------------
private fun buildUI() {
rootLayout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.parseColor("#121212"))
}
setContentView(rootLayout)
rootLayout.addView(buildTopBar())
searchBar = buildSearchBar()
rootLayout.addView(searchBar)

// Page nav strip: < PREV | Page X of N (tap=go-to) | NEXT >
val navStrip = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setBackgroundColor(Color.parseColor("#1A1A1A"))
layoutParams = LinearLayout.LayoutParams(-1, -2)
}
val navTint = Color.parseColor("#ADC6FF")
navStrip.addView(ImageButton(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(44), dp(34))
setImageResource(android.R.drawable.ic_media_previous)
colorFilter = PorterDuffColorFilter(navTint, PorterDuff.Mode.SRC_IN)
setBackgroundColor(Color.TRANSPARENT)
setOnClickListener { if (currentPage > 0) scrollToPage(currentPage - 1) }
})
pageCounter = TextView(this).apply {
layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
gravity = Gravity.CENTER
setTextColor(navTint)
textSize = 11f; typeface = Typeface.DEFAULT_BOLD
setPadding(0, dp(3), 0, dp(3))
text = "Loading..."
setOnClickListener { showGoToPageDialog() }
}
navStrip.addView(pageCounter)
navStrip.addView(ImageButton(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(44), dp(34))
setImageResource(android.R.drawable.ic_media_next)
colorFilter = PorterDuffColorFilter(navTint, PorterDuff.Mode.SRC_IN)
setBackgroundColor(Color.TRANSPARENT)
setOnClickListener { if (currentPage < totalPages - 1) scrollToPage(currentPage + 1) }
})
rootLayout.addView(navStrip)

scrollView = ScrollView(this).apply {
layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
setBackgroundColor(Color.parseColor("#282828"))
}
pageContainer = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(dp(8), dp(8), dp(8), dp(8))
}
scrollView.addView(pageContainer)
rootLayout.addView(scrollView)
scrollView.viewTreeObserver.addOnScrollChangedListener { updatePageCounterFromScroll() }
rootLayout.addView(buildAnnotationArea())
}

private fun buildTopBar(): LinearLayout {
val cTxt = Color.parseColor("#ADC6FF")
return LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setBackgroundColor(Color.parseColor("#1A1A1A"))
gravity = Gravity.CENTER_VERTICAL
setPadding(dp(4), dp(4), dp(4), dp(4))
layoutParams = LinearLayout.LayoutParams(-1, -2)
addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back", cTxt) { finish() })
addView(TextView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
val name = pdfUri?.lastPathSegment ?: "PDF"
text = if (name.length > 28) name.take(25) + "..." else name
setTextColor(cTxt); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
setPadding(dp(4), 0, 0, 0)
})
addView(buildIconBtn(android.R.drawable.ic_menu_search, "Find", cTxt) { toggleSearchBar() })
addView(buildIconBtn(android.R.drawable.ic_menu_mapmode, "Mode", cTxt) { showReadingModeDialog() })
addView(buildIconBtn(android.R.drawable.ic_menu_edit, "OCR", cTxt) { showOcrMenu() })
addView(buildIconBtn(android.R.drawable.ic_menu_more, "More", cTxt) { showPdfOpsMenu() })
}
}

private fun buildIconBtn(iconRes: Int, desc: String, tint: Int, action: () -> Unit): ImageButton {
return ImageButton(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
setImageResource(iconRes)
colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
setBackgroundColor(Color.TRANSPARENT); contentDescription = desc
setOnClickListener { action() }
}
}

// -------------------------------------------------------
// PDF LOADING
// -------------------------------------------------------
private fun loadPdf() {
lifecycleScope.launch {
try {
val uri = pdfUri ?: run { toast("No PDF specified"); return@launch }
val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
if (file == null) { toast("Cannot read PDF"); return@launch }
pdfFile = file
val ok = withContext(Dispatchers.IO) {
try { openPdfRenderer(file); true } catch (_: Exception) { false }
}
if (!ok) { toast("Error opening PDF"); return@launch }
renderAllPages()
} catch (e: Exception) {
toast("Error: ${e.message}")
}
}
}

private fun copyUriToCache(uri: Uri): File? {
return try {
val dest = File(cacheDir, "viewer_${System.currentTimeMillis()}.pdf")
contentResolver.openInputStream(uri)?.use { inp ->
FileOutputStream(dest).use { out -> inp.copyTo(out) }
}
if (dest.length() > 0) dest else null
} catch (_: Exception) {
null
}
}

private fun openPdfRenderer(file: File) {
closePdfRenderer()
val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
pdfRenderer = PdfRenderer(pfd); totalPages = pdfRenderer!!.pageCount
}

private fun closePdfRenderer() {
try {
pdfRenderer?.close()
} catch (_: Exception) {
}
pdfRenderer = null
}

private suspend fun renderAllPages() {
val renderer = pdfRenderer ?: return
val screenW = resources.displayMetrics.widthPixels - dp(16)
totalPages = renderer.pageCount
withContext(Dispatchers.Main) {
pageContainer.removeAllViews(); annotOverlays.clear()
pageCounter.text = "Page 1 of $totalPages"
}

for (i in 0 until totalPages) {
val bmp: Bitmap = withContext(Dispatchers.IO) {
synchronized(renderer) {
val page = renderer.openPage(i)
try {
val scale = screenW.toFloat() / page.width.coerceAtLeast(1).toFloat()
val bmpW = (page.width * scale).toInt().coerceAtLeast(1)
val bmpH = (page.height * scale).toInt().coerceAtLeast(1)
val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
Canvas(b).drawColor(Color.WHITE)
page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
b
} finally {
page.close()
}
}
}
withContext(Dispatchers.Main) {
addPageView(bmp, i)
if (i % 4 == 0) System.gc()
}
}
withContext(Dispatchers.Main) {
annotOverlays.values.forEach { it.invalidate() }
}
}

private fun addPageView(bmp: Bitmap, pageIndex: Int) {
val pageFrame = FrameLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
}
val zoomFrame = ZoomableImageView(this, bmp, pageIndex)
pageFrame.addView(zoomFrame, FrameLayout.LayoutParams(-1, -2))
val overlay = AnnotOverlay(this, pageIndex)
annotOverlays[pageIndex] = overlay
pageFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
pageContainer.addView(pageFrame)
}

private fun updatePageCounterFromScroll() {
if (totalPages == 0) return
val page = ((scrollView.scrollY.toFloat() / pageContainer.height.coerceAtLeast(1)) * totalPages)
.toInt().coerceIn(0, totalPages - 1)
currentPage = page
val bm = if (bookmarkedPages.contains(page)) " [B]" else ""
pageCounter.text = "Page ${page + 1} of $totalPages$bm"
}

// -------------------------------------------------------
// UNDO / REDO
// -------------------------------------------------------
private fun updateUndoRedoBtns() {
if (!::undoBtn.isInitialized) return
val on = Color.parseColor("#ADC6FF"); val off = Color.parseColor("#444444")
undoBtn.setTextColor(if (annotationManager.undoCount() > 0) on else off)
redoBtn.setTextColor(if (annotationManager.redoCount() > 0) on else off)
}

// -------------------------------------------------------
// SEARCH BAR
// -------------------------------------------------------
private fun toggleSearchBar() {
if (searchBar.visibility == View.GONE) {
searchBar.visibility = View.VISIBLE
searchInput.requestFocus()
(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
.showSoftInput(searchInput, 0)
} else hideSearchBar()
}

private fun hideSearchBar() {
searchBar.visibility = View.GONE; searchInput.setText("")
searchResults = emptyList(); searchResultIdx = 0
updateSearchCounter(); hideKeyboard()
}

private fun buildSearchBar(): LinearLayout {
val cBg = Color.parseColor("#1E1E2E")
val cInput = Color.parseColor("#0E0E1A")
val cBlue = Color.parseColor("#ADC6FF")
val cDark = Color.parseColor("#4B8EFF")
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(cBg); setPadding(dp(12), dp(10), dp(12), dp(10))
visibility = View.GONE
val row1 = LinearLayout(this@ViewerActivity).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}
val wrap = FrameLayout(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
}
searchInput = EditText(this@ViewerActivity).apply {
layoutParams = FrameLayout.LayoutParams(-1, -1)
hint = "Search in PDF..."
setHintTextColor(Color.parseColor("#666888"))
setTextColor(Color.WHITE)
background = GradientDrawable().apply {
setColor(cInput); cornerRadius = dp(10).toFloat()
}
setPadding(dp(12), 0, dp(12), 0)
imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
setOnEditorActionListener { _, actionId, _ ->
if (actionId == EditorInfo.IME_ACTION_SEARCH) {
hideKeyboard(); runSearch(); true
} else false
}
}
wrap.addView(searchInput); row1.addView(wrap)
row1.addView(TextView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
text = "GO"; setTextColor(Color.parseColor("#001A4D"))
typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; letterSpacing = 0.1f
background = GradientDrawable().apply {
colors = intArrayOf(cBlue, cDark)
gradientType = GradientDrawable.LINEAR_GRADIENT
orientation = GradientDrawable.Orientation.TL_BR
cornerRadius = dp(10).toFloat()
}
setOnClickListener { hideKeyboard(); runSearch() }
})
row1.addView(ImageButton(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginStart = dp(4) }
setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
colorFilter = PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN)
setBackgroundColor(Color.TRANSPARENT)
setOnClickListener { hideSearchBar() }
})
addView(row1)
addView(View(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(-1, dp(6))
})
val row2 = LinearLayout(this@ViewerActivity).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}
row2.addView(buildSearchNavBtn("< Prev") {
if (searchResults.isNotEmpty()) {
searchResultIdx = (searchResultIdx - 1 + searchResults.size) % searchResults.size
scrollToPage(searchResults[searchResultIdx]); updateSearchCounter()
}
})
searchCountLabel = TextView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(0, -2, 1f); gravity = Gravity.CENTER
textSize = 12f; setTextColor(cBlue); text = "Tap GO"
}
row2.addView(searchCountLabel)
row2.addView(buildSearchNavBtn("Next >") {
if (searchResults.isNotEmpty()) {
searchResultIdx = (searchResultIdx + 1) % searchResults.size
scrollToPage(searchResults[searchResultIdx]); updateSearchCounter()
}
})
row2.addView(TextView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(8) }
text = "Go to Pg"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
setTextColor(cBlue); gravity = Gravity.CENTER
setPadding(dp(10), dp(6), dp(10), dp(6))
background = GradientDrawable().apply {
setColor(Color.parseColor("#2D2D44")); cornerRadius = dp(8).toFloat()
}
setOnClickListener { showGoToPageDialog() }
})
addView(row2)
}
}

private fun buildSearchNavBtn(label: String, action: () -> Unit): TextView {
return TextView(this).apply {
layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
if (label.startsWith("<")) marginEnd = dp(6) else marginStart = dp(6)
}
text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
setTextColor(Color.parseColor("#E5E2E1")); gravity = Gravity.CENTER
setPadding(dp(14), 0, dp(14), 0)
background = GradientDrawable().apply {
setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(10).toFloat()
}
setOnClickListener { action() }
}
}

private fun runSearch() {
val query = searchInput.text.toString().trim()
if (query.isEmpty()) {
toast("Enter search text"); return
}
// Placeholder: in a real app, this would use PDFBox or another library to search text
// For this example, we'll just pretend all pages match for demonstration
lastSearchQuery = query
searchResults = (0 until totalPages).toList()
searchResultIdx = 0; updateSearchCounter()
if (searchResults.isNotEmpty()) scrollToPage(searchResults[0])
}

private fun updateSearchCounter() {
if (!::searchCountLabel.isInitialized) return
searchCountLabel.text = when {
searchResults.isEmpty() && lastSearchQuery.isNotEmpty() -> "No results for '$lastSearchQuery'"
searchResults.isEmpty() -> "Tap GO to search"
else -> "${searchResultIdx + 1} / ${searchResults.size} pages"
}
}

private fun scrollToPage(page: Int) {
if (pageContainer.childCount <= page || page < 0) return
scrollView.post {
val child = pageContainer.getChildAt(page) ?: return@post
scrollView.smoothScrollTo(0, child.top)
currentPage = page
val bm = if (bookmarkedPages.contains(page)) " [B]" else ""
pageCounter.text = "Page ${page + 1} of $totalPages$bm"
}
}

private fun showGoToPageDialog() {
val container = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10))
}
val input = EditText(this).apply {
inputType = android.text.InputType.TYPE_CLASS_NUMBER
hint = "Enter page (1 - $totalPages)"
textSize = 16f; setPadding(dp(8), dp(8), dp(8), dp(8))
setSelectAllOnFocus(true)
}
val info = TextView(this).apply {
text = "Currently on page ${currentPage + 1} of $totalPages"
textSize = 12f; setTextColor(Color.parseColor("#8B90A0")); setPadding(0, dp(6), 0, 0)
}
container.addView(input); container.addView(info)
// Quick jump buttons
val jumpRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
}
listOf("First" to 1, "Prev" to (currentPage), "Next" to (currentPage + 2), "Last" to totalPages).forEach { (label, pg) ->
jumpRow.addView(TextView(this).apply {
text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
setPadding(dp(12), dp(6), dp(12), dp(6)); layoutParams =
LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
setTextColor(Color.parseColor("#ADC6FF"))
background = GradientDrawable().apply {
setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat()
}
setOnClickListener {
val target = pg.coerceIn(1, totalPages);
scrollToPage(target - 1);
}
})
}
container.addView(jumpRow)
AlertDialog.Builder(this).setTitle("Navigate to Page").setView(container)
.setPositiveButton("Go") { dialog, _ ->
val pg = input.text.toString().toIntOrNull()
if (pg != null && pg in 1..totalPages) {
scrollToPage(pg - 1);
} else toast("Enter a page between 1 and $totalPages")
(dialog as AlertDialog).dismiss()
}.setNegativeButton("Cancel", null).show()
}

// -------------------------------------------------------
// READING MODE
// -------------------------------------------------------
private fun showReadingModeDialog() {
val modes = arrayOf("Normal", "Night Mode", "Sepia", "Day (Bright)")
AlertDialog.Builder(this).setTitle("View & Reading Mode").setItems(modes)
{ _, which ->
when (which) {
0 -> applyReadingFilter("normal")
1 -> applyReadingFilter("night")
2 -> applyReadingFilter("sepia")
3 -> applyReadingFilter("day")
}
}.show()
}

private fun applyReadingFilter(mode: String) {
val matrix = ColorMatrix()
when (mode) {
"night" -> matrix.set(
floatArrayOf(
-1f, 0f, 0f, 0f, 255f,
0f, -1f, 0f, 0f, 255f,
0f, 0f, -1f, 0f, 255f,
0f, 0f, 0f, 1f, 0f
)
)
"sepia" -> matrix.set(
floatArrayOf(
0.393f, 0.769f, 0.189f, 0f, 0f,
0.349f, 0.686f, 0.168f, 0f, 0f,
0.272f, 0.534f, 0.131f, 0f, 0f,
0f, 0f, 0f, 1f, 0f
)
)
"day" -> matrix.set(
floatArrayOf(
1.2f, 0f, 0f, 0f, 20f,
0f, 1.2f, 0f, 0f, 20f,
0f, 0f, 1.2f, 0f, 20f,
0f, 0f, 0f, 1f, 0f
)
)
else -> { // normal
for (i in 0 until pageContainer.childCount) {
val f = pageContainer.getChildAt(i) as? FrameLayout ?: continue
(f.getChildAt(0) as? ZoomableImageView)?.applyFilter(null)
}
return
}
}
val filter = ColorMatrixColorFilter(matrix)
for (i in 0 until pageContainer.childCount) {
val f = pageContainer.getChildAt(i) as? FrameLayout ?: continue
(f.getChildAt(0) as? ZoomableImageView)?.applyFilter(filter)
}
}

// -------------------------------------------------------
// OCR MENU (IMPLEMENTED)
// -------------------------------------------------------
private fun showOcrMenu() {
val items = arrayOf(
"Extract text — this page",
"Extract text — all pages",
"Copy page text to clipboard",
"Select & copy text (overlay)",
"OCR on image-based PDF (ML Kit)"
)
AlertDialog.Builder(this).setTitle("Text & OCR")
.setItems(items) { _, which ->
when (which) {
0 -> extractAndShowPageText()
1 -> extractAndShowAllText()
2 -> copyPageTextDirect()
3 -> showSelectableTextOverlay()
4 -> runMlKitOcrOnPage()
}
}.show()
}

private fun extractAndShowPageText() {
lifecycleScope.launch {
toast("Extracting text from page ${currentPage + 1}...")
val text = withContext(Dispatchers.IO) {
pdfFile?.let { file ->
try {
PDDocument.load(file).use { document ->
val stripper = PDFTextStripper()
stripper.startPage = currentPage + 1
stripper.endPage = currentPage + 1
stripper.getText(document)
}
} catch (e: Exception) {
"Error extracting text: ${e.message}"
}
} ?: "PDF file not found."
}
showTextDialog("Page ${currentPage + 1} Text", text)
}
}

private fun extractAndShowAllText() {
lifecycleScope.launch {
toast("Extracting text from $totalPages pages...")
val text = withContext(Dispatchers.IO) {
pdfFile?.let { file ->
try {
PDDocument.load(file).use { document ->
PDFTextStripper().getText(document)
}
} catch (e: Exception) {
"Error extracting text: ${e.message}"
}
} ?: "PDF file not found."
}
showTextDialog("Full Document Text", text)
}
}

private fun copyPageTextDirect() {
lifecycleScope.launch {
val text = withContext(Dispatchers.IO) {
pdfFile?.let { file ->
try {
PDDocument.load(file).use { document ->
val stripper = PDFTextStripper()
stripper.startPage = currentPage + 1
stripper.endPage = currentPage + 1
stripper.getText(document)
}
} catch (e: Exception) { null }
}
}
if (!text.isNullOrEmpty()) {
val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cb.setPrimaryClip(ClipData.newPlainText("PDF Page Text", text))
toast("Page ${currentPage + 1} text copied to clipboard")
} else {
toast("Failed to extract text.")
}
}
}

private fun showSelectableTextOverlay() {
val pageFrame = pageContainer.getChildAt(currentPage) as? FrameLayout ?: return
val existingOverlay = pageFrame.findViewWithTag<View>("text_overlay")

if (existingOverlay != null) {
pageFrame.removeView(existingOverlay)
toast("Text overlay removed")
return
}

lifecycleScope.launch {
toast("Extracting text for overlay...")
val text = withContext(Dispatchers.IO) {
pdfFile?.let { file ->
try {
PDDocument.load(file).use { document ->
val stripper = PDFTextStripper()
stripper.startPage = currentPage + 1
stripper.endPage = currentPage + 1
stripper.getText(document)
}
} catch (e: Exception) {
"Error extracting text: ${e.message}"
}
} ?: "PDF file not found."
}

val editText = EditText(this@ViewerActivity).apply {
tag = "text_overlay"
layoutParams = FrameLayout.LayoutParams(
FrameLayout.LayoutParams.MATCH_PARENT,
FrameLayout.LayoutParams.MATCH_PARENT
)
setText(text)
gravity = Gravity.TOP
textSize = 10f // Small text size to roughly align with document
setTextColor(Color.BLACK)
setBackgroundColor(Color.argb(80, 255, 255, 200)) // Semi-transparent
isFocusable = true
isFocusableInTouchMode = true
setTextIsSelectable(true)
}
pageFrame.addView(editText)
toast("Text overlay added. Long-press to select.")
}
}

private fun runMlKitOcrOnPage() {
val frame = pageContainer.getChildAt(currentPage) as? FrameLayout ?: return
val zoom = frame.getChildAt(0) as? ZoomableImageView ?: return
val bmp = zoom.getRenderedBitmap()?.copy(Bitmap.Config.ARGB_8888, false) ?: return

toast("OCR running on page ${currentPage + 1}...")

val image = InputImage.fromBitmap(bmp, 0)
val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

recognizer.process(image)
.addOnSuccessListener { visionText ->
showTextDialog("ML Kit OCR Result", visionText.text)
}
.addOnFailureListener { e ->
toast("OCR failed: ${e.message}")
}
.addOnCompleteListener {
// The bitmap can be recycled after the process is complete.
if (!bmp.isRecycled) {
bmp.recycle()
}
}
}

private fun showTextDialog(title: String, text: String) {
val scrollView = ScrollView(this)
val et = EditText(this).apply {
setText(text); textSize = 13f
setTextColor(Color.parseColor("#E5E2E1"))
setBackgroundColor(Color.parseColor("#1A1A1A"))
setPadding(dp(16), dp(8), dp(16), dp(8))
setTextIsSelectable(true)
}
scrollView.addView(et)
AlertDialog.Builder(this).setTitle(title).setView(scrollView)
.setPositiveButton("Copy") { _, _ ->
val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
cb.setPrimaryClip(ClipData.newPlainText("PDF Text", text)); toast("Copied")
}.setNegativeButton("Close", null).show()
}

// -------------------------------------------------------
// PDF OPS MENU
// -------------------------------------------------------
private fun showPdfOpsMenu() {
val bm = if (bookmarkedPages.contains(currentPage)) "Remove Bookmark" else "Bookmark This Page"
val items = arrayOf(
bm, "All Bookmarks", "Go to Page", "Password Protect",
"Add Watermark", "Rotate Page", "Delete Page", "Merge / Split", "Share PDF", "Open Tools"
)
AlertDialog.Builder(this).setTitle("PDF Operations").setItems(items) { _, which ->
when (which) {
0 -> toggleBookmark()
1 -> showBookmarksDialog()
2 -> showGoToPageDialog()
3 -> toast("Encrypt: Feature not implemented")
4 -> toast("Watermark: Feature not implemented")
5 -> showRotatePageDialog()
6 -> toast("Delete: Feature not implemented")
7 -> startActivity(Intent(this, ToolsActivity::class.java)) // Example
8 -> sharePdf()
9 -> startActivity(Intent(this, ToolsActivity::class.java)) // Example
}
}.show()
}

private fun toggleBookmark() {
if (bookmarkedPages.contains(currentPage)) {
bookmarkedPages.remove(currentPage); toast("Bookmark removed")
} else {
bookmarkedPages.add(currentPage); toast("Page ${currentPage + 1} bookmarked")
}
val prefs = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
val key = pdfUri?.toString()?.hashCode().toString()
prefs.edit().putStringSet(key, bookmarkedPages.map { it.toString() }.toSet()).apply()
updatePageCounterFromScroll()
}

private fun showBookmarksDialog() {
if (bookmarkedPages.isEmpty()) {
toast("No bookmarks yet. Use More > Bookmark This Page"); return
}
val sorted = bookmarkedPages.sorted()
val prefs2 = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
val key = pdfUri?.toString()?.hashCode().toString()
val labels = prefs2.getStringSet("${key}_labels", emptySet()) ?: emptySet()
val labelMap = labels.associate {
val parts = it.split(":", limit = 2); (parts[0].toIntOrNull() ?: -1) to (parts.getOrElse(1) { "" })
}
val items = sorted.map { pg ->
val lbl = labelMap[pg]
if (!lbl.isNullOrEmpty()) "Page ${pg + 1} — $lbl" else "Page ${pg + 1}"
}.toTypedArray()

AlertDialog.Builder(this).setTitle("Bookmarks (${sorted.size})")
.setItems(items) { _, i -> scrollToPage(sorted[i]) }
.setNeutralButton("Add Label") { _, _ ->
val et = EditText(this).apply { hint = "Label for page ${currentPage + 1}" }
AlertDialog.Builder(this).setTitle("Bookmark Label").setView(et)
.setPositiveButton("Save") { _, _ ->
val lbl = et.text.toString().trim()
val newLabels = (labels + "${currentPage}:$lbl").toMutableSet()
prefs2.edit().putStringSet("${key}_labels", newLabels).apply()
if (!bookmarkedPages.contains(currentPage)) toggleBookmark()
toast("Bookmark labelled: $lbl")
}.setNegativeButton("Cancel", null).show()
}.show()
}

private fun sharePdf() {
val file = pdfFile ?: return
try {
val uri = FileProvider.getUriForFile(
this, "$packageName.provider", file
)
startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}, "Share PDF"))
} catch (_: Exception) {
toast("Cannot share file")
}
}

// -------------------------------------------------------
// ANNOTATION TOOLBAR
// -------------------------------------------------------
private fun buildAnnotationArea(): LinearLayout {
annotToolbarWrap = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.parseColor("#131313"))
}
val strip = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setBackgroundColor(Color.parseColor("#1A1A1A"))
setPadding(dp(12), dp(6), dp(12), dp(6))
layoutParams = LinearLayout.LayoutParams(-1, -2)
}
annotExpandBtn = TextView(this).apply {
layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
text = "Annotate v"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
setTextColor(Color.parseColor("#ADC6FF")); gravity = Gravity.CENTER
background = GradientDrawable().apply {
setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat()
}
setOnClickListener { toggleAnnotToolbar() }
}
strip.addView(annotExpandBtn)

undoBtn = buildQuickBtn("\u21A9")
undoBtn.setOnClickListener { annotationManager.undo() }
strip.addView(undoBtn)

redoBtn = buildQuickBtn("\u21AA")
redoBtn.setOnClickListener { annotationManager.redo() }
strip.addView(redoBtn)

val saveBtn = buildQuickBtn("Save", textColor = Color.parseColor("#2E7D32"), bgColor = Color.parseColor("#1B3A1C"))
saveBtn.setOnClickListener { saveAnnotations() }
strip.addView(saveBtn)

annotToolbarWrap.addView(strip)
annotBar = buildFullAnnotBar()
annotBar.visibility = View.GONE
annotToolbarWrap.addView(annotBar)
return annotToolbarWrap
}

private fun buildQuickBtn(
label: String,
textColor: Int = Color.parseColor("#ADC6FF"),
bgColor: Int = Color.parseColor("#2D2D2D")
): TextView {
return TextView(this).apply {
layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) }
text = label
textSize = if (label.length == 1) 14f else 11f
typeface = Typeface.DEFAULT_BOLD
setTextColor(textColor)
setPadding(dp(10), 0, dp(10), 0)
gravity = Gravity.CENTER
background = GradientDrawable().apply {
setColor(bgColor); cornerRadius = dp(8).toFloat()
}
}
}

private fun toggleAnnotToolbar() {
if (annotToolbarExpanded) collapseAnnotToolbar() else expandAnnotToolbar()
}

private fun expandAnnotToolbar() {
annotToolbarExpanded = true; annotBar.visibility = View.VISIBLE
annotExpandBtn.text = "Annotate ^"
}

private fun collapseAnnotToolbar() {
annotToolbarExpanded = false; annotBar.visibility = View.GONE
annotExpandBtn.text = "Annotate v"
activeTool = null; refreshAnnotSubMenu(activeAnnotGroup)
}

private fun buildFullAnnotBar(): LinearLayout {
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.parseColor("#131313"))
addView(buildSettingsPill())
val scroll = HorizontalScrollView(this@ViewerActivity).apply {
isHorizontalScrollBarEnabled = false
setPadding(dp(6), dp(4), dp(6), dp(4))
}
annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
orientation = LinearLayout.HORIZONTAL
}
scroll.addView(annotSubMenuRow); addView(scroll)
annotGroupNavBar = buildAnnotGroupNav(); addView(annotGroupNavBar)
refreshAnnotSubMenu("markup")
}
}

private fun buildSettingsPill(): FrameLayout {
val cPill = Color.parseColor("#1A1A1A"); val cDim = Color.parseColor("#2D2D2D")
val cTxt = Color.parseColor("#8B90A0"); val cBlue = Color.parseColor("#ADC6FF")
val pill = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setPadding(dp(10), dp(6), dp(10), dp(6))
background = GradientDrawable().apply { setColor(cPill); cornerRadius = dp(24).toFloat() }
elevation = dp(3).toFloat()
}
pill.addView(TextView(this).apply {
text = "W"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cTxt)
})
annotWeightValue = TextView(this).apply {
text = strokeWidth.toInt().toString(); textSize = 10f
typeface = Typeface.DEFAULT_BOLD; setTextColor(cBlue)
setPadding(dp(3), 0, dp(3), 0)
}
pill.addView(annotWeightValue)
annotWeightBar = SeekBar(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(70), dp(22))
max = 48; progress = (strokeWidth.toInt() - 2).coerceIn(0, 48)
setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
if (!fromUser) return
strokeWidth = (p + 2).toFloat()
annotWeightValue.text = (p + 2).toString()
}
override fun onStartTrackingTouch(sb: SeekBar?) {}
override fun onStopTrackingTouch(sb: SeekBar?) {}
})
}
pill.addView(annotWeightBar)
pill.addView(View(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply { setMargins(dp(6), 0, dp(6), 0) }
setBackgroundColor(cDim)
})
val cs = HorizontalScrollView(this).apply {
layoutParams = LinearLayout.LayoutParams(dp(130), dp(26))
isHorizontalScrollBarEnabled = false
}
val cr = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setPadding(dp(2), 0, dp(2), 0)
}
annotSwatchViews.clear()
COLOR_PALETTE.forEach { hex ->
val col = Color.parseColor(hex)
val sw = View(this).apply {
val sz = dp(18)
layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(dp(2), 0, dp(2), 0) }
tag = col; applySwatchStyle(this, col, col == activeColor)
setOnClickListener {
activeColor = col
if (activeTool == "highlight") highlightColor = col
annotSwatchViews.forEach { sv -> applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor) }
}
setOnLongClickListener {
highlightColor = col; activeColor = col
annotSwatchViews.forEach { sv -> applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor) }
toast("Highlight colour updated"); true
}
}
annotSwatchViews.add(sw); cr.addView(sw)
}
cs.addView(cr); pill.addView(cs)
return FrameLayout(this).apply {
layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
topMargin = dp(6); bottomMargin = dp(4)
}
addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
}
}

private fun applySwatchStyle(view: View, color: Int, isActive: Boolean) {
view.background = GradientDrawable().apply {
shape = GradientDrawable.OVAL; setColor(color)
if (isActive) setStroke(dp(2), Color.parseColor("#ADC6FF"))
}
view.alpha = if (isActive) 1f else 0.8f
}

private fun buildAnnotGroupNav(): LinearLayout {
val cActive = Color.parseColor("#ADC6FF"); val cInactive = Color.parseColor("#8B90A0")
data class GDef(val id: String, val icon: Int, val label: String)
val groups = listOf(
GDef("markup", android.R.drawable.ic_menu_edit, "MARKUP"),
GDef("shapes", android.R.drawable.ic_menu_crop, "SHAPES"),
GDef("inserts", android.R.drawable.ic_menu_add, "INSERTS"),
GDef("manage", android.R.drawable.ic_menu_agenda, "MANAGE")
)
return LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL
setBackgroundColor(Color.parseColor("#1A1A1A"))
layoutParams = LinearLayout.LayoutParams(-1, dp(50))
groups.forEach { g ->
val isActive = g.id == activeAnnotGroup
addView(LinearLayout(this@ViewerActivity).apply {
orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
if (isActive) background = GradientDrawable().apply {
colors = intArrayOf(Color.parseColor("#1A1A1A"), Color.parseColor("#2D2D2D"))
gradientType = GradientDrawable.LINEAR_GRADIENT
orientation = GradientDrawable.Orientation.TL_BR
cornerRadius = dp(8).toFloat()
}
setOnClickListener {
activeAnnotGroup = g.id; rebuildAnnotGroupNav();
refreshAnnotSubMenu(g.id)
}
addView(ImageView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
setImageResource(g.icon)
colorFilter = PorterDuffColorFilter(
if (isActive) cActive else cInactive, PorterDuff.Mode.SRC_IN
)
alpha = if (isActive) 1f else 0.65f
})
addView(TextView(this@ViewerActivity).apply {
text = g.label; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
setTextColor(if (isActive) cActive else cInactive)
alpha = if (isActive) 1f else 0.65f
gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
})
})
}
}
}

private fun rebuildAnnotGroupNav() {
if (!::annotGroupNavBar.isInitialized) return
val parent = annotGroupNavBar.parent as? LinearLayout ?: return
val idx = (0 until parent.childCount).indexOfFirst { parent.getChildAt(it) === annotGroupNavBar }
if (idx < 0) return
parent.removeViewAt(idx)
annotGroupNavBar = buildAnnotGroupNav()
parent.addView(annotGroupNavBar, idx)
}

private fun refreshAnnotSubMenu(groupId: String) {
if (!::annotSubMenuRow.isInitialized) return
annotSubMenuRow.removeAllViews()
ANNOT_GROUPS[groupId]?.forEach { toolId ->
annotSubMenuRow.addView(buildAnnotToolCell(toolId))
}
}

private fun buildAnnotToolCell(toolId: String): LinearLayout {
val isActive = toolId == activeTool
val cOn = Color.parseColor("#001A41"); val cOff = Color.parseColor("#ADC6FF")
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
layoutParams = LinearLayout.LayoutParams(dp(62), dp(68)).apply { marginEnd = dp(5) }
background = if (isActive) GradientDrawable().apply {
colors = intArrayOf(Color.parseColor("#ADC6FF"), Color.parseColor("#4B8EFF"))
gradientType = GradientDrawable.LINEAR_GRADIENT
orientation = GradientDrawable.Orientation.TL_BR
cornerRadius = dp(12).toFloat()
} else GradientDrawable().apply {
setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(12).toFloat()
}
elevation = if (isActive) dp(4).toFloat() else dp(2).toFloat()
addView(ImageView(this@ViewerActivity).apply {
layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
setImageResource(TOOL_ICON[toolId] ?: android.R.drawable.ic_menu_edit)
colorFilter = PorterDuffColorFilter(if (isActive) cOn else cOff, PorterDuff.Mode.SRC_IN)
alpha = if (isActive) 1f else 0.75f
})
addView(TextView(this@ViewerActivity).apply {
text = TOOL_LABEL[toolId] ?: toolId; textSize = 8.5f
typeface = Typeface.DEFAULT_BOLD
setTextColor(if (isActive) cOn else Color.WHITE)
alpha = if (isActive) 1f else 0.65f
gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
})
setOnClickListener { handleAnnotToolTap(toolId) }
}
}

private fun handleAnnotToolTap(toolId: String) {
when (toolId) {
"save" -> { saveAnnotations(); return }
"image" -> { showImageInsertDialog(); return }
"stamp" -> { showStampDialog(); return }
"text" -> { showTextInsertDialog(); return }
}
activeTool = if (activeTool == toolId) null else toolId
if (activeTool == "highlight") activeColor = highlightColor
val ownerGroup = ANNOT_GROUPS.entries.firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
if (ownerGroup != activeAnnotGroup) {
activeAnnotGroup = ownerGroup; rebuildAnnotGroupNav()
}
refreshAnnotSubMenu(activeAnnotGroup)
if (activeTool != null) toast("${TOOL_LABEL[toolId]} — draw on page")
}

private fun showStampDialog() {
val stamps = arrayOf("APPROVED", "REJECTED", "REVIEWED", "DRAFT", "CONFIDENTIAL", "URGENT", "PAID", "VOID", "Custom...")
AlertDialog.Builder(this).setTitle("Select Stamp").setItems(stamps) { _, i ->
if (i < stamps.size - 1) placeStampOnPage(stamps[i])
else {
val et = EditText(this).apply { hint = "Enter stamp text" }
AlertDialog.Builder(this).setTitle("Custom Stamp").setView(et)
.setPositiveButton("OK") { _, _ ->
val t = et.text.toString().trim()
if (t.isNotEmpty()) placeStampOnPage(t)
}.show()
}
}.show()
}

private fun placeStampOnPage(text: String) {
if (annotOverlays[currentPage] == null) return
val ann = Annotation(
pageIndex = currentPage, type = AnnotationType.STAMP,
points = listOf(PointF(80f, 180f)), color = Color.RED,
strokeWidth = 8f, text = text
)
annotationManager.add(ann); annotOverlays[currentPage]?.invalidate()
toast("Stamp '$text' placed")
}

// Rich text state
private var textBold = false
private var textItalic = false
private var textUnderline = false
private var textAlign = "left" // "left" | "center" | "right"
private var textFontSize = 16f

private fun showTextInsertDialog() {
val container = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
}

fun fmtBtn(label: String, isActive: Boolean, action: () -> Unit): TextView {
return TextView(this).apply {
text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
setPadding(dp(10), dp(6), dp(10), dp(6))
layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
setTextColor(if (isActive) Color.parseColor("#001A41") else Color.parseColor("#ADC6FF"))
background = GradientDrawable().apply {
setColor(if (isActive) Color.parseColor("#ADC6FF") else Color.parseColor("#2D2D2D"))
cornerRadius = dp(6).toFloat()
}
setOnClickListener { action() }
}
}

val fmtRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setPadding(0, dp(4), 0, dp(8))
}

val boldBtn = fmtBtn("B", textBold) { textBold = !textBold; showTextInsertDialog() }
val italBtn = fmtBtn("I", textItalic) { textItalic = !textItalic; showTextInsertDialog() }
val undlBtn = fmtBtn("U", textUnderline) { textUnderline = !textUnderline; showTextInsertDialog() }
fmtRow.addView(boldBtn); fmtRow.addView(italBtn); fmtRow.addView(undlBtn)

val alignRow = LinearLayout(this).apply {
orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
setPadding(0, 0, 0, dp(8))
}
listOf("L" to "left", "C" to "center", "R" to "right").forEach { (lbl, al) ->
alignRow.addView(fmtBtn(lbl, textAlign == al) { textAlign = al; showTextInsertDialog() })
}
alignRow.addView(TextView(this).apply { text = "Size:"; textSize = 11f; setTextColor(Color.parseColor("#8B90A0")); setPadding(dp(8), 0, dp(4), 0); gravity = Gravity.CENTER_VERTICAL })
val sizeEt = EditText(this).apply {
setText(textFontSize.toInt().toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER
layoutParams = LinearLayout.LayoutParams(dp(50), -2);
setTextColor(Color.WHITE); setPadding(dp(6), dp(4), dp(6), dp(4))
}
alignRow.addView(sizeEt)

val et = EditText(this).apply {
hint = "Enter annotation text"; setPadding(dp(12), dp(8), dp(12), dp(8))
setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#666888"))
textSize = textFontSize
typeface = when {
textBold && textItalic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
textBold -> Typeface.DEFAULT_BOLD
textItalic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
else -> Typeface.DEFAULT
}
gravity = when (textAlign) {
"center" -> Gravity.CENTER_HORIZONTAL; "right" -> Gravity.END; else -> Gravity.START
}
if (textUnderline) paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
}

container.addView(fmtRow); container.addView(alignRow); container.addView(et)

AlertDialog.Builder(this).setTitle("Add Text Note").setView(container)
.setPositiveButton("Place") { _, _ ->
val txt = et.text.toString().trim()
val sz = sizeEt.text.toString().toFloatOrNull() ?: textFontSize
textFontSize = sz.coerceIn(8f, 72f)
if (txt.isNotEmpty()) {
val w = resources.displayMetrics.widthPixels / 2f
val h = (pageContainer.getChildAt(currentPage)?.height?.toFloat() ?: 400f) / 2f
val fmt = "${if (textBold) "B" else ""}${if (textItalic) "I" else ""}${if (textUnderline) "U" else ""}:${textAlign}:${textFontSize.toInt()}"
val ann = Annotation(
pageIndex = currentPage, type = AnnotationType.TEXT,
points = listOf(PointF(w - dp(40), h)),
color = activeColor, strokeWidth = textFontSize, text = "$fmt|$txt"
)
annotationManager.add(ann); annotOverlays[currentPage]?.invalidate()
toast("Text placed — tap Annotate > Save to keep")
}
}.setNegativeButton("Cancel", null).show()
}

private fun showRotatePageDialog() {
val angles = arrayOf("90 degrees clockwise", "90 degrees counter-clockwise", "180 degrees", "Custom angle...")
AlertDialog.Builder(this).setTitle("Rotate Page ${currentPage + 1}").setItems(angles) { _, which ->
when (which) {
0 -> applyPageRotation(90f)
1 -> applyPageRotation(-90f)
2 -> applyPageRotation(180f)
3 -> {
val et = EditText(this).apply {
inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "Enter degrees (e.g. 45)"
setPadding(dp(16), dp(8), dp(16), dp(8))
}
AlertDialog.Builder(this).setTitle("Custom Rotation").setView(et)
.setPositiveButton("Rotate") { _, _ ->
val deg = et.text.toString().toFloatOrNull()
if (deg != null) applyPageRotation(deg) else toast("Enter a valid number")
}.setNegativeButton("Cancel", null).show()
}
}
}.show()
}

private fun applyPageRotation(degrees: Float) {
val frame = pageContainer.getChildAt(currentPage) as? FrameLayout ?: return
val zoom = frame.getChildAt(0) as? ZoomableImageView ?: return
val bmp = zoom.getRenderedBitmap() ?: return
val matrix = Matrix().apply { postRotate(degrees) }
val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)

// Replace the view (this is a simplified example)
pageContainer.removeViewAt(currentPage)
addPageView(rotated, currentPage)
toast("Page rotated ${degrees.toInt()} degrees")
}

// -------------------------------------------------------
// SAVE ANNOTATIONS
// -------------------------------------------------------
private fun saveAnnotations() {
if (!annotationManager.hasAny()) {
toast("No annotations to save"); return
}
lifecycleScope.launch {
toast("Saving annotations...")
val saved = withContext(Dispatchers.IO) {
try {
val uri = pdfUri ?: return@withContext false
val safeId = uri.toString().hashCode().toString()
val jsonFile = File(cacheDir, "annot_${safeId}.json")
jsonFile.writeText(annotationManager.toJson())

val bitmapPdf = PdfDocument()
val renderer2 = AnnotationRenderer()
for (i in 0 until pageContainer.childCount) {
val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
val zoom = frame.getChildAt(0) as? ZoomableImageView ?: continue
val bmp = zoom.getRenderedBitmap() ?: continue
val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
renderer2.render(Canvas(out), annotationManager.get(i))
val pi = PdfDocument.PageInfo.Builder(out.width, out.height, i + 1).create()
val page = bitmapPdf.startPage(pi)
page.canvas.drawBitmap(out, 0f, 0f, null)
bitmapPdf.finishPage(page); out.recycle()
}

val fileName = "annotated_${System.currentTimeMillis()}.pdf"
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
val values = ContentValues().apply {
put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
}
val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
if (outUri != null) contentResolver.openOutputStream(outUri)?.use {
bitmapPdf.writeTo(it)
}
} else {
val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
FileOutputStream(File(dir, fileName)).use { bitmapPdf.writeTo(it) }
}
bitmapPdf.close(); true
} catch (_: Exception) {
false
}
}
toast(if (saved) "Saved to Downloads/ProPDF (+ JSON cache)" else "Save failed — check storage permission")
}
}

private fun loadAnnotationsFromCache() {
lifecycleScope.launch(Dispatchers.IO) {
try {
val uri = pdfUri ?: return@launch
val safeId = uri.toString().hashCode().toString()
val file = File(cacheDir, "annot_${safeId}.json")
if (file.exists()) {
val json = file.readText()
withContext(Dispatchers.Main) {
annotationManager.fromJson(json)
annotOverlays.values.forEach { it.invalidate() }
if (annotationManager.hasAny()) toast("Annotations restored")
}
}
} catch (_: Exception) {
}
}
}

// -------------------------------------------------------
// HELPERS
// -------------------------------------------------------
private fun hideKeyboard() {
(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
}

private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

// -------------------------------------------------------
// IMAGE INSERTION
// -------------------------------------------------------
private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
uri ?: return@registerForActivityResult
lifecycleScope.launch {
val bmp = withContext(Dispatchers.IO) {
try {
contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
} catch (_: Exception) {
null
}
}
if (bmp != null) {
val scale = resources.displayMetrics.widthPixels.toFloat() / 3f
val sx = resources.displayMetrics.widthPixels / 2f - scale / 2f
val sy = (pageContainer.getChildAt(currentPage)?.height?.toFloat() ?: 400f) / 3f
val ann = Annotation(
pageIndex = currentPage, type = AnnotationType.IMAGE,
points = listOf(PointF(sx, sy), PointF(sx + scale, sy + (scale * bmp.height / bmp.width.toFloat()))),
color = Color.WHITE, strokeWidth = scale, text = uri.toString()
)
annotationManager.add(ann)
annotOverlays[currentPage]?.invalidate()
toast("Image inserted on page ${currentPage + 1}")
} else toast("Cannot load image")
}
}

private fun showImageInsertDialog() {
AlertDialog.Builder(this).setTitle("Insert Image")
.setItems(arrayOf("Pick from Gallery", "Use Camera (via Scanner)")) { _, w ->
when (w) {
0 -> imagePicker.launch("image/*")
1 -> toast("Scanner feature not implemented")
}
}.show()
}

// -------------------------------------------------------
// ZoomableImageView
// -------------------------------------------------------
inner class ZoomableImageView(context: Context, private val bmp: Bitmap, val pageIdx: Int) : FrameLayout(context) {
private val iv = ImageView(context).apply {
setImageBitmap(bmp)
scaleType = ImageView.ScaleType.FIT_CENTER
adjustViewBounds = true
}
private var scaleFactor = 1f
private var translateX = 0f
private var translateY = 0f
private var lastTouchX = 0f
private var lastTouchY = 0f
private var isDragging = false

private val scaleGD = ScaleGestureDetector(context,
object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
override fun onScale(det: ScaleGestureDetector): Boolean {
scaleFactor = (scaleFactor * det.scaleFactor).coerceIn(0.5f, 5f)
applyTransform(); return true
}
})

private val gestureDetector = GestureDetector(context,
object : GestureDetector.SimpleOnGestureListener() {
override fun onDoubleTap(e: MotionEvent): Boolean {
if (scaleFactor > 1.2f) {
scaleFactor = 1f; translateX = 0f; translateY = 0f
} else scaleFactor = 2f
applyTransform(); return true
}
})

init { addView(iv) }

private fun applyTransform() {
iv.scaleX = scaleFactor; iv.scaleY = scaleFactor
iv.translationX = translateX; iv.translationY = translateY
}

fun applyFilter(filter: ColorMatrixColorFilter?) {
iv.colorFilter = filter
}

fun getRenderedBitmap(): Bitmap? = bmp.takeIf { !it.isRecycled }

override fun onTouchEvent(ev: MotionEvent): Boolean {
gestureDetector.onTouchEvent(ev); scaleGD.onTouchEvent(ev)
if (scaleGD.isInProgress) return true
when (ev.actionMasked) {
MotionEvent.ACTION_DOWN -> {
lastTouchX = ev.x; lastTouchY = ev.y; isDragging = false
}
MotionEvent.ACTION_MOVE -> {
if (scaleFactor > 1.1f) {
val dx = ev.x - lastTouchX; val dy = ev.y - lastTouchY
if (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3)) {
translateX += dx; translateY += dy
lastTouchX = ev.x; lastTouchY = ev.y
isDragging = true; applyTransform()
}
}
}
MotionEvent.ACTION_UP -> if (!isDragging && scaleFactor <= 1.1f) return false
}
return scaleFactor > 1.1f || scaleGD.isInProgress
}
}

// -------------------------------------------------------
// COMPANION
// -------------------------------------------------------
companion object {
const val EXTRA_URI = "extra_pdf_uri"
const val EXTRA_PASSWORD = "extra_pdf_password"

fun start(context: Context, uri: Uri, password: String? = null) {
context.startActivity(Intent(context, ViewerActivity::class.java).apply {
putExtra(EXTRA_URI, uri.toString())
if (password != null) putExtra(EXTRA_PASSWORD, password)
})
}
}
}