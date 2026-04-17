package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.view.Gravity
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

    // Views wired in buildUI()
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

    // Tool tables
    private val ANNOT_GROUPS = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "undo", "redo", "save")
    )
    private val TOOL_LABEL = mapOf(
        "freehand" to "Pen", "highlight" to "High.", "underline" to "Under.",
        "strikeout" to "Strike", "eraser" to "Eraser", "rect" to "Box",
        "circle" to "Circle", "arrow" to "Arrow", "text" to "Text",
        "stamp" to "Stamp", "image" to "Image", "move_text" to "MoveT",
        "move_shape" to "MoveS", "undo" to "Undo", "redo" to "Redo", "save" to "Save"
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
        "undo"       to android.R.drawable.ic_menu_revert,
        "redo"       to android.R.drawable.ic_media_ff,
        "save"       to android.R.drawable.ic_menu_save
    )

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
    // ROOT UI  --  builds full screen layout
    // -------------------------------------------------------

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        setContentView(rootLayout)

        // 1. Top toolbar
        rootLayout.addView(buildTopBar())

        // 2. Search bar (hidden until search icon tapped)
        searchBar = buildSearchBar()
        rootLayout.addView(searchBar)

        // 3. Page counter strip
        pageCounter = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#ADC6FF"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            text = "Loading..."
        }
        rootLayout.addView(pageCounter)

        // 4. Scrollable page area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#303030"))
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scrollView.addView(pageContainer)
        rootLayout.addView(scrollView)

        // Track scroll -> update page counter
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            updatePageCounterFromScroll()
        }

        // 5. Annotation toolbar at bottom
        annotBar = buildAnnotationToolbar()
        rootLayout.addView(annotBar)
    }

    private fun buildTopBar(): LinearLayout {
        val cBg  = Color.parseColor("#1A1A1A")
        val cTxt = Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Back button
            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back", cTxt) {
                finish()
            })

            // Title
            addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = pdfUri?.lastPathSegment ?: "PDF Viewer"
                setTextColor(cTxt)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            // Search icon
            addView(buildIconBtn(android.R.drawable.ic_menu_search, "Search", cTxt) {
                toggleSearchBar()
            })

            // Tools menu
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
    // -------------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri = pdfUri ?: run {
                    toast("No PDF file specified")
                    return@launch
                }
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                if (file == null) {
                    toast("Cannot read PDF file")
                    return@launch
                }
                pdfFile = file
                withContext(Dispatchers.IO) { openPdfRenderer(file) }
                renderAllPages()
            } catch (e: Exception) {
                toast("Error opening PDF: ${e.message}")
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = "viewer_${System.currentTimeMillis()}.pdf"
            val dest = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out) }
            }
            if (dest.length() > 0) dest else null
        } catch (e: Exception) { null }
    }

    private fun openPdfRenderer(file: File) {
        closePdfRenderer()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(pfd)
        totalPages = pdfRenderer!!.pageCount
    }

    private fun closePdfRenderer() {
        try { pdfRenderer?.close() } catch (_: Exception) {}
        pdfRenderer = null
    }

    private suspend fun renderAllPages() {
        val renderer = pdfRenderer ?: return
        val screenW = resources.displayMetrics.widthPixels - dp(16)
        totalPages = renderer.pageCount

        withContext(Dispatchers.Main) {
            pageContainer.removeAllViews()
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
                        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Pinch-to-zoom wrapper
        val zoomFrame = ZoomableImageView(this, bmp)
        pageFrame.addView(zoomFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        pageContainer.addView(pageFrame)
    }

    private fun updatePageCounterFromScroll() {
        if (totalPages == 0) return
        val scrollY = scrollView.scrollY
        val total = pageContainer.height.coerceAtLeast(1)
        val pct = scrollY.toFloat() / total
        val page = (pct * totalPages).toInt().coerceIn(0, totalPages - 1)
        currentPage = page
        pageCounter.text = "Page ${page + 1} of $totalPages"
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
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(8)
            }
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
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard(); runSearch(); true
                } else false
            }
        }
        inputWrap.addView(searchInput)

        val clearBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(4) }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            colorFilter = PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                searchInput.setText("")
                searchResults = emptyList()
                searchResultIdx = 0
                updateSearchCounter()
            }
        }
        inputWrap.addView(clearBtn)
        inputRow.addView(inputWrap)

        val goBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46))
            text = "GO"
            setTextColor(Color.parseColor("#002E69"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            background = GradientDrawable().apply {
                colors = intArrayOf(cGoFrom, cGoTo)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { hideKeyboard(); runSearch() }
        }
        inputRow.addView(goBtn)
        container.addView(inputRow)

        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(8))
        })

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navRow.addView(buildSearchNavBtn("< PREV") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx = (searchResultIdx - 1 + searchResults.size) % searchResults.size
                scrollToPage(searchResults[searchResultIdx])
                updateSearchCounter()
            }
        })
        searchCountLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.parseColor("#64B5F6"))
            text = ""
        }
        navRow.addView(searchCountLabel)
        navRow.addView(buildSearchNavBtn("NEXT >") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx = (searchResultIdx + 1) % searchResults.size
                scrollToPage(searchResults[searchResultIdx])
                updateSearchCounter()
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
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) { toast("Enter search text"); return }
        // PDFBox text search - simplified: marks all pages as results for now
        // Full implementation uses PDFTextStripper per page
        searchResults = (0 until totalPages).toList()
        searchResultIdx = 0
        if (searchResults.isEmpty()) toast("'$query' not found")
        else scrollToPage(searchResults[0])
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
    // PDF OPERATIONS MENU
    // -------------------------------------------------------

    private fun showPdfOpsMenu() {
        val ops = arrayOf(
            "Password Protect", "Remove Password", "Add Watermark",
            "Add Page Numbers", "Rotate Page", "Delete Page",
            "Compress PDF", "Merge PDFs", "Split PDF",
            "OCR This Page", "OCR Full Document", "Extract Text",
            "Night Mode", "Sepia Mode", "Print", "Share PDF"
        )
        AlertDialog.Builder(this)
            .setTitle("PDF Operations")
            .setItems(ops) { _, which ->
                when (which) {
                    0  -> toast("Password protect: use PdfOperationsManager.encryptPdf()")
                    1  -> toast("Remove password: use PdfOperationsManager.removePassword()")
                    2  -> toast("Watermark: use PdfOperationsManager.addWatermark()")
                    3  -> toast("Page numbers: use PdfOperationsManager.addPageNumbers()")
                    4  -> toast("Rotate: use PdfOperationsManager.rotatePage()")
                    5  -> toast("Delete: use PdfOperationsManager.deletePage()")
                    6  -> toast("Compress: use PdfOperationsManager.compress()")
                    7  -> startActivity(Intent(this, ToolsActivity::class.java))
                    8  -> startActivity(Intent(this, ToolsActivity::class.java))
                    9  -> toast("OCR page: use OcrManager.ocrPage()")
                    10 -> toast("OCR doc: use OcrManager.ocrFullDocument()")
                    11 -> toast("Extract: use OcrManager.extractText()")
                    12 -> applyReadingFilter("night")
                    13 -> applyReadingFilter("sepia")
                    14 -> toast("Print: use Android PrintManager")
                    15 -> sharePdf()
                }
            }
            .show()
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
            val iv = frame.getChildAt(0) as? ImageView ?: continue
            iv.colorFilter = filter
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.provider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    // -------------------------------------------------------
    // ANNOTATION TOOLBAR - 3 layers
    // -------------------------------------------------------

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            // Layer 1: settings pill centred above tool row
            val pillWrapper = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(6)
                }
            }
            annotSettingsPill = buildAnnotSettingsPill()
            pillWrapper.addView(annotSettingsPill,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            addView(pillWrapper)

            // Layer 2: horizontally scrollable tool cards
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            // Layer 3: group nav (MARKUP / SHAPES / INSERTS / MANAGE)
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            refreshAnnotSubMenu("markup")
        }
    }

    private fun buildAnnotSettingsPill(): LinearLayout {
        val cPill = Color.parseColor("#1A1A1A")
        val cDim  = Color.parseColor("#2D2D2D")
        val cTxt  = Color.parseColor("#8B90A0")
        val cBlue = Color.parseColor("#ADC6FF")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(cPill); cornerRadius = dp(24).toFloat()
            }
            elevation = dp(4).toFloat()

            addView(TextView(this@ViewerActivity).apply {
                text = "WEIGHT"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(cTxt); letterSpacing = 0.1f
            })

            annotWeightValue = TextView(this@ViewerActivity).apply {
                text = strokeWidth.toInt().toString(); textSize = 10f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(cBlue)
                setPadding(dp(5), 0, dp(5), 0)
            }
            addView(annotWeightValue)

            annotWeightBar = SeekBar(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(86), dp(22))
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
            addView(annotWeightBar)

            addView(View(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    marginStart = dp(8); marginEnd = dp(8)
                }
                setBackgroundColor(cDim)
            })

            addView(TextView(this@ViewerActivity).apply {
                text = "COLOR"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(cTxt); letterSpacing = 0.1f; setPadding(0, 0, dp(8), 0)
            })

            annotSwatchViews.clear()
            listOf("#007AFF", "#EF6719", "#FFFFFF", "#000000").forEach { hex ->
                val col = Color.parseColor(hex)
                val sw = View(this@ViewerActivity).apply {
                    val sz = dp(17)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(7) }
                    tag = col
                    applySwatchStyle(this, col, col == activeColor)
                    setOnClickListener {
                        activeColor = col
                        annotSwatchViews.forEach { sv ->
                            applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor)
                        }
                    }
                }
                annotSwatchViews.add(sw); addView(sw)
            }
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
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
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
        activeTool = toolId
        val ownerGroup = ANNOT_GROUPS.entries
            .firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
        if (ownerGroup != activeAnnotGroup) {
            activeAnnotGroup = ownerGroup
            rebuildAnnotGroupNav()
        }
        refreshAnnotSubMenu(activeAnnotGroup)
        toast("Tool: ${TOOL_LABEL[toolId] ?: toolId}")
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: simple pinch-to-zoom ImageView wrapper
    // -------------------------------------------------------

    inner class ZoomableImageView(
        context: Context, bmp: Bitmap
    ) : FrameLayout(context) {

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
                    iv.scaleX = scaleFactor
                    iv.scaleY = scaleFactor
                    return true
                }
            })

        init { addView(iv) }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
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
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                if (password != null) putExtra(EXTRA_PASSWORD, password)
            }
            context.startActivity(intent)
        }
    }
}
