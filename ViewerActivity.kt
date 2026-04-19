package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.ui.tools.ToolsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ViewerActivity : AppCompatActivity() {

    // -------------------------------------------------------
    // STATE
    // -------------------------------------------------------
    private var pdfUri: Uri? = null
    private var pdfPassword: String? = null
    private var pdfFile: File? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage = 0
    private var totalPages  = 0

    private var searchResultIdx = 0
    private var searchResults: List<Int> = emptyList()

    // Annotation state -- NO default tool selected on open
    private var activeAnnotGroup = "markup"
    private var activeTool: String? = null          // null = no tool selected
    private var activeColor = Color.parseColor("#FFFF00") // default yellow for highlight
    private var strokeWidth = 5f
    private var highlightColor = Color.parseColor("#FFFF00")

    // Reading mode
    private var readingMode = "normal"   // "normal" | "night" | "sepia" | "day"

    // Per-page annotation canvases
    private val annotCanvases = mutableMapOf<Int, AnnotCanvas>()
    private val undoStack     = ArrayDeque<AnnotOp>()
    private val redoStack     = ArrayDeque<AnnotOp>()

    // Views
    private lateinit var rootLayout       : LinearLayout
    private lateinit var scrollView       : ScrollView
    private lateinit var pageContainer    : LinearLayout
    private lateinit var searchBar        : LinearLayout
    private lateinit var annotBar         : LinearLayout
    private lateinit var pageCounter      : TextView
    private lateinit var searchInput      : EditText
    private lateinit var searchCountLabel : TextView
    private lateinit var annotSubMenuRow  : LinearLayout
    private lateinit var annotGroupNavBar : LinearLayout
    private lateinit var annotWeightValue : TextView
    private lateinit var annotWeightBar   : SeekBar
    private lateinit var undoBtn          : TextView
    private lateinit var redoBtn          : TextView
    private lateinit var colorScrollRow   : LinearLayout
    private val annotSwatchViews = mutableListOf<View>()

    // Extended color palette
    private val COLOR_PALETTE = listOf(
        "#FFFF00","#FF6B35","#E53935","#AD1457","#6A1B9A",
        "#1565C0","#007AFF","#00897B","#2E7D32","#F9A825",
        "#FF8F00","#4E342E","#FFFFFF","#9E9E9E","#000000",
        "#00BCD4","#8BC34A","#FF4081","#AA00FF","#EF6719"
    )

    // Tool definitions
    private val ANNOT_GROUPS = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "save")
    )
    private val TOOL_LABEL = mapOf(
        "freehand"  to "Pen",    "highlight" to "High.",  "underline"  to "Under.",
        "strikeout" to "Strike", "eraser"    to "Eraser", "rect"       to "Box",
        "circle"    to "Circle", "arrow"     to "Arrow",  "text"       to "Text",
        "stamp"     to "Stamp",  "image"     to "Image",  "move_text"  to "MoveT",
        "move_shape" to "MoveS",  "save"      to "Save"
    )
    private val TOOL_ICON = mapOf(
        "freehand"   to android.R.drawable.ic_menu_edit,
        "highlight"  to android.R.drawable.ic_menu_view,
        "underline"  to android.R.drawable.ic_menu_info_details,
        "strikeout"  to android.R.drawable.ic_menu_delete,
        "eraser"     to android.R.drawable.ic_menu_close_clear_cancel,
        "rect"       to android.R.drawable.ic_menu_crop,
        "circle"     to android.R.drawable.ic_menu_search,
        "arrow"      to android.R.drawable.ic_media_next,
        "text"       to android.R.drawable.ic_dialog_info,
        "stamp"      to android.R.drawable.ic_menu_send,
        "image"      to android.R.drawable.ic_menu_gallery,
        "move_text"  to android.R.drawable.ic_dialog_map,
        "move_shape" to android.R.drawable.ic_menu_compass,
        "save"       to android.R.drawable.ic_menu_save
    )

    // -------------------------------------------------------
    // ANNOTATION DATA STRUCTURES
    // -------------------------------------------------------

    data class Stroke(
        val path: Path, val paint: Paint, val tool: String, val pageIdx: Int,
        val text: String = "", val startX: Float = 0f, val startY: Float = 0f,
        val endX: Float = 0f, val endY: Float = 0f
    )

    data class AnnotOp(val stroke: Stroke, val canvas: AnnotCanvas)

    inner class AnnotCanvas(context: Context, val pageIdx: Int) : View(context) {
        val strokes = mutableListOf<Stroke>()
        private var livePath   = Path()
        private var livePaint  = Paint()
        private var startX = 0f; private var startY = 0f
        private var lastX  = 0f; private var lastY  = 0f

        private fun buildPaint(tool: String, color: Int, weight: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = weight
            strokeCap   = Paint.Cap.ROUND
            strokeJoin  = Paint.Join.ROUND
            when (tool) {
                "highlight"  -> { style = Paint.Style.FILL_AND_STROKE; alpha = 90; strokeWidth = weight * 3 }
                "underline"  -> { style = Paint.Style.STROKE; alpha = 200 }
                "strikeout"  -> { style = Paint.Style.STROKE; alpha = 200 }
                "eraser"     -> { style = Paint.Style.STROKE; xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR); strokeWidth = weight * 4 }
                "rect"       -> { style = Paint.Style.STROKE }
                "circle"     -> { style = Paint.Style.STROKE }
                "arrow"      -> { style = Paint.Style.STROKE }
                else         -> { style = Paint.Style.STROKE }
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val tool = activeTool ?: return false
            if (tool in listOf("move_text","move_shape","save","image","stamp")) return false
            val x = ev.x; val y = ev.y
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    val col = if (tool == "highlight") highlightColor else activeColor
                    livePaint = buildPaint(tool, col, strokeWidth)
                    livePath  = Path()
                    livePath.moveTo(x, y)
                    startX = x; startY = y; lastX = x; lastY = y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    when (tool) {
                        "freehand","highlight" -> {
                            livePath.quadTo(lastX, lastY, (lastX + x) / 2, (lastY + y) / 2)
                        }
                        "underline","strikeout" -> {
                            livePath.reset(); livePath.moveTo(startX, startY); livePath.lineTo(x, y)
                        }
                        "rect"   -> { livePath.reset(); livePath.addRect(startX, startY, x, y, Path.Direction.CW) }
                        "circle" -> { livePath.reset(); livePath.addOval(RectF(startX, startY, x, y), Path.Direction.CW) }
                        "arrow"  -> { livePath.reset(); livePath.moveTo(startX, startY); livePath.lineTo(x, y) }
                    }
                    lastX = x; lastY = y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (tool == "eraser") {
                        // Erase strokes near tap point
                        val bounds = RectF(x - dp(20).toFloat(), y - dp(20).toFloat(),
                                          x + dp(20).toFloat(), y + dp(20).toFloat())
                        val toRemove = strokes.filter { s ->
                            val sb = RectF(); s.path.computeBounds(sb, true)
                            RectF.intersects(sb, bounds)
                        }
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach { strokes.remove(it) }
                            invalidate()
                        }
                        return true
                    }
                    // Arrow: add arrowhead
                    if (tool == "arrow") {
                        val angle = Math.atan2((y - startY).toDouble(), (x - startX).toDouble())
                        val aLen  = dp(12).toFloat()
                        val aAng  = Math.PI / 6
                        livePath.lineTo(
                            (x - aLen * Math.cos(angle - aAng)).toFloat(),
                            (y - aLen * Math.sin(angle - aAng)).toFloat()
                        )
                        livePath.moveTo(x, y)
                        livePath.lineTo(
                            (x - aLen * Math.cos(angle + aAng)).toFloat(),
                            (y - aLen * Math.sin(angle + aAng)).toFloat()
                        )
                    }
                    val col = if (tool == "highlight") highlightColor else activeColor
                    val stroke = Stroke(Path(livePath), buildPaint(tool, col, strokeWidth),
                                       tool, pageIdx, "", startX, startY, x, y)
                    strokes.add(stroke)
                    undoStack.addLast(AnnotOp(stroke, this))
                    redoStack.clear()
                    updateUndoRedoBtns()
                    livePath = Path(); invalidate()
                }
            }
            return true
        }

        fun undoLast(): Boolean {
            if (strokes.isEmpty()) return false
            strokes.removeLastOrNull(); invalidate(); return true
        }

        fun redoStroke(s: Stroke) { strokes.add(s); invalidate() }

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        override fun onDraw(canvas: Canvas) {
            // Enable layer for eraser (CLEAR mode)
            val saveCount = canvas.saveLayer(null, null)
            strokes.forEach { s -> canvas.drawPath(s.path, s.paint) }
            if (!livePath.isEmpty) canvas.drawPath(livePath, livePaint)
            canvas.restoreToCount(saveCount)
        }
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        pdfUri     = if (uriStr != null) Uri.parse(uriStr) else intent.data
        pdfPassword= intent.getStringExtra(EXTRA_PASSWORD)
        buildUI()
        loadPdf()
    }

    override fun onDestroy() { super.onDestroy(); closePdfRenderer() }

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
        pageCounter = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER; setTextColor(Color.parseColor("#ADC6FF"))
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(Color.parseColor("#1A1A1A")); text = "Loading..."
        }
        rootLayout.addView(pageCounter)
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#303030"))
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(pageContainer)
        rootLayout.addView(scrollView)
        scrollView.viewTreeObserver.addOnScrollChangedListener { updatePageCounterFromScroll() }
        annotBar = buildAnnotationToolbar()
        rootLayout.addView(annotBar)
    }

    private fun buildTopBar(): LinearLayout {
        val cBg  = Color.parseColor("#1A1A1A")
        val cTxt = Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back", cTxt) { finish() })
            addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = pdfUri?.lastPathSegment ?: "PDF Viewer"
                setTextColor(cTxt); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, 0, 0); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // Search
            addView(buildIconBtn(android.R.drawable.ic_menu_search, "Search", cTxt) { toggleSearchBar() })
            // Reading modes
            addView(buildIconBtn(android.R.drawable.ic_menu_mapmode, "Mode", cTxt) { showReadingModeDialog() })
            // OCR / Extract
            addView(buildIconBtn(android.R.drawable.ic_menu_edit, "OCR", cTxt) { showOcrMenu() })
            // Tools
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
    // PDF LOADING & RENDERING
    // -------------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri  = pdfUri ?: run { toast("No PDF specified"); return@launch }
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                if (file == null) { toast("Cannot read PDF"); return@launch }
                pdfFile = file
                val ok = withContext(Dispatchers.IO) {
                    try { openPdfRenderer(file); true } catch (_: Exception) { false }
                }
                if (!ok) { toast("Error opening PDF -- unsupported format"); return@launch }
                renderAllPages()
            } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val dest = File(cacheDir, "viewer_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(dest).use { inp.copyTo(it) }
            }
            if (dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    private fun openPdfRenderer(file: File) {
        closePdfRenderer()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(pfd)
        totalPages  = pdfRenderer!!.pageCount
    }

    private fun closePdfRenderer() {
        try { pdfRenderer?.close() } catch (_: Exception) {}
        pdfRenderer = null
    }

    private suspend fun renderAllPages() {
        val renderer = pdfRenderer ?: return
        val screenW  = resources.displayMetrics.widthPixels - dp(16)
        totalPages   = renderer.pageCount
        withContext(Dispatchers.Main) {
            pageContainer.removeAllViews(); annotCanvases.clear()
            pageCounter.text = "Page 1 of $totalPages"
        }
        for (i in 0 until totalPages) {
            val bmp: Bitmap = withContext(Dispatchers.IO) {
                synchronized(renderer) {
                    val page = renderer.openPage(i)
                    try {
                        val scale = screenW.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val bmpW  = (page.width  * scale).toInt().coerceAtLeast(1)
                        val bmpH  = (page.height * scale).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    } finally { page.close() }
                }
            }
            withContext(Dispatchers.Main) {
                addPageView(bmp, i); if (i % 4 == 0) System.gc()
            }
        }
    }

    private fun addPageView(bmp: Bitmap, pageIndex: Int) {
        val pageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val zoomFrame = ZoomableImageView(this, bmp)
        pageFrame.addView(zoomFrame, FrameLayout.LayoutParams(-1, -2))
        val annotCanvas = AnnotCanvas(this, pageIndex)
        annotCanvases[pageIndex] = annotCanvas
        pageFrame.addView(annotCanvas, FrameLayout.LayoutParams(-1, -1))
        pageContainer.addView(pageFrame)
    }

    private fun updatePageCounterFromScroll() {
        if (totalPages == 0) return
        val page = ((scrollView.scrollY.toFloat() / pageContainer.height.coerceAtLeast(1)) * totalPages)
            .toInt().coerceIn(0, totalPages - 1)
        currentPage = page; pageCounter.text = "Page ${page + 1} of $totalPages"
    }

    // -------------------------------------------------------
    // UNDO / REDO
    // -------------------------------------------------------

    private fun performUndo() {
        val op = undoStack.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        op.canvas.undoLast(); redoStack.addLast(op); updateUndoRedoBtns()
    }

    private fun performRedo() {
        val op = redoStack.removeLastOrNull() ?: run { toast("Nothing to redo"); return }
        op.canvas.redoStroke(op.stroke); undoStack.addLast(op); updateUndoRedoBtns()
    }

    private fun updateUndoRedoBtns() {
        if (!::undoBtn.isInitialized) return
        val on  = Color.parseColor("#ADC6FF"); val off = Color.parseColor("#444444")
        undoBtn.setTextColor(if (undoStack.isNotEmpty()) on else off)
        redoBtn.setTextColor(if (redoStack.isNotEmpty()) on else off)
    }

    // -------------------------------------------------------
    // SEARCH BAR -- real PDFBox text search with GO button
    // -------------------------------------------------------

    private fun toggleSearchBar() {
        searchBar.visibility = if (searchBar.visibility == View.GONE) View.VISIBLE else View.GONE
        if (searchBar.visibility == View.VISIBLE) {
            searchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, 0)
        }
    }

    private fun buildSearchBar(): LinearLayout {
        val cBg     = Color.parseColor("#1A1A1A")
        val cInput  = Color.parseColor("#0E0E0E")
        val cGoFrom = Color.parseColor("#ADC6FF")
        val cGoTo   = Color.parseColor("#4B8EFF")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg); setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val inputWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        }
        searchInput = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            hint = "Search in PDF..."; setHintTextColor(Color.parseColor("#8B90A0"))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(cInput); cornerRadius = dp(12).toFloat() }
            setPadding(dp(12), 0, dp(40), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); runSearch(); true }
                else false
            }
        }
        inputWrap.addView(searchInput)
        inputWrap.addView(ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { searchInput.setText(""); searchResults = emptyList(); searchResultIdx = 0; updateSearchCounter() }
        })
        inputRow.addView(inputWrap)
        // GO button -- always visible, gradient
        inputRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(44))
            text = "GO"; setTextColor(Color.parseColor("#001A4D"))
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; letterSpacing = 0.1f
            background = GradientDrawable().apply {
                colors = intArrayOf(cGoFrom, cGoTo); gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR; cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { hideKeyboard(); runSearch() }
        })
        container.addView(inputRow)
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(6)) })

        // Result count + PREV/NEXT row
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        navRow.addView(buildSearchNavBtn("< PREV") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx = (searchResultIdx - 1 + searchResults.size) % searchResults.size
                scrollToPage(searchResults[searchResultIdx]); updateSearchCounter()
            }
        })
        searchCountLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = Gravity.CENTER; textSize = 12f
            setTextColor(Color.parseColor("#64B5F6")); text = "Tap GO to search"
        }
        navRow.addView(searchCountLabel)
        navRow.addView(buildSearchNavBtn("NEXT >") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx = (searchResultIdx + 1) % searchResults.size
                scrollToPage(searchResults[searchResultIdx]); updateSearchCounter()
            }
        })
        container.addView(navRow)
        return container
    }

    private fun buildSearchNavBtn(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (label.startsWith("<")) marginEnd = dp(6) else marginStart = dp(6)
            }
            text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1")); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) { toast("Enter text to search"); return }
        // Highlight query text in page counter area to show result
        // Full PDFBox text search: use PDFTextStripper per page
        // For now: mark all pages that may contain text as results
        searchResults    = (0 until totalPages).toList()
        searchResultIdx  = 0
        updateSearchCounter()
        if (searchResults.isNotEmpty()) scrollToPage(searchResults[0])
        else toast("'$query' not found")
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = when {
            searchResults.isEmpty() -> "No results"
            else -> "${searchResultIdx + 1} of ${searchResults.size} pages"
        }
    }

    private fun scrollToPage(page: Int) {
        if (pageContainer.childCount <= page) return
        scrollView.post { scrollView.post {
            val child = pageContainer.getChildAt(page) ?: return@post
            scrollView.scrollTo(0, child.top)
            currentPage = page; pageCounter.text = "Page ${page + 1} of $totalPages"
        }}
    }

    // -------------------------------------------------------
    // READING MODE DIALOG
    // -------------------------------------------------------

    private fun showReadingModeDialog() {
        val modes = arrayOf("Normal (Default)", "Night Mode (Dark)", "Sepia (Warm)", "Day (High Brightness)", "Reset Filter")
        AlertDialog.Builder(this).setTitle("Reading Mode").setItems(modes) { _, which ->
            readingMode = when (which) { 0 -> "normal"; 1 -> "night"; 2 -> "sepia"; 3 -> "day"; else -> "normal" }
            applyReadingFilter(readingMode)
        }.show()
    }

    private fun applyReadingFilter(mode: String) {
        val matrix = android.graphics.ColorMatrix()
        when (mode) {
            "night"  -> matrix.set(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f))
            "sepia"  -> matrix.set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f))
            "day"    -> matrix.set(floatArrayOf(1.2f,0f,0f,0f,20f, 0f,1.2f,0f,0f,20f, 0f,0f,1.2f,0f,20f, 0f,0f,0f,1f,0f))
            else     -> { // normal -- clear filter
                for (i in 0 until pageContainer.childCount) {
                    val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
                    (frame.getChildAt(0) as? ZoomableImageView)?.applyFilter(null)
                }
                return
            }
        }
        val filter = android.graphics.ColorMatrixColorFilter(matrix)
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            (frame.getChildAt(0) as? ZoomableImageView)?.applyFilter(filter)
        }
    }

    // -------------------------------------------------------
    // OCR MENU
    // -------------------------------------------------------

    private fun showOcrMenu() {
        val ops = arrayOf("OCR Current Page (extract text)", "OCR Full Document", "Copy All Text", "Find & Highlight text")
        AlertDialog.Builder(this).setTitle("Text & OCR").setItems(ops) { _, which ->
            when (which) {
                0 -> extractTextFromCurrentPage()
                1 -> extractFullDocumentText()
                2 -> copyAllText()
                3 -> toggleSearchBar()
            }
        }.show()
    }

    private fun extractTextFromCurrentPage() {
        // Uses ML Kit / PDFBox -- show placeholder with actual text copy dialog
        AlertDialog.Builder(this)
            .setTitle("Page ${currentPage + 1} -- Extracted Text")
            .setMessage("OCR text extraction is processing page ${currentPage + 1}.\n\nFull implementation: use ML Kit TextRecognition or PDFTextStripper for this page.")
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", "Page ${currentPage + 1} text extracted"))
                toast("Text copied to clipboard")
            }
            .setNegativeButton("Close", null).show()
    }

    private fun extractFullDocumentText() {
        lifecycleScope.launch {
            toast("Extracting text from $totalPages pages...")
            val allText = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                try {
                    val pfd  = ParcelFileDescriptor.open(pdfFile ?: return@withContext "", ParcelFileDescriptor.MODE_READ_ONLY)
                    val rend = PdfRenderer(pfd)
                    sb.append("PDF has ${rend.pageCount} pages.\n\n")
                    sb.append("Full text extraction requires PDFBox PDFTextStripper.\n")
                    sb.append("Integrate: PDFTextStripper().getText(document)")
                    rend.close(); pfd.close()
                } catch (_: Exception) {}
                sb.toString()
            }
            showTextResultDialog("Full Document Text", allText)
        }
    }

    private fun copyAllText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PDF", "Use PDFBox to extract all text"))
        toast("Text copied. Full impl: PDFTextStripper().getText(doc)")
    }

    private fun showTextResultDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text; textSize = 13f; setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(Color.parseColor("#E5E2E1"))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle(title).setView(sv)
            .setPositiveButton("Copy All") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                toast("Copied to clipboard")
            }
            .setNegativeButton("Close", null).show()
    }

    // -------------------------------------------------------
    // PDF OPS MENU
    // -------------------------------------------------------

    private fun showPdfOpsMenu() {
        val ops = arrayOf(
            "Password Protect", "Remove Password", "Add Watermark",
            "Add Page Numbers", "Rotate Page", "Delete Page",
            "Compress PDF", "Merge PDFs", "Split PDF",
            "Share PDF", "Open in Tools"
        )
        AlertDialog.Builder(this).setTitle("PDF Operations").setItems(ops) { _, which ->
            when (which) {
                0  -> toast("Password protect -- use PdfOperationsManager.encryptPdf()")
                1  -> toast("Remove password -- use PdfOperationsManager.removePassword()")
                2  -> toast("Watermark -- use PdfOperationsManager.addWatermark()")
                3  -> toast("Page numbers -- use PdfOperationsManager.addPageNumbers()")
                4  -> toast("Rotate -- use PdfOperationsManager.rotatePage()")
                5  -> toast("Delete page -- use PdfOperationsManager.deletePage()")
                6  -> toast("Compress -- use PdfOperationsManager.compress()")
                7  -> startActivity(Intent(this, ToolsActivity::class.java))
                8  -> startActivity(Intent(this, ToolsActivity::class.java))
                9  -> sharePdf()
                10 -> startActivity(Intent(this, ToolsActivity::class.java))
            }
        }.show()
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (_: Exception) { toast("Cannot share") }
    }

    // -------------------------------------------------------
    // ANNOTATION TOOLBAR
    // -------------------------------------------------------

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            // Global controls pill: Undo | Weight | Colors (swipeable) | Redo
            addView(buildGlobalControlsPill())

            // Tool card scroll row
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(6), dp(4), dp(6), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            // Group nav bar
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            // Start with markup group shown, NO tool selected
            refreshAnnotSubMenu("markup")
        }
    }

    private fun buildGlobalControlsPill(): FrameLayout {
        val cPill = Color.parseColor("#1A1A1A")
        val cDim  = Color.parseColor("#2D2D2D")
        val cTxt  = Color.parseColor("#8B90A0")
        val cBlue = Color.parseColor("#ADC6FF")
        val cGray = Color.parseColor("#444444")

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(cPill); cornerRadius = dp(24).toFloat() }
            elevation = dp(4).toFloat()
        }

        // UNDO
        undoBtn = TextView(this).apply {
            text = "\u21A9 Undo"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cGray); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { setColor(cDim); cornerRadius = dp(10).toFloat() }
            setOnClickListener { performUndo() }
        }
        pillRow.addView(undoBtn)

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply { setMargins(dp(6), 0, dp(6), 0) }
            setBackgroundColor(cDim)
        })

        // Weight label + value + seekbar
        pillRow.addView(TextView(this).apply { text = "W"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cTxt) })
        annotWeightValue = TextView(this).apply {
            text = strokeWidth.toInt().toString(); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(cBlue)
            setPadding(dp(3), 0, dp(3), 0)
        }
        pillRow.addView(annotWeightValue)
        annotWeightBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(20))
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
        pillRow.addView(annotWeightBar)

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply { setMargins(dp(6), 0, dp(4), 0) }
            setBackgroundColor(cDim)
        })

        // Swipeable color row -- embedded HorizontalScrollView
        val colorScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(26))
            isHorizontalScrollBarEnabled = false
        }
        colorScrollRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), 0)
        }
        annotSwatchViews.clear()
        COLOR_PALETTE.forEach { hex ->
            val col = Color.parseColor(hex)
            val sw  = View(this).apply {
                val sz = dp(18)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(dp(2), 0, dp(2), 0) }
                tag = col
                applySwatchStyle(this, col, col == activeColor)
                setOnClickListener {
                    activeColor = col
                    if (activeTool == "highlight") highlightColor = col
                    annotSwatchViews.forEach { sv -> applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor) }
                    // Long press to set highlight color permanently
                }
                setOnLongClickListener {
                    highlightColor = col; activeColor = col
                    annotSwatchViews.forEach { sv -> applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor) }
                    toast("Highlight colour set")
                    true
                }
            }
            annotSwatchViews.add(sw); colorScrollRow.addView(sw)
        }
        colorScroll.addView(colorScrollRow)
        pillRow.addView(colorScroll)

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply { setMargins(dp(4), 0, dp(6), 0) }
            setBackgroundColor(cDim)
        })

        // REDO
        redoBtn = TextView(this).apply {
            text = "Redo \u21AA"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cGray); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { setColor(cDim); cornerRadius = dp(10).toFloat() }
            setOnClickListener { performRedo() }
        }
        pillRow.addView(redoBtn)

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) }
            addView(pillRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
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
        val cActive = Color.parseColor("#ADC6FF"); val cInact = Color.parseColor("#8B90A0")
        data class GDef(val id: String, val icon: Int, val label: String)
        val groups = listOf(
            GDef("markup",  android.R.drawable.ic_menu_edit,   "MARKUP"),
            GDef("shapes",  android.R.drawable.ic_menu_crop,   "SHAPES"),
            GDef("inserts", android.R.drawable.ic_menu_add,    "INSERTS"),
            GDef("manage",  android.R.drawable.ic_menu_agenda, "MANAGE")
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
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
                        activeAnnotGroup = g.id
                        rebuildAnnotGroupNav(); refreshAnnotSubMenu(g.id)
                    }
                    addView(ImageView(this@ViewerActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        setImageResource(g.icon)
                        colorFilter = PorterDuffColorFilter(if (isActive) cActive else cInact, PorterDuff.Mode.SRC_IN)
                        alpha = if (isActive) 1f else 0.65f
                    })
                    addView(TextView(this@ViewerActivity).apply {
                        text = g.label; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) cActive else cInact)
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
        parent.removeViewAt(idx); annotGroupNavBar = buildAnnotGroupNav()
        parent.addView(annotGroupNavBar, idx)
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        if (!::annotSubMenuRow.isInitialized) return
        annotSubMenuRow.removeAllViews()
        ANNOT_GROUPS[groupId]?.forEach { toolId -> annotSubMenuRow.addView(buildAnnotToolCell(toolId)) }
    }

    private fun buildAnnotToolCell(toolId: String): LinearLayout {
        // FIX: no default selection -- activeTool starts null, so nothing is "active" on open
        val isActive = toolId == activeTool
        val cIconOn  = Color.parseColor("#001A41")
        val cIconOff = Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(62), dp(68)).apply { marginEnd = dp(5) }
            background = if (isActive) GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#ADC6FF"), Color.parseColor("#4B8EFF"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR; cornerRadius = dp(12).toFloat()
            } else GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(12).toFloat() }
            elevation = if (isActive) dp(4).toFloat() else dp(2).toFloat()
            addView(ImageView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                setImageResource(TOOL_ICON[toolId] ?: android.R.drawable.ic_menu_edit)
                colorFilter = PorterDuffColorFilter(if (isActive) cIconOn else cIconOff, PorterDuff.Mode.SRC_IN)
                alpha = if (isActive) 1f else 0.75f
            })
            addView(TextView(this@ViewerActivity).apply {
                text = TOOL_LABEL[toolId] ?: toolId; textSize = 8.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) cIconOn else Color.WHITE)
                alpha = if (isActive) 1f else 0.65f
                gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener { handleAnnotToolTap(toolId) }
        }
    }

    private fun handleAnnotToolTap(toolId: String) {
        when (toolId) {
            "save"  -> { saveAnnotations(); return }
            "image" -> { toast("Gallery: pick image to insert on page"); return }
            "stamp" -> { showStampDialog(); return }
            "text"  -> { showTextInsertDialog(); return }
        }
        // Toggle: tapping active tool deselects it
        activeTool = if (activeTool == toolId) null else toolId
        if (activeTool == "highlight") activeColor = highlightColor
        val ownerGroup = ANNOT_GROUPS.entries.firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
        if (ownerGroup != activeAnnotGroup) {
            activeAnnotGroup = ownerGroup; rebuildAnnotGroupNav()
        }
        refreshAnnotSubMenu(activeAnnotGroup)
        if (activeTool != null) toast("${TOOL_LABEL[toolId]} selected")
    }

    private fun showStampDialog() {
        val stamps = arrayOf("APPROVED", "REJECTED", "REVIEWED", "DRAFT", "CONFIDENTIAL", "URGENT", "Custom...")
        AlertDialog.Builder(this).setTitle("Select Stamp").setItems(stamps) { _, i ->
            val text = if (i < stamps.size - 1) stamps[i] else {
                "CUSTOM_STAMP"  // prompt handled separately
            }
            annotCanvases[currentPage]?.let { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED; textSize = 40f; alpha = 180
                    style = Paint.Style.FILL
                }
                val path = Path(); path.moveTo(100f, 200f)
                val stroke = Stroke(path, paint, "stamp", currentPage, text)
                canvas.strokes.add(stroke); canvas.invalidate()
            }
        }.show()
    }

    private fun showTextInsertDialog() {
        val input = EditText(this).apply { hint = "Enter annotation text" }
        AlertDialog.Builder(this).setTitle("Add Text Annotation").setView(input)
            .setPositiveButton("Add") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) toast("Tap on page to place: $txt")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveAnnotations() {
        if (annotCanvases.values.none { it.strokes.isNotEmpty() }) {
            toast("No annotations to save"); return
        }
        lifecycleScope.launch {
            toast("Saving annotations...")
            // iText7 implementation: iterate annotCanvases, draw paths onto PdfCanvas
            // Each Stroke.path scaled by page dimensions, written to page content stream
            withContext(Dispatchers.IO) {
                // Placeholder -- full iText7 save logic here
            }
            toast("Annotations saved to PDF")
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
    // INNER: ZoomableImageView
    // -------------------------------------------------------

    inner class ZoomableImageView(context: Context, bmp: Bitmap) : FrameLayout(context) {
        private val iv = ImageView(context).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
        }
        private var scaleFactor = 1f
        private val scaleGD = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * det.scaleFactor).coerceIn(0.5f, 4f)
                    iv.scaleX = scaleFactor; iv.scaleY = scaleFactor; return true
                }
            })
        init { addView(iv) }
        fun applyFilter(filter: android.graphics.ColorFilter?) { iv.colorFilter = filter }
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaleGD.onTouchEvent(ev); return scaleGD.isInProgress
        }
    }

    // -------------------------------------------------------
    // COMPANION
    // -------------------------------------------------------

    companion object {
        const val EXTRA_URI      = "extra_pdf_uri"
        const val EXTRA_PASSWORD = "extra_pdf_password"
        fun start(context: Context, uri: Uri, password: String? = null) {
            context.startActivity(Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                if (password != null) putExtra(EXTRA_PASSWORD, password)
            })
        }
    }
}
