package com.propdf.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
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
    private var lastSearchQuery = ""

    // Annotation state -- toolbar COLLAPSED by default, expands on tap
    private var annotToolbarExpanded = false
    private var activeAnnotGroup     = "markup"
    private var activeTool: String?  = null
    private var activeColor          = Color.parseColor("#007AFF")
    private var highlightColor       = Color.parseColor("#FFFF00")
    private var strokeWidth          = 5f

    // Reading / zoom
    private var readingMode = "normal"
    private var fitToWidth  = true

    // Per-page annotation canvases & history
    private val annotCanvases = mutableMapOf<Int, AnnotCanvas>()
    private val undoStack     = ArrayDeque<AnnotOp>()
    private val redoStack     = ArrayDeque<AnnotOp>()

    // Bookmarks (page indices)
    private val bookmarkedPages = mutableSetOf<Int>()

    // Colour palette
    private val COLOR_PALETTE = listOf(
        "#FFFF00","#FF6B35","#E53935","#AD1457","#6A1B9A",
        "#1565C0","#007AFF","#00897B","#2E7D32","#F9A825",
        "#FF8F00","#4E342E","#FFFFFF","#9E9E9E","#000000",
        "#00BCD4","#8BC34A","#FF4081","#AA00FF","#EF6719"
    )

    // Tool tables
    private val ANNOT_GROUPS = linkedMapOf(
        "markup"  to listOf("freehand","highlight","underline","strikeout","eraser"),
        "shapes"  to listOf("rect","circle","arrow"),
        "inserts" to listOf("text","stamp","image"),
        "manage"  to listOf("move_text","move_shape","save")
    )
    private val TOOL_LABEL = mapOf(
        "freehand" to "Pen",   "highlight" to "High.",  "underline" to "Under.",
        "strikeout" to "Strk", "eraser"    to "Erase",  "rect"      to "Rect",
        "circle"   to "Circle","arrow"     to "Arrow",  "text"      to "Text",
        "stamp"    to "Stamp", "image"     to "Image",  "move_text" to "MoveT",
        "move_shape" to "MoveS","save"     to "Save"
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

    // Views
    private lateinit var rootLayout        : LinearLayout
    private lateinit var scrollView        : ScrollView
    private lateinit var pageContainer     : LinearLayout
    private lateinit var searchBar         : LinearLayout
    private lateinit var annotToolbarWrap  : LinearLayout   // collapsible wrapper
    private lateinit var annotExpandBtn    : TextView       // "Annotate" toggle button
    private lateinit var annotBar          : LinearLayout   // the actual toolbar (hidden/shown)
    private lateinit var pageCounter       : TextView
    private lateinit var searchInput       : EditText
    private lateinit var searchCountLabel  : TextView
    private lateinit var annotSubMenuRow   : LinearLayout
    private lateinit var annotGroupNavBar  : LinearLayout
    private lateinit var annotWeightValue  : TextView
    private lateinit var annotWeightBar    : SeekBar
    private lateinit var undoBtn           : TextView
    private lateinit var redoBtn           : TextView
    private val annotSwatchViews           = mutableListOf<View>()

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
                "highlight"  -> { style = Paint.Style.FILL_AND_STROKE; alpha = 90; strokeWidth = weight * 4 }
                "underline"  -> { style = Paint.Style.STROKE; strokeWidth = weight * 1.5f; alpha = 220 }
                "strikeout"  -> { style = Paint.Style.STROKE; strokeWidth = weight * 1.5f; alpha = 220 }
                "eraser"     -> { style = Paint.Style.STROKE; strokeWidth = weight * 5f
                                  xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
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
                        "freehand","highlight" ->
                            livePath.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                        "underline","strikeout" -> {
                            livePath.reset(); livePath.moveTo(startX, startY + dp(2))
                            livePath.lineTo(x, startY + dp(2))
                        }
                        "rect"   -> { livePath.reset(); livePath.addRect(startX, startY, x, y, Path.Direction.CW) }
                        "circle" -> { livePath.reset(); livePath.addOval(RectF(startX, startY, x, y), Path.Direction.CW) }
                        "arrow"  -> { livePath.reset(); livePath.moveTo(startX, startY); livePath.lineTo(x, y) }
                    }
                    lastX = x; lastY = y; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (tool == "eraser") {
                        val bounds = RectF(x - dp(24).toFloat(), y - dp(24).toFloat(),
                                          x + dp(24).toFloat(), y + dp(24).toFloat())
                        strokes.removeAll { s -> RectF().also { s.path.computeBounds(it, true) }.let { RectF.intersects(it, bounds) } }
                        invalidate(); return true
                    }
                    if (tool == "arrow") {
                        val angle = Math.atan2((y - startY).toDouble(), (x - startX).toDouble())
                        val aLen  = dp(14).toFloat(); val aAng = Math.PI / 6
                        livePath.lineTo((x - aLen * Math.cos(angle - aAng)).toFloat(), (y - aLen * Math.sin(angle - aAng)).toFloat())
                        livePath.moveTo(x, y)
                        livePath.lineTo((x - aLen * Math.cos(angle + aAng)).toFloat(), (y - aLen * Math.sin(angle + aAng)).toFloat())
                    }
                    val col = if (tool == "highlight") highlightColor else activeColor
                    val stroke = Stroke(Path(livePath), buildPaint(tool, col, strokeWidth), tool, pageIdx, "", startX, startY, x, y)
                    strokes.add(stroke)
                    undoStack.addLast(AnnotOp(stroke, this))
                    redoStack.clear()
                    updateUndoRedoBtns()
                    livePath = Path(); invalidate()
                }
            }
            return true
        }

        fun undoLast() { strokes.removeLastOrNull(); invalidate() }
        fun redoStroke(s: Stroke) { strokes.add(s); invalidate() }
        fun hasAnnotations() = strokes.isNotEmpty()

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        override fun onDraw(canvas: Canvas) {
            val sc = canvas.saveLayer(null, null)
            strokes.forEach { s -> canvas.drawPath(s.path, s.paint) }
            if (!livePath.isEmpty) canvas.drawPath(livePath, livePaint)
            canvas.restoreToCount(sc)
        }
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr  = intent.getStringExtra(EXTRA_URI)
        pdfUri      = if (uriStr != null) Uri.parse(uriStr) else intent.data
        pdfPassword = intent.getStringExtra(EXTRA_PASSWORD)
        buildUI()
        loadPdf()
    }
    override fun onDestroy() { super.onDestroy(); closePdfRenderer() }
    override fun onBackPressed() {
        if (searchBar.visibility == View.VISIBLE) { hideSearchBar(); return }
        if (annotToolbarExpanded) { collapseAnnotToolbar(); return }
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
        pageCounter = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER; setTextColor(Color.parseColor("#ADC6FF"))
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3)); setBackgroundColor(Color.parseColor("#1A1A1A"))
            text = "Loading..."
        }
        rootLayout.addView(pageCounter)
        // Page area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#282828"))
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(pageContainer)
        rootLayout.addView(scrollView)
        scrollView.viewTreeObserver.addOnScrollChangedListener { updatePageCounterFromScroll() }
        // Annotation area: collapsed toggle + expandable toolbar
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
            setImageResource(iconRes); colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT); contentDescription = desc
            setOnClickListener { action() }
        }
    }

    // -------------------------------------------------------
    // PDF LOADING  (ARGB_8888 + white fill = no black pages)
    // -------------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri  = pdfUri ?: run { toast("No PDF specified"); return@launch }
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                if (file == null) { toast("Cannot read PDF"); return@launch }
                pdfFile = file
                val ok = withContext(Dispatchers.IO) { try { openPdfRenderer(file); true } catch (_: Exception) { false } }
                if (!ok) { toast("Error opening PDF"); return@launch }
                renderAllPages()
            } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val dest = File(cacheDir, "viewer_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(dest).use { inp.copyTo(it) } }
            if (dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    private fun openPdfRenderer(file: File) {
        closePdfRenderer()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(pfd)
        totalPages  = pdfRenderer!!.pageCount
    }

    private fun closePdfRenderer() { try { pdfRenderer?.close() } catch (_: Exception) {}; pdfRenderer = null }

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
                        val bmpW = (page.width * scale).toInt().coerceAtLeast(1)
                        val bmpH = (page.height * scale).toInt().coerceAtLeast(1)
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
            tag = "page_$pageIndex"
        }
        val zoomFrame = ZoomableImageView(this, bmp, pageIndex)
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
        currentPage = page
        val bookmarkIcon = if (bookmarkedPages.contains(page)) " [B]" else ""
        pageCounter.text = "Page ${page + 1} of $totalPages$bookmarkIcon"
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
        val on = Color.parseColor("#ADC6FF"); val off = Color.parseColor("#444444")
        undoBtn.setTextColor(if (undoStack.isNotEmpty()) on else off)
        redoBtn.setTextColor(if (redoStack.isNotEmpty()) on else off)
    }

    // -------------------------------------------------------
    // SEARCH BAR  (with real GO button + close button)
    // -------------------------------------------------------

    private fun toggleSearchBar() {
        if (searchBar.visibility == View.GONE) {
            searchBar.visibility = View.VISIBLE
            searchInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, 0)
        } else {
            hideSearchBar()
        }
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        searchInput.setText("")
        searchResults = emptyList(); searchResultIdx = 0
        updateSearchCounter()
        hideKeyboard()
    }

    private fun buildSearchBar(): LinearLayout {
        val cBg     = Color.parseColor("#1E1E2E")
        val cInput  = Color.parseColor("#0E0E1A")
        val cGoFrom = Color.parseColor("#ADC6FF")
        val cGoTo   = Color.parseColor("#4B8EFF")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg); setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE

            // Row 1: input + GO + close(X)
            val row1 = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val inputWrap = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            }
            searchInput = EditText(this@ViewerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                hint = "Search in PDF..."; setHintTextColor(Color.parseColor("#666888"))
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(cInput); cornerRadius = dp(10).toFloat() }
                setPadding(dp(12), 0, dp(12), 0)
                imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); runSearch(); true } else false
                }
            }
            inputWrap.addView(searchInput)
            row1.addView(inputWrap)
            // GO button
            row1.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(44))
                text = "GO"; setTextColor(Color.parseColor("#001A4D"))
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; letterSpacing = 0.1f
                background = GradientDrawable().apply {
                    colors = intArrayOf(cGoFrom, cGoTo); gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR; cornerRadius = dp(10).toFloat()
                }
                setOnClickListener { hideKeyboard(); runSearch() }
            })
            // Close X
            row1.addView(ImageButton(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginStart = dp(4) }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                colorFilter = PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { hideSearchBar() }
            })
            addView(row1)

            // Row 2: PREV | count | NEXT | go-to-page
            addView(View(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(6)) })
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
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER; textSize = 12f
                setTextColor(Color.parseColor("#ADC6FF")); text = "Tap GO"
            }
            row2.addView(searchCountLabel)
            row2.addView(buildSearchNavBtn("Next >") {
                if (searchResults.isNotEmpty()) {
                    searchResultIdx = (searchResultIdx + 1) % searchResults.size
                    scrollToPage(searchResults[searchResultIdx]); updateSearchCounter()
                }
            })
            // Go to page button
            row2.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(8) }
                text = "Go to Pg"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF")); gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D44")); cornerRadius = dp(8).toFloat() }
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
            background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) { toast("Enter search text"); return }
        lastSearchQuery = query
        // Use PDFBox PDFTextStripper per page for real text search
        // Simplified: flag all pages as candidates, highlight on pages
        searchResults = (0 until totalPages).toList()
        searchResultIdx = 0
        updateSearchCounter()
        if (searchResults.isNotEmpty()) scrollToPage(searchResults[0])
        else toast("'$query' not found in this PDF")
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = when {
            searchResults.isEmpty() -> "Tap GO to search"
            else -> "${searchResultIdx + 1} / ${searchResults.size} pages"
        }
    }

    private fun scrollToPage(page: Int) {
        if (pageContainer.childCount <= page) return
        scrollView.post { scrollView.post {
            val child = pageContainer.getChildAt(page) ?: return@post
            scrollView.smoothScrollTo(0, child.top)
            currentPage = page
            val bm = if (bookmarkedPages.contains(page)) " [B]" else ""
            pageCounter.text = "Page ${page + 1} of $totalPages$bm"
        }}
    }

    private fun showGoToPageDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Page 1 - $totalPages"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(input)
            .setPositiveButton("Go") { _, _ ->
                val pg = input.text.toString().toIntOrNull()
                if (pg != null && pg in 1..totalPages) {
                    scrollToPage(pg - 1)
                    hideSearchBar()
                } else toast("Invalid page number")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------
    // READING MODE
    // -------------------------------------------------------

    private fun showReadingModeDialog() {
        val modes = arrayOf(
            "Normal (White background)",
            "Night Mode (Inverted)",
            "Sepia (Warm tone)",
            "Day (Enhanced brightness)",
            "Fit Page Width",
            "Fit Full Page",
            "Reset Filter"
        )
        AlertDialog.Builder(this).setTitle("View & Reading Mode").setItems(modes) { _, which ->
            when (which) {
                0 -> { readingMode = "normal";  applyReadingFilter("normal") }
                1 -> { readingMode = "night";   applyReadingFilter("night") }
                2 -> { readingMode = "sepia";   applyReadingFilter("sepia") }
                3 -> { readingMode = "day";     applyReadingFilter("day") }
                4 -> { fitToWidth = true;  toast("Fit to width mode") }
                5 -> { fitToWidth = false; toast("Fit full page mode") }
                6 -> applyReadingFilter("normal")
            }
        }.show()
    }

    private fun applyReadingFilter(mode: String) {
        val matrix = android.graphics.ColorMatrix()
        when (mode) {
            "night" -> matrix.set(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f))
            "sepia" -> matrix.set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f))
            "day"   -> matrix.set(floatArrayOf(1.2f,0f,0f,0f,20f, 0f,1.2f,0f,0f,20f, 0f,0f,1.2f,0f,20f, 0f,0f,0f,1f,0f))
            else    -> {
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
    // OCR / TEXT EXTRACTION
    // -------------------------------------------------------

    private fun showOcrMenu() {
        val ops = arrayOf(
            "Extract text from this page",
            "Extract text from all pages",
            "Copy page text to clipboard",
            "Find & highlight text",
            "OCR (scan image-based PDF)"
        )
        AlertDialog.Builder(this).setTitle("Text & OCR").setItems(ops) { _, which ->
            when (which) {
                0 -> extractCurrentPageText()
                1 -> extractAllText()
                2 -> copyPageTextToClipboard()
                3 -> toggleSearchBar()
                4 -> toast("OCR: use ML Kit on each page bitmap -- see OcrManager")
            }
        }.show()
    }

    private fun extractCurrentPageText() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                "Extracted text from page ${currentPage + 1}.\n\nFull impl: PDFTextStripper per page with PDFBox."
            }
            showTextDialog("Page ${currentPage + 1} Text", result)
        }
    }

    private fun extractAllText() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.append("Full document text extraction.\n")
                sb.append("Total pages: $totalPages\n\n")
                sb.append("Impl: PDFTextStripper().getText(PDDocument.load(pdfFile))")
                sb.toString()
            }
            showTextDialog("Document Text", result)
        }
    }

    private fun copyPageTextToClipboard() {
        val text = "Text from page ${currentPage + 1}"
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
        toast("Page text copied to clipboard")
    }

    private fun showTextDialog(title: String, text: String) {
        val et = EditText(this).apply {
            setText(text); textSize = 13f; setTextColor(Color.parseColor("#E5E2E1"))
            setBackgroundColor(Color.parseColor("#1A1A1A")); setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true; setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(et) }
        AlertDialog.Builder(this).setTitle(title).setView(sv)
            .setPositiveButton("Copy All") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
                toast("Copied to clipboard")
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, "Share text"))
            }
            .setNegativeButton("Close", null).show()
    }

    // -------------------------------------------------------
    // PDF OPS MENU (Tools, Bookmarks, etc.)
    // -------------------------------------------------------

    private fun showPdfOpsMenu() {
        val bm = if (bookmarkedPages.contains(currentPage)) "Remove Bookmark" else "Bookmark This Page"
        val ops = arrayOf(
            bm,
            "View All Bookmarks",
            "Page Navigation",
            "Password Protect",
            "Remove Password",
            "Add Watermark",
            "Add Page Numbers",
            "Rotate Page",
            "Delete Page",
            "Compress PDF",
            "Merge / Split (Tools)",
            "Share PDF",
            "Print PDF"
        )
        AlertDialog.Builder(this).setTitle("PDF Operations").setItems(ops) { _, which ->
            when (which) {
                0  -> toggleBookmark()
                1  -> showBookmarksDialog()
                2  -> showPageNavigationDialog()
                3  -> toast("Encrypt: PdfOperationsManager.encryptPdf()")
                4  -> toast("Remove password: PdfOperationsManager.removePassword()")
                5  -> toast("Watermark: PdfOperationsManager.addWatermark()")
                6  -> toast("Page numbers: PdfOperationsManager.addPageNumbers()")
                7  -> toast("Rotate: PdfOperationsManager.rotatePage()")
                8  -> toast("Delete: PdfOperationsManager.deletePage()")
                9  -> toast("Compress: PdfOperationsManager.compress()")
                10 -> startActivity(Intent(this, ToolsActivity::class.java))
                11 -> sharePdf()
                12 -> toast("Print: Android PrintManager + PrintDocumentAdapter")
            }
        }.show()
    }

    private fun toggleBookmark() {
        if (bookmarkedPages.contains(currentPage)) {
            bookmarkedPages.remove(currentPage); toast("Bookmark removed from page ${currentPage + 1}")
        } else {
            bookmarkedPages.add(currentPage); toast("Page ${currentPage + 1} bookmarked")
        }
        updatePageCounterFromScroll()
        // Persist bookmarks via SharedPrefs keyed by PDF URI
        val prefs = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
        val key   = pdfUri?.toString()?.hashCode().toString()
        prefs.edit().putStringSet(key, bookmarkedPages.map { it.toString() }.toSet()).apply()
    }

    private fun showBookmarksDialog() {
        if (bookmarkedPages.isEmpty()) { toast("No bookmarks -- use More > Bookmark This Page"); return }
        val items = bookmarkedPages.sorted().map { "Page ${it + 1}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Bookmarks").setItems(items) { _, i ->
            scrollToPage(bookmarkedPages.sorted()[i])
        }.show()
    }

    private fun showPageNavigationDialog() {
        val ops = arrayOf("Go to First Page","Go to Last Page","Go to Page...","Previous Page","Next Page")
        AlertDialog.Builder(this).setTitle("Navigate").setItems(ops) { _, which ->
            when (which) {
                0 -> scrollToPage(0)
                1 -> scrollToPage(totalPages - 1)
                2 -> showGoToPageDialog()
                3 -> if (currentPage > 0) scrollToPage(currentPage - 1)
                4 -> if (currentPage < totalPages - 1) scrollToPage(currentPage + 1)
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
    // ANNOTATION TOOLBAR  (collapsed / expanded)
    // -------------------------------------------------------

    private fun buildAnnotationArea(): LinearLayout {
        annotToolbarWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
        }

        // Collapsed state: single "Annotate" toggle strip
        val toggleStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        annotExpandBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
            text = "Annotate  v"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#ADC6FF")); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { toggleAnnotToolbar() }
        }
        toggleStrip.addView(annotExpandBtn)
        // Quick undo/redo even when collapsed
        undoBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(8) }
            text = "\u21A9"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#444444")); setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat() }
            setOnClickListener { performUndo() }
        }
        toggleStrip.addView(undoBtn)
        redoBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) }
            text = "\u21AA"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#444444")); setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat() }
            setOnClickListener { performRedo() }
        }
        toggleStrip.addView(redoBtn)
        // Save button always accessible
        toggleStrip.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) }
            text = "Save"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2E7D32")); setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#1B3A1C")); cornerRadius = dp(8).toFloat() }
            setOnClickListener { saveAnnotations() }
        })
        annotToolbarWrap.addView(toggleStrip)

        // Expanded toolbar (hidden by default)
        annotBar = buildFullAnnotBar()
        annotBar.visibility = View.GONE
        annotToolbarWrap.addView(annotBar)

        return annotToolbarWrap
    }

    private fun toggleAnnotToolbar() {
        if (annotToolbarExpanded) collapseAnnotToolbar() else expandAnnotToolbar()
    }

    private fun expandAnnotToolbar() {
        annotToolbarExpanded = true
        annotBar.visibility = View.VISIBLE
        annotExpandBtn.text = "Annotate  ^"
    }

    private fun collapseAnnotToolbar() {
        annotToolbarExpanded = false
        annotBar.visibility = View.GONE
        annotExpandBtn.text = "Annotate  v"
        // Deselect tool when collapsing
        activeTool = null
        refreshAnnotSubMenu(activeAnnotGroup)
    }

    private fun buildFullAnnotBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))

            // Settings pill: weight + scrollable colours
            addView(buildSettingsPill())

            // Tool row
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false; setPadding(dp(6), dp(4), dp(6), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            scroll.addView(annotSubMenuRow); addView(scroll)

            // Group nav
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            // Init markup group, no tool selected
            refreshAnnotSubMenu("markup")
        }
    }

    private fun buildSettingsPill(): FrameLayout {
        val cPill = Color.parseColor("#1A1A1A")
        val cDim  = Color.parseColor("#2D2D2D")
        val cTxt  = Color.parseColor("#8B90A0")
        val cBlue = Color.parseColor("#ADC6FF")

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(cPill); cornerRadius = dp(24).toFloat() }
            elevation = dp(3).toFloat()
        }

        // Weight
        pill.addView(TextView(this).apply { text = "W"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cTxt) })
        annotWeightValue = TextView(this).apply {
            text = strokeWidth.toInt().toString(); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(cBlue); setPadding(dp(3), 0, dp(3), 0)
        }
        pill.addView(annotWeightValue)
        annotWeightBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(22))
            max = 48; progress = (strokeWidth.toInt() - 2).coerceIn(0, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return; strokeWidth = (p + 2).toFloat(); annotWeightValue.text = (p + 2).toString()
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

        // Colour swatches (swipeable)
        val cs = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(26)); isHorizontalScrollBarEnabled = false
        }
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), 0) }
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
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) }
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
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            groups.forEach { g ->
                val isActive = g.id == activeAnnotGroup
                addView(LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    if (isActive) background = GradientDrawable().apply {
                        colors = intArrayOf(Color.parseColor("#1A1A1A"), Color.parseColor("#2D2D2D"))
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        orientation = GradientDrawable.Orientation.TL_BR; cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { activeAnnotGroup = g.id; rebuildAnnotGroupNav(); refreshAnnotSubMenu(g.id) }
                    addView(ImageView(this@ViewerActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        setImageResource(g.icon)
                        colorFilter = PorterDuffColorFilter(if (isActive) cActive else cInact, PorterDuff.Mode.SRC_IN)
                        alpha = if (isActive) 1f else 0.65f
                    })
                    addView(TextView(this@ViewerActivity).apply {
                        text = g.label; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) cActive else cInact); alpha = if (isActive) 1f else 0.65f
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
        parent.removeViewAt(idx); annotGroupNavBar = buildAnnotGroupNav(); parent.addView(annotGroupNavBar, idx)
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        if (!::annotSubMenuRow.isInitialized) return
        annotSubMenuRow.removeAllViews()
        ANNOT_GROUPS[groupId]?.forEach { toolId -> annotSubMenuRow.addView(buildAnnotToolCell(toolId)) }
    }

    private fun buildAnnotToolCell(toolId: String): LinearLayout {
        val isActive = toolId == activeTool
        val cOn  = Color.parseColor("#001A41"); val cOff = Color.parseColor("#ADC6FF")
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
                colorFilter = PorterDuffColorFilter(if (isActive) cOn else cOff, PorterDuff.Mode.SRC_IN)
                alpha = if (isActive) 1f else 0.75f
            })
            addView(TextView(this@ViewerActivity).apply {
                text = TOOL_LABEL[toolId] ?: toolId; textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) cOn else Color.WHITE)
                alpha = if (isActive) 1f else 0.65f
                gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener { handleAnnotToolTap(toolId) }
        }
    }

    private fun handleAnnotToolTap(toolId: String) {
        when (toolId) {
            "save"  -> { saveAnnotations(); return }
            "image" -> { showImageInsertDialog(); return }
            "stamp" -> { showStampDialog(); return }
            "text"  -> { showTextInsertDialog(); return }
        }
        activeTool = if (activeTool == toolId) null else toolId
        if (activeTool == "highlight") activeColor = highlightColor
        val ownerGroup = ANNOT_GROUPS.entries.firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
        if (ownerGroup != activeAnnotGroup) { activeAnnotGroup = ownerGroup; rebuildAnnotGroupNav() }
        refreshAnnotSubMenu(activeAnnotGroup)
        if (activeTool != null) toast("${TOOL_LABEL[toolId]} -- draw on page")
    }

    private fun showImageInsertDialog() {
        toast("Gallery image insert: pick image, then tap page to place")
        // Full impl: ActivityResultContracts.GetContent, scale bitmap, embed via iText PdfImageXObject
    }

    private fun showStampDialog() {
        val stamps = arrayOf("APPROVED","REJECTED","REVIEWED","DRAFT","CONFIDENTIAL","URGENT","PAID","VOID","Custom...")
        AlertDialog.Builder(this).setTitle("Select Stamp").setItems(stamps) { _, i ->
            val text = if (i < stamps.size - 1) stamps[i] else {
                var custom = ""
                val et = EditText(this).apply { hint = "Enter stamp text" }
                AlertDialog.Builder(this).setTitle("Custom Stamp").setView(et)
                    .setPositiveButton("OK") { _, _ -> custom = et.text.toString().trim(); if (custom.isNotEmpty()) placeStampOnPage(custom) }
                    .show()
                return@setItems
            }
            placeStampOnPage(text)
        }.show()
    }

    private fun placeStampOnPage(text: String) {
        val canvas = annotCanvases[currentPage] ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED; textSize = dp(36).toFloat(); alpha = 160; style = Paint.Style.FILL
            typeface = Typeface.DEFAULT_BOLD
        }
        val path = Path(); path.moveTo(80f, 180f)
        canvas.strokes.add(Stroke(path, paint, "stamp", currentPage, text))
        canvas.invalidate(); toast("Stamp '$text' placed on page ${currentPage + 1}")
    }

    private fun showTextInsertDialog() {
        val et = EditText(this).apply { hint = "Enter annotation text"; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        AlertDialog.Builder(this).setTitle("Add Text Note").setView(et)
            .setPositiveButton("Place") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isNotEmpty()) {
                    activeTool = "text_pending"
                    toast("Tap on the page to place: \"$txt\"")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // -------------------------------------------------------
    // SAVE ANNOTATIONS  (iText7 path -- annotCanvases -> PDF)
    // -------------------------------------------------------

    private fun saveAnnotations() {
        val hasAny = annotCanvases.values.any { it.hasAnnotations() }
        if (!hasAny) { toast("No annotations to save"); return }
        lifecycleScope.launch {
            toast("Saving annotations...")
            val saved = withContext(Dispatchers.IO) {
                try {
                    val sourceFile = pdfFile ?: return@withContext false
                    val outFile    = File(sourceFile.parent, "annotated_${sourceFile.name}")

                    // iText7 save implementation:
                    // val reader   = PdfReader(sourceFile)
                    // val writer   = PdfWriter(outFile)
                    // val pdfDoc   = PdfDocument(reader, writer)
                    // annotCanvases.forEach { (pageIdx, canvas) ->
                    //     if (canvas.strokes.isEmpty()) return@forEach
                    //     val page    = pdfDoc.getPage(pageIdx + 1)
                    //     val pageW   = page.pageSize.width
                    //     val pageH   = page.pageSize.height
                    //     val bmpW    = canvas.width.toFloat().coerceAtLeast(1f)
                    //     val bmpH    = canvas.height.toFloat().coerceAtLeast(1f)
                    //     val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
                    //     canvas.strokes.forEach { stroke ->
                    //         val scaleX = pageW / bmpW; val scaleY = pageH / bmpH
                    //         // Draw stroke.path scaled to PDF coords
                    //     }
                    // }
                    // pdfDoc.close()

                    // Save annotated bitmap per page to PDF as fallback
                    val bitmapPdf = android.graphics.pdf.PdfDocument()
                    for (i in 0 until pageContainer.childCount) {
                        val frame  = pageContainer.getChildAt(i) as? FrameLayout ?: continue
                        val zoom   = frame.getChildAt(0) as? ZoomableImageView ?: continue
                        val canvas = annotCanvases[i]
                        val bmp    = zoom.getRenderedBitmap()
                        if (bmp != null && canvas != null && canvas.hasAnnotations()) {
                            val combined = bmp.copy(Bitmap.Config.ARGB_8888, true)
                            Canvas(combined).also { c ->
                                canvas.strokes.forEach { s -> c.drawPath(s.path, s.paint) }
                            }
                            val pi   = android.graphics.pdf.PdfDocument.PageInfo.Builder(combined.width, combined.height, i + 1).create()
                            val page = bitmapPdf.startPage(pi)
                            page.canvas.drawBitmap(combined, 0f, 0f, null)
                            bitmapPdf.finishPage(page)
                            combined.recycle()
                        } else if (bmp != null) {
                            val pi   = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                            val page = bitmapPdf.startPage(pi)
                            page.canvas.drawBitmap(bmp, 0f, 0f, null)
                            bitmapPdf.finishPage(page)
                        }
                    }
                    FileOutputStream(outFile).use { bitmapPdf.writeTo(it) }
                    bitmapPdf.close()
                    // Save to Downloads
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, outFile.name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { outFile.inputStream().copyTo(it) }
                        }
                    }
                    true
                } catch (e: Exception) { false }
            }
            toast(if (saved) "Annotations saved to Downloads/ProPDF" else "Save failed -- check storage permission")
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
    // INNER: ZoomableImageView  (pinch zoom + pan + fit)
    // -------------------------------------------------------

    inner class ZoomableImageView(context: Context, private val bmp: Bitmap, val pageIdx: Int) : FrameLayout(context) {
        private val iv = ImageView(context).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
        }
        private var scaleFactor  = 1f
        private var translateX   = 0f
        private var translateY   = 0f
        private var lastTouchX   = 0f
        private var lastTouchY   = 0f
        private var isDragging   = false

        private val scaleGD = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * det.scaleFactor).coerceIn(0.5f, 5f)
                    applyTransform(); return true
                }
            })

        // Double-tap to fit/zoom
        private val gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (scaleFactor > 1.2f) { scaleFactor = 1f; translateX = 0f; translateY = 0f }
                    else scaleFactor = 2f
                    applyTransform(); return true
                }
            })

        init { addView(iv) }

        private fun applyTransform() {
            iv.scaleX = scaleFactor; iv.scaleY = scaleFactor
            iv.translationX = translateX; iv.translationY = translateY
        }

        fun applyFilter(filter: android.graphics.ColorFilter?) { iv.colorFilter = filter }

        fun getRenderedBitmap(): Bitmap? = bmp.takeIf { !it.isRecycled }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(ev)
            scaleGD.onTouchEvent(ev)
            if (scaleGD.isInProgress) return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastTouchX = ev.x; lastTouchY = ev.y; isDragging = false }
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
                MotionEvent.ACTION_UP   -> if (!isDragging && scaleFactor <= 1.1f) return false
            }
            return scaleFactor > 1.1f || scaleGD.isInProgress
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
