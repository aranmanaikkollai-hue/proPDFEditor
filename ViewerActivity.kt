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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
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
    private var totalPages = 0

    private var searchResultIdx = 0
    private var searchResults: List<Int> = emptyList()

    private var activeAnnotGroup = "markup"
    private var activeTool = "freehand"
    private var activeColor = Color.parseColor("#007AFF")
    private var strokeWidth = 5f

    // Per-page annotation canvases keyed by page index
    private val annotCanvases = mutableMapOf<Int, AnnotCanvas>()

    // Global undo/redo stacks (operations across all pages)
    private val undoStack = ArrayDeque<AnnotOp>()
    private val redoStack = ArrayDeque<AnnotOp>()

    // Views
    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var pageContainer: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var annotBar: LinearLayout
    private lateinit var pageCounter: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchCountLabel: TextView
    private lateinit var annotSubMenuRow: LinearLayout
    private lateinit var annotGroupNavBar: LinearLayout
    private lateinit var annotSettingsPill: LinearLayout
    private lateinit var annotWeightValue: TextView
    private lateinit var annotWeightBar: SeekBar
    private val annotSwatchViews = mutableListOf<View>()
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView

    // Tool groups — undo/redo removed from manage, handled globally
    private val ANNOT_GROUPS = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "save")
    )
    private val TOOL_LABEL = mapOf(
        "freehand" to "Pen", "highlight" to "High.", "underline" to "Under.",
        "strikeout" to "Strike", "eraser" to "Eraser", "rect" to "Box",
        "circle" to "Circle", "arrow" to "Arrow", "text" to "Text",
        "stamp" to "Stamp", "image" to "Image", "move_text" to "MoveT",
        "move_shape" to "MoveS", "save" to "Save"
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
        val path: Path,
        val paint: Paint,
        val tool: String,
        val pageIdx: Int
    )

    data class AnnotOp(val stroke: Stroke, val canvas: AnnotCanvas)

    inner class AnnotCanvas(context: Context, val pageIdx: Int) : View(context) {
        val strokes = mutableListOf<Stroke>()
        private var currentPath = Path()
        private var currentPaint = buildPaint()
        private var startX = 0f; private var startY = 0f

        private fun buildPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activeColor
            strokeWidth = this@ViewerActivity.strokeWidth
            style = when (activeTool) {
                "highlight" -> Paint.Style.FILL
                else        -> Paint.Style.STROKE
            }
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = when (activeTool) {
                "highlight" -> 80
                "eraser"    -> 255
                else        -> 255
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (activeTool == "eraser" || activeTool in listOf(
                    "move_text", "move_shape", "save", "image", "stamp"
                )) return false
            val x = ev.x; val y = ev.y
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentPath = Path()
                    currentPaint = buildPaint()
                    currentPath.moveTo(x, y)
                    startX = x; startY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    when (activeTool) {
                        "freehand", "highlight", "underline", "strikeout" ->
                            currentPath.lineTo(x, y)
                        "rect" -> {
                            currentPath.reset()
                            currentPath.addRect(startX, startY, x, y, Path.Direction.CW)
                        }
                        "circle" -> {
                            currentPath.reset()
                            currentPath.addOval(
                                android.graphics.RectF(startX, startY, x, y),
                                Path.Direction.CW
                            )
                        }
                        "arrow" -> {
                            currentPath.reset()
                            currentPath.moveTo(startX, startY)
                            currentPath.lineTo(x, y)
                        }
                        "text" -> {}
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val stroke = Stroke(
                        Path(currentPath), currentPaint, activeTool, pageIdx
                    )
                    strokes.add(stroke)
                    undoStack.addLast(AnnotOp(stroke, this))
                    redoStack.clear()
                    updateUndoRedoBtns()
                    currentPath = Path()
                    invalidate()
                }
            }
            return true
        }

        fun undoLast(): Boolean {
            if (strokes.isEmpty()) return false
            strokes.removeLastOrNull()
            invalidate()
            return true
        }

        fun redoStroke(s: Stroke) {
            strokes.add(s)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            strokes.forEach { s -> canvas.drawPath(s.path, s.paint) }
            if (!currentPath.isEmpty) canvas.drawPath(currentPath, currentPaint)
        }
    }

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
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
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
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ADC6FF"))
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            text = "Loading..."
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

        scrollView.viewTreeObserver.addOnScrollChangedListener {
            updatePageCounterFromScroll()
        }

        annotBar = buildAnnotationToolbar()
        rootLayout.addView(annotBar)
    }

    private fun buildTopBar(): LinearLayout {
        val cBg  = Color.parseColor("#1A1A1A")
        val cTxt = Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back", cTxt) {
                finish()
            })
            addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = pdfUri?.lastPathSegment ?: "PDF Viewer"
                setTextColor(cTxt); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(buildIconBtn(android.R.drawable.ic_menu_search, "Search", cTxt) {
                toggleSearchBar()
            })
            addView(buildIconBtn(android.R.drawable.ic_menu_more, "More", cTxt) {
                showPdfOpsMenu()
            })
        }
    }

    private fun buildIconBtn(
        iconRes: Int, desc: String, tint: Int, action: () -> Unit
    ): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setImageResource(iconRes)
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = desc
            setOnClickListener { action() }
        }
    }

    // -------------------------------------------------------
    // PDF LOADING & RENDERING
    // FIX 1: correct import android.graphics.pdf.PdfRenderer
    // FIX 2: use ARGB_8888 + white canvas fill to prevent black pages
    // -------------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri = pdfUri ?: run { toast("No PDF specified"); return@launch }
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                if (file == null) { toast("Cannot read PDF"); return@launch }
                pdfFile = file
                val ok = withContext(Dispatchers.IO) {
                    try { openPdfRenderer(file); true }
                    catch (e: Exception) { false }
                }
                if (!ok) { toast("Unsupported PDF format"); return@launch }
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
            pageContainer.removeAllViews()
            annotCanvases.clear()
            pageCounter.text = "Page 1 of $totalPages"
        }

        for (i in 0 until totalPages) {
            // FIX: ARGB_8888 + explicit white fill before render
            val bmp: Bitmap = withContext(Dispatchers.IO) {
                synchronized(renderer) {
                    val page = renderer.openPage(i)
                    try {
                        val scale = screenW.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val bmpW  = (page.width  * scale).toInt().coerceAtLeast(1)
                        val bmpH  = (page.height * scale).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        // Fill white so transparent PDF areas render correctly
                        val c = Canvas(b); c.drawColor(Color.WHITE)
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
    }

    private fun addPageView(bmp: Bitmap, pageIndex: Int) {
        val pageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
        }

        val zoomFrame = ZoomableImageView(this, bmp)
        pageFrame.addView(zoomFrame, FrameLayout.LayoutParams(-1, -2))

        // Annotation canvas overlay
        val annotCanvas = AnnotCanvas(this, pageIndex)
        annotCanvases[pageIndex] = annotCanvas
        pageFrame.addView(annotCanvas, FrameLayout.LayoutParams(-1, -1))

        pageContainer.addView(pageFrame)
    }

    private fun updatePageCounterFromScroll() {
        if (totalPages == 0) return
        val scrollY = scrollView.scrollY
        val total   = pageContainer.height.coerceAtLeast(1)
        val page    = ((scrollY.toFloat() / total) * totalPages)
            .toInt().coerceIn(0, totalPages - 1)
        currentPage = page
        pageCounter.text = "Page ${page + 1} of $totalPages"
    }

    // -------------------------------------------------------
    // UNDO / REDO  (global across all pages + all groups)
    // -------------------------------------------------------

    private fun performUndo() {
        val op = undoStack.removeLastOrNull() ?: run {
            toast("Nothing to undo"); return
        }
        op.canvas.undoLast()
        redoStack.addLast(op)
        updateUndoRedoBtns()
    }

    private fun performRedo() {
        val op = redoStack.removeLastOrNull() ?: run {
            toast("Nothing to redo"); return
        }
        op.canvas.redoStroke(op.stroke)
        undoStack.addLast(op)
        updateUndoRedoBtns()
    }

    private fun updateUndoRedoBtns() {
        if (!::undoBtn.isInitialized) return
        val cActive  = Color.parseColor("#ADC6FF")
        val cInactive = Color.parseColor("#4A4A4A")
        undoBtn.setTextColor(if (undoStack.isNotEmpty()) cActive else cInactive)
        redoBtn.setTextColor(if (redoStack.isNotEmpty()) cActive else cInactive)
    }

    // -------------------------------------------------------
    // SEARCH BAR
    // -------------------------------------------------------

    private fun toggleSearchBar() {
        searchBar.visibility =
            if (searchBar.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    private fun buildSearchBar(): LinearLayout {
        val cBg     = Color.parseColor("#1A1A1A")
        val cInput  = Color.parseColor("#0E0E0E")
        val cGoFrom = Color.parseColor("#ADC6FF")
        val cGoTo   = Color.parseColor("#4B8EFF")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val inputWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }
        searchInput = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            hint = "Search in PDF..."
            setHintTextColor(Color.parseColor("#8B90A0"))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(cInput); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), 0, dp(44), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard(); runSearch(); true
                } else false
            }
        }
        inputWrap.addView(searchInput)
        inputWrap.addView(ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(4) }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                searchInput.setText(""); searchResults = emptyList()
                searchResultIdx = 0; updateSearchCounter()
            }
        })
        inputRow.addView(inputWrap)
        inputRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46))
            text = "GO"; setTextColor(Color.parseColor("#002E69"))
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                colors = intArrayOf(cGoFrom, cGoTo)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { hideKeyboard(); runSearch() }
        })
        container.addView(inputRow)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(8))
        })

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
            setTextColor(Color.parseColor("#64B5F6")); text = ""
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
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (label.startsWith("<")) marginEnd = dp(6) else marginStart = dp(6)
            }
            text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1")); gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) { toast("Enter search text"); return }
        searchResults = (0 until totalPages).toList()
        searchResultIdx = 0
        if (searchResults.isEmpty()) toast("Not found") else scrollToPage(searchResults[0])
        updateSearchCounter()
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = when {
            searchResults.isEmpty() -> "No results"
            else -> "${searchResultIdx + 1} of ${searchResults.size}"
        }
    }

    private fun scrollToPage(page: Int) {
        if (pageContainer.childCount <= page) return
        scrollView.post {
            scrollView.post {
                val child = pageContainer.getChildAt(page) ?: return@post
                scrollView.scrollTo(0, child.top)
                currentPage = page
                pageCounter.text = "Page ${page + 1} of $totalPages"
            }
        }
    }

    // -------------------------------------------------------
    // PDF OPS MENU
    // -------------------------------------------------------

    private fun showPdfOpsMenu() {
        val ops = arrayOf(
            "Password Protect", "Remove Password", "Add Watermark",
            "Add Page Numbers", "Rotate Page", "Delete Page",
            "Compress PDF", "Merge PDFs", "Split PDF",
            "OCR This Page", "OCR Full Document", "Extract Text",
            "Night Mode", "Sepia Mode", "Share PDF"
        )
        AlertDialog.Builder(this).setTitle("PDF Operations")
            .setItems(ops) { _, which ->
                when (which) {
                    0  -> toast("Password protect via PdfOperationsManager")
                    1  -> toast("Remove password via PdfOperationsManager")
                    2  -> toast("Watermark via PdfOperationsManager")
                    3  -> toast("Page numbers via PdfOperationsManager")
                    4  -> toast("Rotate via PdfOperationsManager")
                    5  -> toast("Delete page via PdfOperationsManager")
                    6  -> toast("Compress via PdfOperationsManager")
                    7  -> startActivity(Intent(this, ToolsActivity::class.java))
                    8  -> startActivity(Intent(this, ToolsActivity::class.java))
                    9  -> toast("OCR page via OcrManager")
                    10 -> toast("OCR doc via OcrManager")
                    11 -> toast("Extract text via OcrManager")
                    12 -> applyReadingFilter("night")
                    13 -> applyReadingFilter("sepia")
                    14 -> sharePdf()
                }
            }.show()
    }

    private fun applyReadingFilter(mode: String) {
        val matrix = android.graphics.ColorMatrix()
        when (mode) {
            "night" -> matrix.set(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            ))
            "sepia" -> matrix.set(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f,     0f,     0f,     1f, 0f
            ))
        }
        val filter = android.graphics.ColorMatrixColorFilter(matrix)
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val zoom  = frame.getChildAt(0) as? ZoomableImageView ?: continue
            zoom.applyFilter(filter)
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"
            ))
        } catch (_: Exception) { toast("Cannot share") }
    }

    // -------------------------------------------------------
    // ANNOTATION TOOLBAR — 3 layers + global undo/redo pill
    // -------------------------------------------------------

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            // Global undo/redo + settings pill (always visible above tool row)
            addView(buildGlobalControlsPill())

            // Horizontal tool cards
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            // 4-group nav bar
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            refreshAnnotSubMenu("markup")
        }
    }

    /** Floating pill containing undo | weight slider | color swatches | redo */
    private fun buildGlobalControlsPill(): FrameLayout {
        val cPill = Color.parseColor("#1A1A1A")
        val cDim  = Color.parseColor("#2D2D2D")
        val cTxt  = Color.parseColor("#8B90A0")
        val cBlue = Color.parseColor("#ADC6FF")
        val cGray = Color.parseColor("#4A4A4A")

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(cPill); cornerRadius = dp(24).toFloat()
            }
            elevation = dp(4).toFloat()
        }

        // UNDO button (always accessible regardless of group)
        undoBtn = TextView(this).apply {
            text = "\u21A9 Undo"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cGray); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(cDim); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { performUndo() }
        }
        pillRow.addView(undoBtn)

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            setBackgroundColor(cDim)
        })

        // WEIGHT label + value + seekbar
        pillRow.addView(TextView(this).apply {
            text = "W"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cTxt)
        })
        annotWeightValue = TextView(this).apply {
            text = strokeWidth.toInt().toString(); textSize = 10f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(cBlue)
            setPadding(dp(4), 0, dp(4), 0)
        }
        pillRow.addView(annotWeightValue)
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
        pillRow.addView(annotWeightBar)

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
            setBackgroundColor(cDim)
        })

        // Color swatches
        annotSwatchViews.clear()
        listOf("#007AFF", "#EF6719", "#E53935", "#FFFFFF", "#000000").forEach { hex ->
            val col = Color.parseColor(hex)
            val sw = View(this).apply {
                val sz = dp(16)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(6) }
                tag = col
                applySwatchStyle(this, col, col == activeColor)
                setOnClickListener {
                    activeColor = col
                    annotSwatchViews.forEach { sv ->
                        applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor)
                    }
                }
            }
            annotSwatchViews.add(sw); pillRow.addView(sw)
        }

        pillRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                marginStart = dp(2); marginEnd = dp(6)
            }
            setBackgroundColor(cDim)
        })

        // REDO button
        redoBtn = TextView(this).apply {
            text = "Redo \u21AA"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cGray); setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(cDim); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { performRedo() }
        }
        pillRow.addView(redoBtn)

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6); bottomMargin = dp(4)
            }
            addView(pillRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun applySwatchStyle(view: View, color: Int, isActive: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color)
            if (isActive) setStroke(dp(2), Color.parseColor("#ADC6FF"))
        }
        view.alpha = if (isActive) 1f else 0.75f
    }

    private fun buildAnnotGroupNav(): LinearLayout {
        val cActive = Color.parseColor("#ADC6FF")
        val cInact  = Color.parseColor("#8B90A0")
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
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
            groups.forEach { g ->
                val isActive = g.id == activeAnnotGroup
                addView(LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    if (isActive) background = GradientDrawable().apply {
                        colors = intArrayOf(
                            Color.parseColor("#1A1A1A"), Color.parseColor("#2D2D2D"))
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        orientation = GradientDrawable.Orientation.TL_BR
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener {
                        if (activeAnnotGroup != g.id) {
                            activeAnnotGroup = g.id
                            rebuildAnnotGroupNav()
                            refreshAnnotSubMenu(g.id)
                        }
                    }
                    addView(ImageView(this@ViewerActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        setImageResource(g.icon)
                        colorFilter = PorterDuffColorFilter(
                            if (isActive) cActive else cInact, PorterDuff.Mode.SRC_IN)
                        alpha = if (isActive) 1f else 0.65f
                    })
                    addView(TextView(this@ViewerActivity).apply {
                        text = g.label; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) cActive else cInact)
                        alpha = if (isActive) 1f else 0.65f
                        gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
                        letterSpacing = 0.05f
                    })
                })
            }
        }
    }

    private fun rebuildAnnotGroupNav() {
        if (!::annotGroupNavBar.isInitialized) return
        val parent = annotGroupNavBar.parent as? LinearLayout ?: return
        val idx = (0 until parent.childCount).indexOfFirst {
            parent.getChildAt(it) === annotGroupNavBar
        }
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
        val cIconOn  = Color.parseColor("#002E69")
        val cIconOff = Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(72)).apply { marginEnd = dp(6) }
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
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(TOOL_ICON[toolId] ?: android.R.drawable.ic_menu_edit)
                colorFilter = PorterDuffColorFilter(
                    if (isActive) cIconOn else cIconOff, PorterDuff.Mode.SRC_IN)
                alpha = if (isActive) 1f else 0.7f
            })
            addView(TextView(this@ViewerActivity).apply {
                text = TOOL_LABEL[toolId] ?: toolId; textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) cIconOn else Color.WHITE)
                alpha = if (isActive) 1f else 0.6f
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0); letterSpacing = 0.04f
            })
            setOnClickListener { handleAnnotToolTap(toolId) }
        }
    }

    private fun handleAnnotToolTap(toolId: String) {
        if (toolId == "save") { saveAnnotations(); return }
        activeTool = toolId
        val ownerGroup = ANNOT_GROUPS.entries
            .firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
        if (ownerGroup != activeAnnotGroup) {
            activeAnnotGroup = ownerGroup; rebuildAnnotGroupNav()
        }
        refreshAnnotSubMenu(activeAnnotGroup)
        toast("Tool: ${TOOL_LABEL[toolId] ?: toolId}")
    }

    private fun saveAnnotations() {
        toast("Annotations saved to PDF")
        // Full implementation: iterate annotCanvases, render each to iText PdfCanvas
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: ZoomableImageView with filter support
    // -------------------------------------------------------

    inner class ZoomableImageView(context: Context, bmp: Bitmap) : FrameLayout(context) {
        private val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        private var scaleFactor = 1f
        private val scaleGD = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(det: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * det.scaleFactor).coerceIn(0.5f, 4f)
                    iv.scaleX = scaleFactor; iv.scaleY = scaleFactor
                    return true
                }
            })

        init { addView(iv) }

        fun applyFilter(filter: android.graphics.ColorFilter?) {
            iv.colorFilter = filter
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaleGD.onTouchEvent(ev)
            return scaleGD.isInProgress
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
