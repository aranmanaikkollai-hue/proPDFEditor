package com.propdf.editor.ui.viewer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.utils.FileHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer as PdfBoxRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    private val imageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { insertImageOnPage(it) }
    }

    companion object {
        const val EXTRA_PDF_URI  = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        fun start(ctx: Context, uri: Uri) = ctx.startActivity(
            Intent(ctx, ViewerActivity::class.java)
                .putExtra(EXTRA_PDF_URI, uri.toString())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    private var pdfFile     : File? = null
    private var pdfPassword : String? = null
    private var totalPages  = 0
    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    private var activeColor = AnnotationCanvasView.HIGHLIGHT_DEFAULT_COLOR
    private var strokeWidth = 14f
    private var textSizePx  = 44f
    private var isDark      = false
    private var isSepia     = false
    private var currentPage = 0

    // Search state
    private var searchQuery    = ""
    private var searchResults  = listOf<Int>()  // page indices with match
    private var searchResultIdx = 0

    private val pageScales  = mutableMapOf<Int, Float>()
    private val canvases    = mutableMapOf<Int, AnnotationCanvasView>()

    private val colorHex = listOf(
        "#FFFF00","#FF9800","#F44336","#E91E63",
        "#9C27B0","#3F51B5","#1A73E8","#009688",
        "#4CAF50","#CDDC39","#795548","#000000",
        "#FFFFFF","#607D8B","#FF5722","#00BCD4"
    )
    private val colorNames = listOf(
        "Yellow","Orange","Red","Pink",
        "Purple","Indigo","Blue","Teal",
        "Green","Lime","Brown","Black",
        "White","Steel","Dp Orange","Cyan"
    )

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvPageCounter : TextView
    private lateinit var bottomNavBar  : LinearLayout
    private lateinit var rootLayout    : LinearLayout
    private lateinit var pageSeekBar   : SeekBar   // page navigation seekbar
    private lateinit var searchBar     : LinearLayout // inline search bar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#303030"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(dp(2), 0, dp(2), 0); gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(makeImgBtn(android.R.drawable.ic_menu_close_clear_cancel) { finish() })
        tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.WHITE); textSize = 12f; text = "Loading..."
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        tvPageCounter = TextView(this).apply {
            text = "0/0"; textSize = 10f; setTextColor(Color.parseColor("#BBDDFF"))
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(tvInfo); topBar.addView(tvPageCounter)
        topBar.addView(makeTextBtn("Find") { showFindBar() })
        topBar.addView(makeTextBtn("Mode") { showReadingModeMenu() })
        topBar.addView(makeImgBtn(android.R.drawable.ic_menu_share) { sharePdf() })

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A73E8"))
        }

        // Inline search bar (hidden by default)
        searchBar = buildSearchBar()

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            viewTreeObserver.addOnScrollChangedListener { updatePageCounter(this.scrollY) }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        // Page seek bar (merges Top/Prev/Next into one slider)
        pageSeekBar = SeekBar(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(32))
            setPadding(dp(12), 0, dp(12), 0); max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A73E8"))
        }
        pageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && totalPages > 0) {
                    val target = (progress.toFloat() / 100f * (totalPages - 1)).toInt()
                    goToPage(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(36))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(4), 0, dp(4), 0); gravity = Gravity.CENTER_VERTICAL
        }
        seekRow.addView(makeTextBtn("<") { goToPage(currentPage - 1) })
        seekRow.addView(pageSeekBar.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        seekRow.addView(makeTextBtn(">") { goToPage(currentPage + 1) })
        seekRow.addView(makeTextBtn("Go") { showGotoDialog() })

        // Size slider
        sizeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6)); gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val tvSzLabel = TextView(this).apply { text = "14"; textSize = 11f; setTextColor(Color.WHITE); minWidth = dp(28) }
        seekStroke = SeekBar(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); max = 100; progress = 14 }
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val w = (v + 4).toFloat()
                strokeWidth = w; textSizePx = w * 3f; tvSzLabel.text = "$v"
                canvases.values.forEach { it.setStrokeWidth(w); it.setTextSize(w * 3f) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sizeBar.addView(tvSzLabel); sizeBar.addView(seekStroke)

        bottomNavBar = buildBottomNavBar()

        rootLayout.addView(topBar); rootLayout.addView(progressBar)
        rootLayout.addView(searchBar)
        rootLayout.addView(scrollView); rootLayout.addView(seekRow)
        rootLayout.addView(sizeBar); rootLayout.addView(bottomNavBar)
        setContentView(rootLayout)
    }

    private fun buildSearchBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            visibility = View.GONE
        }

        // Row 1: Input + Search button + Close
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val et = EditText(this).apply {
            hint = "Search in PDF..."
            textSize = 15f
            tag = "search_et"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#2A2A3E"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextIsSelectable(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    runSearch((this@apply).text.toString().trim())
                }; true
            }
        }
        val btnGo = Button(this).apply {
            text = "GO"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(38)).apply { setMargins(dp(6), 0, dp(4), 0) }
            setOnClickListener {
                val q = searchBar.findViewWithTag<EditText>("search_et")?.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) runSearch(q)
            }
        }
        val btnClose = Button(this).apply {
            text = "X"
            textSize = 13f
            setTextColor(Color.parseColor("#FF6B6B"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { hideSearchBar() }
        }
        inputRow.addView(et); inputRow.addView(btnGo); inputRow.addView(btnClose)

        // Row 2: Result count + Prev/Next navigation
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val tvCount = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#64B5F6"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            tag = "search_count"
        }
        val btnPrev = Button(this).apply {
            text = "< Prev"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333355"))
            layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { navigateSearch(-1) }
        }
        val btnNext = Button(this).apply {
            text = "Next >"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333355"))
            layoutParams = LinearLayout.LayoutParams(-2, dp(32))
            setOnClickListener { navigateSearch(1) }
        }
        navRow.addView(tvCount); navRow.addView(btnPrev); navRow.addView(btnNext)

        bar.addView(inputRow); bar.addView(navRow)
        return bar
    }

    private fun showFindBar() {
        searchBar.visibility = View.VISIBLE
        searchBar.findViewWithTag<EditText>("search_et")?.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(searchBar.findViewWithTag<EditText>("search_et"), 0)
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        searchQuery = ""; searchResults = emptyList()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        searchQuery = query
        val file = pdfFile ?: run { toast("No PDF open"); return }
        // Hide keyboard immediately when search starts
        val imm2 = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm2.hideSoftInputFromWindow(it.windowToken, 0) }
        progressBar.visibility = View.VISIBLE
        val tvCount = searchBar.findViewWithTag<TextView>("search_count")
        tvCount?.text = "Searching..."

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                var doc: PDDocument? = null
                try {
                    doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword)
                          else PDDocument.load(file)
                    val total = doc.numberOfPages
                    val found = mutableListOf<Int>()

                    // Extract ALL text at once (faster than per-page for most PDFs)
                    val fullStripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    fullStripper.sortByPosition = true
                    val fullText = try { fullStripper.getText(doc) } catch (_: Exception) { "" }

                    if (fullText.isBlank()) {
                        // PDF has no extractable text (likely image/scanned)
                        return@withContext emptyList<Int>()
                    }

                    // Search page by page for accurate page numbers
                    for (i in 1..total) {
                        val s = com.tom_roush.pdfbox.text.PDFTextStripper()
                        s.sortByPosition = true
                        s.startPage = i
                        s.endPage = i
                        val pageText = try { s.getText(doc) } catch (_: Exception) { "" }
                        if (pageText.contains(query, ignoreCase = true)) found.add(i - 1)
                    }
                    found
                } catch (e: Exception) {
                    android.util.Log.e("ProPDF", "Search error: ${e.message}")
                    emptyList()
                } finally {
                    try { doc?.close() } catch (_: Exception) {}
                }
            }

            progressBar.visibility = View.GONE
            searchResults = results
            searchResultIdx = 0

            if (results.isEmpty()) {
                tvCount?.text = "Not found"
                toast("Not found: '$query' in this document")
            } else {
                tvCount?.text = "1 of ${results.size}"
                goToPage(results[0])
                toast("Found on ${results.size} page(s): ${results.take(5).map { it + 1 }.joinToString(", ")}")
            }
        }
    }

    private fun navigateSearch(dir: Int) {
        if (searchResults.isEmpty()) return
        searchResultIdx = (searchResultIdx + dir + searchResults.size) % searchResults.size
        searchBar.findViewWithTag<TextView>("search_count")?.text = "${searchResultIdx + 1} of ${searchResults.size}"
        goToPage(searchResults[searchResultIdx])
    }

    private fun buildAnnotToolbarGrid(): AlertDialog {
        data class T(val id: String, val lbl: String, val bg: String, val fg: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,   "Pen",       "#E3F2FD", "#1A73E8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT,  "Highlight", "#FFFDE7", "#F9A825"),
            T(AnnotationCanvasView.TOOL_UNDERLINE,  "Underline", "#E0F7FA", "#00838F"),
            T(AnnotationCanvasView.TOOL_STRIKEOUT,  "Strike",    "#FFEBEE", "#C62828"),
            T(AnnotationCanvasView.TOOL_TEXT,       "Text",      "#E8F5E9", "#2E7D32"),
            T(AnnotationCanvasView.TOOL_RECT,       "Box",       "#F3E5F5", "#7B1FA2"),
            T(AnnotationCanvasView.TOOL_CIRCLE,     "Circle",    "#E8EAF6", "#3949AB"),
            T(AnnotationCanvasView.TOOL_ARROW,      "Arrow",     "#FFF3E0", "#E65100"),
            T(AnnotationCanvasView.TOOL_STAMP,      "Stamp",     "#FFEBEE", "#B71C1C"),
            T(AnnotationCanvasView.TOOL_ERASER,     "Eraser",    "#ECEFF1", "#546E7A"),
            T(AnnotationCanvasView.TOOL_MOVE_TEXT,  "Move Text", "#E0F2F1", "#00695C"),
            T(AnnotationCanvasView.TOOL_MOVE_SHAPE, "Move Shape","#E8F5E9", "#1B5E20"),
            T("image",                              "Image",     "#E8EAF6", "#3F51B5"),
            T("color",                              "Color",     "#FCE4EC", "#AD1457"),
            T("undo",                               "Undo",      "#F5F5F5", "#616161"),
            T("redo",                               "Redo",      "#F5F5F5", "#616161"),
            T("save",                               "Save PDF",  "#E8F5E9", "#2E7D32"),
            T("extract",                            "Copy Text", "#E3F2FD", "#1565C0"),
        )

        // Use a custom layout: full-width dialog with HorizontalScrollView rows
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Title bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#1A73E8"))
        }
        titleBar.addView(TextView(this).apply {
            text = "Annotation Tools"
            textSize = 16f
            setTextColor(Color.WHITE)
            android.graphics.Typeface.DEFAULT_BOLD.let { typeface = it }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        // ScrollView with 2-row grid for all tools
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        var dlg: AlertDialog? = null
        var activeCellBg = Color.WHITE
        var activeCell: LinearLayout? = null

        // Build rows of 4 tools each
        tools.chunked(4).forEach { rowTools ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            }
            rowTools.forEach { t ->
                val bgColor = Color.parseColor(t.bg)
                val fgColor = Color.parseColor(t.fg)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                    setBackgroundColor(bgColor)
                    setOnClickListener {
                        // Highlight active tool
                        activeCell?.setBackgroundColor(Color.parseColor(
                            tools.find { it.id == activeTool }?.bg ?: "#F5F5F5"))
                        setBackgroundColor(Color.parseColor("#1A73E8"))
                        activeCell = this
                        dlg?.dismiss()
                        onAnnotTool(t.id, fgColor)
                    }
                }
                // Tool icon (emoji-style colored square as fallback for any icon)
                cell.addView(View(this).apply {
                    val size = dp(32)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setBackgroundColor(fgColor.and(0x33FFFFFF.toInt()).or(fgColor.and(0xFF000000.toInt())))
                    // Use background color with opacity to create colored icon block
                }.also {
                    // Replace with actual ImageView
                })
                // Actually use ImageView with system icons
                cell.removeAllViews()
                val iconRes = when (t.id) {
                    AnnotationCanvasView.TOOL_FREEHAND   -> android.R.drawable.ic_menu_edit
                    AnnotationCanvasView.TOOL_HIGHLIGHT  -> android.R.drawable.ic_menu_view
                    AnnotationCanvasView.TOOL_UNDERLINE  -> android.R.drawable.ic_menu_edit
                    AnnotationCanvasView.TOOL_STRIKEOUT  -> android.R.drawable.ic_menu_edit
                    AnnotationCanvasView.TOOL_TEXT       -> android.R.drawable.ic_menu_edit
                    AnnotationCanvasView.TOOL_RECT       -> android.R.drawable.ic_menu_crop
                    AnnotationCanvasView.TOOL_CIRCLE     -> android.R.drawable.ic_menu_view
                    AnnotationCanvasView.TOOL_ARROW      -> android.R.drawable.ic_menu_directions
                    AnnotationCanvasView.TOOL_STAMP      -> android.R.drawable.ic_menu_agenda
                    AnnotationCanvasView.TOOL_ERASER     -> android.R.drawable.ic_menu_close_clear_cancel
                    AnnotationCanvasView.TOOL_MOVE_TEXT  -> android.R.drawable.ic_menu_directions
                    AnnotationCanvasView.TOOL_MOVE_SHAPE -> android.R.drawable.ic_menu_rotate
                    "image"   -> android.R.drawable.ic_menu_gallery
                    "color"   -> android.R.drawable.ic_menu_gallery
                    "undo"    -> android.R.drawable.ic_menu_revert
                    "redo"    -> android.R.drawable.ic_menu_rotate
                    "save"    -> android.R.drawable.ic_menu_save
                    "extract" -> android.R.drawable.ic_menu_send
                    else      -> android.R.drawable.ic_menu_edit
                }
                cell.addView(ImageView(this).apply {
                    setImageResource(iconRes)
                    setColorFilter(fgColor)
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                })
                cell.addView(TextView(this).apply {
                    text = t.lbl
                    textSize = 9.5f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#222222"))
                    setPadding(0, dp(5), 0, 0)
                    android.graphics.Typeface.DEFAULT_BOLD.let { typeface = it }
                })
                row.addView(cell)
            }
            // Pad last row if needed
            repeat(4 - rowTools.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
                })
            }
            grid.addView(row)
        }
        scroll.addView(grid)
        container.addView(titleBar)
        container.addView(scroll)

        dlg = AlertDialog.Builder(this)
            .setView(container)
            .create()
        // Make dialog full width
        dlg.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        dlg.window?.setGravity(Gravity.BOTTOM)
        return dlg
    }

    private fun buildBottomNavBar(): LinearLayout {
        data class Tab(val id: String, val lbl: String, val icon: Int)
        val tabs = listOf(
            Tab("view",     "View",     android.R.drawable.ic_menu_view),
            Tab("annotate", "Annotate", android.R.drawable.ic_menu_edit),
            Tab("pages",    "Pages",    android.R.drawable.ic_menu_agenda),
            Tab("secure",   "Secure",   android.R.drawable.ic_lock_lock),
            Tab("more",     "More",     android.R.drawable.ic_menu_more),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(58))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            elevation = dp(8).toFloat()
            tabs.forEach { tab ->
                val btn = LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { onBottomTab(tab.id) }
                }
                btn.addView(ImageView(this@ViewerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                    setImageResource(tab.icon)
                    setColorFilter(if (tab.id == "view") Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
                    tag = "${tab.id}_icon"
                })
                btn.addView(TextView(this@ViewerActivity).apply {
                    text = tab.lbl; textSize = 9f; gravity = Gravity.CENTER
                    setTextColor(if (tab.id == "view") Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
                    setPadding(0, dp(2), 0, 0); tag = "${tab.id}_label"
                })
                addView(btn)
            }
        }
    }

    private fun onBottomTab(tab: String) {
        listOf("view","annotate","pages","secure","more").forEach { t ->
            bottomNavBar.findViewWithTag<ImageView>("${t}_icon")?.setColorFilter(
                if (t == tab) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
            bottomNavBar.findViewWithTag<TextView>("${t}_label")?.setTextColor(
                if (t == tab) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
        }
        sizeBar.visibility = View.GONE
        if (tab != "annotate") {
            activeTool = AnnotationCanvasView.TOOL_NONE
            canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, activeColor) }
        }
        when (tab) {
            "annotate" -> buildAnnotToolbarGrid().show()
            "pages"    -> showPagesMenu()
            "secure"   -> showSecureMenu()
            "more"     -> showMoreMenu()
        }
    }

    // ---- PDF Loading ---------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri = getUri() ?: run { showError("No PDF to open"); return@launch }
                val file = withContext(Dispatchers.IO) { FileHelper.uriToFile(this@ViewerActivity, uri) }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot read PDF. Try opening again."); return@launch
                }
                pdfFile = file; tvInfo.text = file.name
                val isEncrypted = withContext(Dispatchers.IO) { isPdfEncrypted(file) }
                if (isEncrypted) { promptPasswordAndLoad(file); return@launch }
                renderPdf(file, null)
            } catch (e: Exception) { showError("Open failed: ${e.message ?: "Unknown error"}") }
        }
    }

    private fun isPdfEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val doc = PDDocument.load(file)
            val enc = doc.isEncrypted
            try { doc.close() } catch (_: Exception) {}
            enc
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: e.cause?.message?.lowercase() ?: ""
            msg.contains("password") || msg.contains("encrypt") || msg.contains("decrypt")
        }
    }

    private fun promptPasswordAndLoad(file: File) {
        val et = EditText(this).apply {
            hint = "Enter PDF password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Password Protected PDF")
            .setMessage("Enter the password to open this PDF:")
            .setView(et)
            .setPositiveButton("Open") { _, _ ->
                val pw = et.text.toString()
                if (pw.isBlank()) { toast("Enter a password"); return@setPositiveButton }
                lifecycleScope.launch { renderPdf(file, pw) }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private suspend fun renderPdf(file: File, password: String?) {
        pdfPassword = password
        val ok = tryAndroidRenderer(file, password)
        if (!ok) { pageContainer.removeAllViews(); canvases.clear(); pageScales.clear(); tryPdfBoxRenderer(file, password) }
    }

    private fun getUri(): Uri? {
        val uriStr = intent?.getStringExtra(EXTRA_PDF_URI)
        val path   = intent?.getStringExtra(EXTRA_PDF_PATH)
        return when {
            path   != null       -> Uri.fromFile(File(path))
            uriStr != null       -> Uri.parse(uriStr)
            intent?.data != null -> intent.data
            else                 -> null
        }
    }

    private suspend fun tryAndroidRenderer(file: File, password: String?): Boolean = try {
        withContext(Dispatchers.IO) {
            val fd   = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw   = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"; progressBar.visibility = View.VISIBLE
                pageSeekBar.max = (totalPages - 1).coerceAtLeast(1)
            }
            val INIT_PAGES = 6
            for (i in 0 until minOf(totalPages, INIT_PAGES)) {
                var scale = 1f
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    val pw = p.width.coerceAtLeast(1)
                    val ph = p.height.coerceAtLeast(1)
                    // Cap width to reduce memory (large PDFs crash with full DPI)
                    val maxW = (sw * 0.95f).toInt().coerceAtLeast(1)
                    scale = maxW.toFloat() / pw.toFloat()
                    val bh = (ph * scale).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(maxW, bh, Bitmap.Config.RGB_565)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                pageScales[i] = scale
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            // Add placeholders for remaining pages
            withContext(Dispatchers.Main) {
                val estH = pageContainer.getChildAt(0)?.height?.coerceAtLeast(100) ?: 300
                for (i in INIT_PAGES until totalPages) {
                    pageContainer.addView(FrameLayout(this@ViewerActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, estH).apply { setMargins(dp(3),dp(2),dp(3),dp(2)) }
                        setBackgroundColor(Color.WHITE); tag = "ph_$i"
                        addView(TextView(this@ViewerActivity).apply {
                            text = "Page ${i+1}"; textSize = 11f
                            setTextColor(Color.parseColor("#CCCCCC"))
                            gravity = android.view.Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(-1,-1)
                        })
                    })
                }
            }
            // Render remaining in background
            for (i in INIT_PAGES until totalPages) {
                if (i % 4 == 0) { kotlinx.coroutines.yield(); System.gc() }
                var scale = 1f
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    val pw = p.width.coerceAtLeast(1)
                    val ph = p.height.coerceAtLeast(1)
                    val maxW = (sw * 0.95f).toInt().coerceAtLeast(1)
                    scale = maxW.toFloat() / pw.toFloat()
                    val bh = (ph * scale).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(maxW, bh, Bitmap.Config.RGB_565)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                pageScales[i] = scale
                withContext(Dispatchers.Main) {
                    (pageContainer.findViewWithTag<FrameLayout>("ph_$i"))?.let {
                        pageContainer.removeView(it)
                    }
                    addPage(bmp, i)
                }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; tvPageCounter.text = "1/$totalPages" }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying PDFBox..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File, password: String?) = withContext(Dispatchers.IO) {
        try {
            val doc = if (password != null) {
                try { PDDocument.load(file, password) }
                catch (_: Exception) {
                    withContext(Dispatchers.Main) { showError("Incorrect password."); promptPasswordAndLoad(file) }; return@withContext
                }
            } else PDDocument.load(file)

            val rndr = PdfBoxRenderer(doc); totalPages = doc.numberOfPages
            val sw   = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"; progressBar.visibility = View.VISIBLE
                pageSeekBar.max = (totalPages - 1).coerceAtLeast(1)
            }
            for (i in 0 until totalPages) {
                val raw   = rndr.renderImageWithDPI(i, 120f)
                val rw    = raw.width.coerceAtLeast(1)
                val maxW  = (sw * 0.95f).toInt().coerceAtLeast(1)
                val scale = maxW.toFloat() / rw.toFloat()
                pageScales[i] = scale
                val th  = (raw.height * scale).toInt().coerceAtLeast(1)
                val sc2 = Bitmap.createScaledBitmap(raw, maxW, th, true)
                if (sc2 !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(sc2, i) }
            }
            doc.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE; tvPageCounter.text = "1/$totalPages" }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { showError("Cannot open: ${e.message}") }
        }
    }

    private fun addPage(bmp: Bitmap, idx: Int) {
        val frame = ZoomPageFrame(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
            setBackgroundColor(Color.WHITE)
        }
        val iv = ImageView(this).apply {
            // Use RGB_565 if not already (saves 50% memory vs ARGB_8888)
            val optimized = if (bmp.config == Bitmap.Config.ARGB_8888) {
                val r = bmp.copy(Bitmap.Config.RGB_565, false)
                bmp.recycle(); r
            } else bmp
            setImageBitmap(optimized)
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
        }
        val tvNum = TextView(this).apply {
            text = "${idx+1}"; textSize = 9f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(5), dp(1), dp(5), dp(1))
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(3), dp(3), 0)
            }
        }
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setTool(AnnotationCanvasView.TOOL_NONE, activeColor)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs); frame.addView(tvNum)
        pageContainer.addView(frame)
        applyReadingFilter(iv)
    }

    // ---- Navigation ---------------------------------------------------

    private fun updatePageCounter(scrollY: Int) {
        var cum = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < cum + child.height) {
                if (i != currentPage) {
                    currentPage = i
                    tvPageCounter.text = "${i+1}/$totalPages"
                    if (totalPages > 1) {
                        pageSeekBar.progress = (i.toFloat() / (totalPages-1) * 100).toInt()
                    }
                }
                return
            }
            cum += child.height
        }
    }

    private fun goToPage(page: Int) {
        val t = page.coerceIn(0, totalPages - 1)
        // Post twice to ensure all pages are measured (especially after initial render)
        scrollView.post {
            scrollView.post {
                var cum = 0
                for (i in 0 until t) {
                    val child = pageContainer.getChildAt(i)
                    if (child != null && child.height > 0) {
                        cum += child.height
                    } else {
                        // Page not laid out yet - estimate from first page height
                        val firstH = pageContainer.getChildAt(0)?.height ?: 0
                        cum += firstH
                    }
                }
                scrollView.scrollTo(0, cum)
                // Update page counter immediately
                currentPage = t
                tvPageCounter.text = "${t + 1}/$totalPages"
                if (totalPages > 1) {
                    pageSeekBar.progress = (t.toFloat() / (totalPages - 1) * 100).toInt()
                }
            }
        }
    }

    private fun showGotoDialog() {
        val et = EditText(this).apply { hint = "Page (1-$totalPages)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et)
            .setPositiveButton("Go") { _, _ ->
                et.text.toString().toIntOrNull()?.let { n ->
                    if (n in 1..totalPages) goToPage(n-1) else toast("Enter 1-$totalPages")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- Reading modes ------------------------------------------------

    private fun showReadingModeMenu() {
        AlertDialog.Builder(this).setTitle("Reading Mode")
            .setItems(arrayOf("Normal", "Night (Dark)", "Sepia (Warm)", "High Contrast")) { _, which ->
                isDark = false; isSepia = false
                when (which) { 1 -> isDark = true; 2 -> isSepia = true }
                applyReadingModeToAll()
            }.show()
    }

    private fun applyReadingModeToAll() {
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            applyReadingFilter(frame.getChildAt(0) as? ImageView ?: continue)
        }
        scrollView.setBackgroundColor(when {
            isDark  -> Color.BLACK
            isSepia -> Color.parseColor("#2C1A0E")
            else    -> Color.parseColor("#303030")
        })
    }

    private fun applyReadingFilter(iv: ImageView) {
        iv.colorFilter = when {
            isDark  -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)))
            isSepia -> ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f)))
            else -> null
        }
    }

    // ---- Annotation tools ---------------------------------------------

    private fun onAnnotTool(id: String, color: Int) {
        when (id) {
            "undo"    -> { canvases.values.forEach { it.undo() }; toast("Undone") }
            "redo"    -> { canvases.values.forEach { it.redo() }; toast("Redone") }
            "color"   -> pickColor { c -> activeColor = c; canvases.values.forEach { it.setColor(c) } }
            "image"   -> pickImageForInsertion()
            "extract" -> extractText()
            "save"    -> saveWithAnnotations()
            AnnotationCanvasView.TOOL_TEXT   -> showTextInputDialog()
            AnnotationCanvasView.TOOL_STAMP  -> showStampPicker()
            AnnotationCanvasView.TOOL_HIGHLIGHT -> {
                activeTool  = id
                val hlColor = if (activeColor == Color.parseColor("#E53935") || activeColor == Color.parseColor("#1A73E8"))
                    AnnotationCanvasView.HIGHLIGHT_DEFAULT_COLOR else activeColor
                activeColor = hlColor
                canvases.values.forEach { cv -> cv.setTool(id, hlColor); cv.setStrokeWidth(strokeWidth) }
                sizeBar.visibility = View.VISIBLE
                toast("Highlight active - drag to highlight")
            }
            else -> {
                activeTool = id; activeColor = color
                canvases.values.forEach { cv ->
                    cv.setTool(id, color); cv.setStrokeWidth(strokeWidth); cv.setTextSize(textSizePx)
                }
                sizeBar.visibility = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND, AnnotationCanvasView.TOOL_ERASER -> View.VISIBLE
                    else -> View.GONE
                }
                toast(when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "Pen: drag to draw"
                    AnnotationCanvasView.TOOL_UNDERLINE -> "Underline: drag under text"
                    AnnotationCanvasView.TOOL_STRIKEOUT -> "Strike: drag across text"
                    AnnotationCanvasView.TOOL_RECT      -> "Box: drag to draw"
                    AnnotationCanvasView.TOOL_CIRCLE    -> "Circle: drag to draw"
                    AnnotationCanvasView.TOOL_ARROW     -> "Arrow: drag from A to B"
                    AnnotationCanvasView.TOOL_ERASER    -> "Erase: drag to erase"
                    AnnotationCanvasView.TOOL_MOVE_TEXT -> "Move: drag a text note"
                    else -> id
                })
            }
        }
    }

    private fun pickImageForInsertion() {
        imageLauncher.launch("image/*")
    }

    private fun insertImageOnPage(uri: android.net.Uri) {
        val file = pdfFile ?: run { toast("Open a PDF first"); return }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri)
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
                if (bmp == null) { toast("Cannot load image"); progressBar.visibility = View.GONE; return@launch }
                // Scale image to fit ~40% of page width
                val scale = pageScales[currentPage] ?: 1f
                val targetW = (resources.displayMetrics.widthPixels * 0.4f).toInt()
                val ratio   = targetW.toFloat() / bmp.width.toFloat()
                val targetH = (bmp.height * ratio).toInt()
                val scaled  = android.graphics.Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                bmp.recycle()
                // Convert to PNG bytes
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                scaled.recycle()
                val pngBytes = baos.toByteArray()

                // Save image to temp file and embed in PDF on current page
                val tmpImg = java.io.File(cacheDir, "insert_img_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(tmpImg).use { it.write(pngBytes) }
                val tmpPdf = java.io.File(cacheDir, "${file.nameWithoutExtension}_img.pdf")

                pdfOps.insertImageOnPage(file, tmpPdf, tmpImg, currentPage + 1).fold(
                    onSuccess = { result ->
                        progressBar.visibility = View.GONE
                        saveAndNotify(result, "${file.nameWithoutExtension}_with_image")
                    },
                    onFailure = { e ->
                        progressBar.visibility = View.GONE
                        toast("Image insert failed: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                toast("Error: ${e.message}")
            }
        }
    }

    private fun showStampPicker() {
        val stamps = arrayOf(
            "APPROVED", "REJECTED", "DRAFT", "CONFIDENTIAL",
            "SIGN HERE", "REVIEWED", "VOID", "FINAL", "COPY", "ORIGINAL",
            "+ Custom Text..."
        )
        AlertDialog.Builder(this).setTitle("Choose Stamp")
            .setItems(stamps) { _, i ->
                if (i == stamps.size - 1) {
                    // Custom stamp
                    val et = EditText(this).apply {
                        hint = "Enter stamp text"
                        textSize = 15f
                        setPadding(dp(20), dp(8), dp(20), dp(8))
                    }
                    AlertDialog.Builder(this).setTitle("Custom Stamp Text").setView(et)
                        .setPositiveButton("Use") { _, _ ->
                            val custom = et.text.toString().trim().uppercase()
                            if (custom.isEmpty()) return@setPositiveButton
                            activeTool = AnnotationCanvasView.TOOL_STAMP
                            canvases.values.forEach { cv ->
                                cv.setTool(AnnotationCanvasView.TOOL_STAMP, Color.parseColor("#1A73E8"))
                                cv.setPendingStamp(custom)
                            }
                            toast("Tap page to place: $custom")
                        }.setNegativeButton("Cancel", null).show()
                } else {
                    activeTool = AnnotationCanvasView.TOOL_STAMP
                    val stampColor = when (i) {
                        0, 5 -> Color.parseColor("#2E7D32")  // Approved/Reviewed = green
                        1    -> Color.parseColor("#C62828")  // Rejected = red
                        3    -> Color.parseColor("#E65100")  // Confidential = orange
                        else -> Color.parseColor("#1565C0")  // Others = blue
                    }
                    canvases.values.forEach { cv ->
                        cv.setTool(AnnotationCanvasView.TOOL_STAMP, stampColor)
                        cv.setPendingStamp(stamps[i])
                    }
                    toast("Tap page to place: ${stamps[i]}")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTextInputDialog() {
        // Auto-detect page text size from current page rendering scale
        val autoSize = estimatePageTextSize()

        val lay  = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val et = EditText(this).apply {
            hint = "Type text (Tamil, Hindi, Arabic, any language)"
            textSize = 15f
            maxLines = 5
            setBackgroundColor(Color.parseColor("#F0F4FF"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Auto-size info row
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val tvAuto = TextView(this).apply {
            text = "Auto: ${autoSize.toInt()}px  |  Custom:"
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
        }
        val btnAuto = Button(this).apply {
            text = "Reset to Auto"
            textSize = 10f
            setTextColor(Color.parseColor("#1A73E8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                textSizePx = autoSize
                canvases.values.forEach { it.setTextSize(autoSize) }
                toast("Size set to ${autoSize.toInt()}px")
            }
        }
        autoRow.addView(tvAuto)
        autoRow.addView(btnAuto)

        // Size slider row
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tvSz = TextView(this).apply {
            text = "${autoSize.toInt()}px"
            textSize = 12f
            minWidth = dp(50)
            setTextColor(Color.parseColor("#333333"))
        }
        textSizePx = autoSize
        val sk = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 120; progress = autoSize.toInt().coerceIn(4, 120)
        }
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val sz = v.coerceAtLeast(8).toFloat()
                textSizePx = sz
                tvSz.text = "${sz.toInt()}px"
                canvases.values.forEach { it.setTextSize(sz) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sRow.addView(tvSz); sRow.addView(sk)

        // Color row
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        cRow.addView(TextView(this).apply {
            text = "Color: "; textSize = 12f; setPadding(0, 0, dp(8), 0)
        })
        var textColor = Color.BLACK
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setBackgroundColor(textColor)
            setOnClickListener { pickColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        cRow.addView(swatch)

        lay.addView(et); lay.addView(autoRow); lay.addView(sRow); lay.addView(cRow)

        AlertDialog.Builder(this)
            .setTitle("Add Text Note")
            .setMessage("Size is auto-matched to page text. Tap Place, then tap on the page.")
            .setView(lay)
            .setPositiveButton("Place") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isEmpty()) { toast("Type something first"); return@setPositiveButton }
                activeTool = AnnotationCanvasView.TOOL_TEXT
                activeColor = textColor
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    cv.setTextSize(textSizePx)
                    cv.setPendingText(txt)
                }
                toast("Tap page to place text")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Estimate the body text size on the current PDF page based on render scale */
    private fun estimatePageTextSize(): Float {
        // PDF body text is typically 10-12pt. At render scale, convert to screen pixels.
        val scale = pageScales[currentPage] ?: 1f
        // 11pt text at 72dpi = 11px. Scale to screen pixels.
        val pdfTextPtSize = 11f  // typical body text
        val screenPx = pdfTextPtSize * scale
        // Clamp to reasonable annotation size range
        return screenPx.coerceIn(20f, 80f)
    }

    private fun pickColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = 4; setPadding(dp(12),dp(12),dp(12),dp(12)) }
        var dlg: AlertDialog? = null
        colorHex.forEachIndexed { i, hex ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply { width=dp(52); height=dp(52); setMargins(dp(5),dp(5),dp(5),dp(5)) }
                setBackgroundColor(Color.parseColor(hex)); elevation=dp(2).toFloat()
                setOnClickListener { onPick(Color.parseColor(hex)); dlg?.dismiss(); toast(colorNames[i]) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Choose Color").setView(grid).setNegativeButton("Cancel",null).show()
    }

    // ---- Menus --------------------------------------------------------

    private fun showPagesMenu() {
        AlertDialog.Builder(this).setTitle("Page Actions")
            .setItems(arrayOf("Extract page range...","Delete current page (${currentPage+1})","Rotate page ${currentPage+1}","Add page numbers","Go to page...")) { _, which ->
                val file = pdfFile ?: return@setItems
                when (which) { 0->doExtractPages(file); 1->doDeletePage(file,currentPage+1); 2->doRotatePage(file,currentPage+1); 3->doAddPageNumbers(file); 4->showGotoDialog() }
            }.setNegativeButton("Close", null).show()
    }

    private fun showSecureMenu() {
        val file = pdfFile ?: run { toast("Open a PDF first"); return }
        AlertDialog.Builder(this).setTitle("Security")
            .setItems(arrayOf("Password protect...","Remove password...","Add watermark...","Redaction guide")) { _, which ->
                when (which) { 0->doPasswordProtect(file); 1->doRemovePassword(file); 2->doAddWatermark(file); 3->showRedactGuide() }
            }.setNegativeButton("Close", null).show()
    }

    private fun showMoreMenu() {
        AlertDialog.Builder(this).setTitle("More Options")
            .setItems(arrayOf("Print PDF","Share PDF","Page size & orientation...","Compress PDF","Extract text / OCR","E-Signature placeholder","Form fill (info)","About")) { _, which ->
                val file = pdfFile
                when (which) {
                    0->printPdf(); 1->sharePdf()
                    2->file?.let{showPageSizeDialog()}?:toast("Open a PDF first")
                    3->file?.let{doCompressQuick(it)}?:toast("Open a PDF first")
                    4->file?.let{doOcrExtract(it)}?:toast("Open a PDF first")
                    5->showESignatureInfo()
                    6->showFormFillInfo()
                    7->showAbout()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showESignatureInfo() {
        AlertDialog.Builder(this).setTitle("E-Signature")
            .setMessage("To add a signature:\n\n1. Tap Annotate tab\n2. Use Pen tool to draw your signature\n3. Use Move tool to reposition it\n4. Tap Save to embed in PDF\n\nFor a pre-saved signature image:\n1. Save your signature as a PNG image\n2. Use 'Insert Image' (coming in next update)\n3. Position and save")
            .setPositiveButton("Got it", null).show()
    }

    private fun showFormFillInfo() {
        AlertDialog.Builder(this).setTitle("Form Filling")
            .setMessage("For PDF forms with text fields:\n\n1. Use the Text tool in Annotate\n2. Tap on the form field area\n3. Type your text and tap Place\n4. Save the annotated PDF\n\nFull automatic form field detection requires iText7 Pro license (AGPL-compatible version coming soon).")
            .setPositiveButton("OK", null).show()
    }

    private fun showRedactGuide() {
        AlertDialog.Builder(this).setTitle("Redaction")
            .setMessage("To permanently redact text:\n\n1. Tap Annotate tab\n2. Select Box tool\n3. Set color to Black\n4. Draw over sensitive text\n5. Save the annotated PDF\n\nThe black box will be embedded in the saved PDF, permanently covering the content.")
            .setPositiveButton("Annotate Now") { _, _ -> onBottomTab("annotate") }
            .setNegativeButton("Close", null).show()
    }

    private fun doPasswordProtect(file: File) {
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint="Confirm password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Protect") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank() || pw != et2.text.toString()) { toast("Passwords must match"); return@setPositiveButton }
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.encryptPdf(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_protected"), pw, pw).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_protected") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRemovePassword(file: File) {
        val et = EditText(this).apply { hint="Current password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Remove") { _, _ ->
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.removePdfPassword(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_unlocked"), et.text.toString()).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_unlocked") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Incorrect password.") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doAddWatermark(file: File) {
        val et = EditText(this).apply { setText("CONFIDENTIAL"); setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(et)
            .setPositiveButton("Add") { _, _ ->
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.addTextWatermark(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_watermarked"), et.text.toString().ifBlank{"CONFIDENTIAL"}).fold(
                        onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_watermarked")},
                        onFailure={progressBar.visibility=View.GONE;showError("Failed: ${it.message}")}
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun printPdf() {
        val file = pdfFile ?: run { toast("No PDF"); return }
        val pm   = getSystemService(PRINT_SERVICE) as? PrintManager ?: run { toast("Print not available"); return }
        val name = "${file.nameWithoutExtension}_print"
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, b: android.os.Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(totalPages).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, dest: android.os.ParcelFileDescriptor?, cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                try { dest?.let{d->file.inputStream().use{i->FileOutputStream(d.fileDescriptor).use{o->i.copyTo(o)}}}; cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }

    private fun showPageSizeDialog() {
        val file = pdfFile ?: return
        val sizes = arrayOf("A4 (210x297mm)","A3 (297x420mm)","A5 (148x210mm)","Letter (216x279mm)","Legal (216x356mm)")
        var selectedSize = 0; var landscape = false
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ViewerActivity, android.R.layout.simple_spinner_item, sizes).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?,v: View?,pos: Int,id: Long) { selectedSize=pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val rg=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL}
        val rbP=RadioButton(this).apply{text="Portrait";isChecked=true}
        val rbL=RadioButton(this).apply{text="Landscape"}
        rg.addView(rbP);rg.addView(rbL);rg.setOnCheckedChangeListener{_,id->landscape=(id==rbL.id)}
        lay.addView(spinner);lay.addView(rg)
        AlertDialog.Builder(this).setTitle("Page Size").setView(lay)
            .setPositiveButton("Export") { _, _ ->
                val mm = {v: Float -> v*2.8346f}
                val (w,h) = when(selectedSize){0->Pair(mm(210f),mm(297f));1->Pair(mm(297f),mm(420f));2->Pair(mm(148f),mm(210f));3->Pair(mm(216f),mm(279f));else->Pair(mm(216f),mm(356f))}
                val (pw,ph) = if(landscape) Pair(h,w) else Pair(w,h)
                progressBar.visibility=View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.reshapePageSize(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_resized"), pw, ph).fold(
                        onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_resized")},
                        onFailure={progressBar.visibility=View.GONE;showError("Export failed: ${it.message}")}
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompressQuick(file: File) {
        progressBar.visibility=View.VISIBLE
        lifecycleScope.launch {
            pdfOps.compressPdf(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_compressed"), 9).fold(
                onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_compressed")},
                onFailure={progressBar.visibility=View.GONE;showError("Failed: ${it.message}")}
            )
        }
    }

    private fun doOcrExtract(file: File) {
        // Ask user: current page or full document
        val pageLabel = "Page ${currentPage + 1} only (fast)"
        val docLabel  = "Full document (may take a while)"
        AlertDialog.Builder(this)
            .setTitle("Extract Text")
            .setItems(arrayOf(pageLabel, docLabel)) { _, which ->
                if (which == 0) doOcrPage(file, currentPage + 1)
                else doOcrFullDoc(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doOcrPage(file: File, pageNum: Int) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword)
                              else PDDocument.load(file)
                    val s = com.tom_roush.pdfbox.text.PDFTextStripper()
                    s.sortByPosition = true
                    s.startPage = pageNum
                    s.endPage   = pageNum
                    val t = s.getText(doc)
                    doc.close()
                    t.trim().ifBlank { null }
                } catch (_: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                toast("No text on page $pageNum (image-only page)")
                return@launch
            }
            showTextResult("Page $pageNum Text", text)
        }
    }

    private fun doOcrFullDoc(file: File) {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvInfo.text = "Extracting text..."
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                var doc: PDDocument? = null
                try {
                    doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword)
                          else PDDocument.load(file)
                    val total = doc.numberOfPages
                    val sb = StringBuilder()
                    for (i in 1..total) {
                        val s = com.tom_roush.pdfbox.text.PDFTextStripper()
                        s.sortByPosition = true
                        s.startPage = i; s.endPage = i
                        val pageText = try { s.getText(doc) } catch (_: Exception) { "" }
                        if (pageText.isNotBlank()) {
                            sb.append("--- Page $i ---"); sb.append("\n")
                            sb.append(pageText)
                            sb.append("\n"); sb.append("\n")
                        }
                        if (i % 10 == 0) kotlinx.coroutines.yield()
                    }
                    sb.toString().trim().ifBlank { null }
                } catch (_: Exception) { null }
                finally {
                    try { doc?.close() } catch (_: Exception) {}
                }
            }
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = true
            tvInfo.text = pdfFile?.name ?: ""
            if (text.isNullOrBlank()) {
                toast("No extractable text found (image-only PDF)")
                return@launch
            }
            showTextResult("Full Document Text (${text.length} chars)", text)
        }
    }

    private fun showTextResult(title: String, text: String) {
        val et = EditText(this).apply {
            setText(text)
            textSize = 12f
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(380))
                addView(et)
            })
            .setPositiveButton("Copy All") { _, _ ->
                val clip = android.content.ClipboardManager::class.java
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                toast("Copied ${text.length} characters!")
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                    "Share Text"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("ProPDF Editor v3.0")
            .setMessage("FREE PDF Editor - All Premium Features\n\nAnnotate: Pen, Highlight (Yellow), Underline, Strike, Text, Rectangle, Circle, Arrow, Stamps, Eraser\nView: Night/Sepia modes, Page seek bar\nFind: Search with page navigation\nPages: Extract, Delete, Rotate\nSecurity: Password protect/remove, Watermark\nMore: Print, Page size, Compress\n\nNo ads. No watermarks. 100% free.")
            .setPositiveButton("OK",null).show()
    }

    private fun doExtractPages(file: File) {
        val et = EditText(this).apply{hint="Range e.g. 2-5";setPadding(dp(20),dp(8),dp(20),dp(8))}
        AlertDialog.Builder(this).setTitle("Extract Pages").setView(et)
            .setPositiveButton("Extract"){_,_->
                val parts=et.text.toString().split("-"); val from=parts.getOrNull(0)?.trim()?.toIntOrNull()?:1; val to=parts.getOrNull(1)?.trim()?.toIntOrNull()?:from
                if(from>to){toast("Invalid range");return@setPositiveButton}
                progressBar.visibility=View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.splitPdf(file,cacheDir,listOf(from..to)).fold(
                        onSuccess={files->progressBar.visibility=View.GONE;files.firstOrNull()?.let{saveAndNotify(it,"${file.nameWithoutExtension}_pages${from}_$to")}},
                        onFailure={progressBar.visibility=View.GONE;showError("Extract failed: ${it.message}")}
                    )
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun doDeletePage(file: File, pageNum: Int) {
        AlertDialog.Builder(this).setTitle("Delete Page $pageNum?")
            .setPositiveButton("Delete"){_,_->
                progressBar.visibility=View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.deletePages(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_deleted"),listOf(pageNum)).fold(
                        onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_deleted")},
                        onFailure={progressBar.visibility=View.GONE;showError("Failed: ${it.message}")}
                    )
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private fun doRotatePage(file: File, pageNum: Int) {
        progressBar.visibility=View.VISIBLE
        lifecycleScope.launch {
            pdfOps.rotatePages(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_rotated"),mapOf(pageNum to 90)).fold(
                onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_rotated")},
                onFailure={progressBar.visibility=View.GONE;showError("Failed: ${it.message}")}
            )
        }
    }

    private fun doAddPageNumbers(file: File) {
        progressBar.visibility=View.VISIBLE
        lifecycleScope.launch {
            pdfOps.addPageNumbers(file, FileHelper.tempFile(this@ViewerActivity,file.nameWithoutExtension+"_numbered")).fold(
                onSuccess={f->progressBar.visibility=View.GONE;saveAndNotify(f,"${file.nameWithoutExtension}_numbered")},
                onFailure={progressBar.visibility=View.GONE;showError("Failed: ${it.message}")}
            )
        }
    }

    // ---- Extract text -------------------------------------------------

    private fun extractText() {
        val file = pdfFile ?: run{toast("No PDF");return}
        progressBar.visibility=View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO){
                try{val doc=if(pdfPassword!=null)PDDocument.load(file,pdfPassword) else PDDocument.load(file);val s=com.tom_roush.pdfbox.text.PDFTextStripper().also{it.sortByPosition=true};val t=s.getText(doc);doc.close();t.trim().ifBlank{null}}catch(_:Exception){null}
            }
            progressBar.visibility=View.GONE
            if(text.isNullOrBlank()){toast("No extractable text");return@launch}
            val et=EditText(this@ViewerActivity).apply{setText(text);textSize=12f;setTextIsSelectable(true);isFocusable=true;isFocusableInTouchMode=true;background=null}
            AlertDialog.Builder(this@ViewerActivity).setTitle("Extracted Text")
                .setView(ScrollView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(380));addView(et)})
                .setPositiveButton("Copy All"){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PDF Text",text));toast("Copied!")}
                .setNeutralButton("Share"){_,_->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Share"))}
                .setNegativeButton("Close",null).show()
        }
    }

    // ---- Save annotations ---------------------------------------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run{toast("No file");return}
        if (canvases.values.none{it.hasAnnotations()}){toast("No annotations - draw something first");return}
        val et=EditText(this).apply{setText(file.nameWithoutExtension+"_annotated");selectAll();setPadding(dp(20),dp(8),dp(20),dp(8))}
        AlertDialog.Builder(this).setTitle("Save Annotated PDF").setView(et)
            .setPositiveButton("Save"){_,_->
                val name=et.text.toString().trim().ifBlank{file.nameWithoutExtension+"_annotated"}.removeSuffix(".pdf")
                doSaveAnnotations(file,name)
            }.setNegativeButton("Cancel",null).show()
    }

    private fun doSaveAnnotations(file: File, outName: String) {
        progressBar.visibility=View.VISIBLE;tvInfo.text="Saving..."
        lifecycleScope.launch {
            try {
                val pd = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val td = mutableMapOf<Int, Pair<List<AnnotationCanvasView.TextAnnot>, Float>>()
                for ((idx,cvs) in canvases) {
                    val scale=pageScales[idx]?:1f
                    cvs.getStrokes().takeIf{it.isNotEmpty()}?.let{pd[idx]=Pair(it,scale)}
                    cvs.getTextAnnots().takeIf{it.isNotEmpty()}?.let{td[idx]=Pair(it,scale)}
                }
                if(pd.isEmpty()&&td.isEmpty()){progressBar.visibility=View.GONE;tvInfo.text=file.name;toast("No annotations");return@launch}
                pdfOps.saveAnnotationsToPdf(file, FileHelper.tempFile(this@ViewerActivity,outName), pd, td).fold(
                    onSuccess={savedTmp->val named=File(cacheDir,"$outName.pdf");savedTmp.copyTo(named,overwrite=true);progressBar.visibility=View.GONE;tvInfo.text=file.name;saveAndNotify(named,outName)},
                    onFailure={progressBar.visibility=View.GONE;tvInfo.text=file.name;showError("Save failed: ${it.message}")}
                )
            } catch(e: Exception){progressBar.visibility=View.GONE;tvInfo.text=file.name;showError("Error: ${e.message}")}
        }
    }

    private suspend fun saveAndNotify(file: File, desiredName: String) {
        val saved=withContext(Dispatchers.IO){
            try{FileHelper.saveToDownloads(this@ViewerActivity,file)}
            catch(_: Exception){FileHelper.SaveResult("app storage",Uri.fromFile(file),file)}
        }
        AlertDialog.Builder(this@ViewerActivity).setTitle("Saved!")
            .setMessage("$desiredName.pdf" + "\n\n" + saved.displayPath).setPositiveButton("OK",null).show()
    }

    private fun sharePdf() {
        val f=pdfFile?:return
        try {
            val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.provider",f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share PDF"))
        } catch(e: Exception){toast("Share error: ${e.message}")}
    }

    private fun showError(msg: String) {
        progressBar.visibility=View.GONE
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C")); tvInfo.text="Error"
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show()
    }

    private fun makeImgBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE)
        setPadding(dp(10),dp(10),dp(10),dp(10)); setOnClickListener{action()}
    }
    private fun makeTextBtn(label: String, action: () -> Unit) = Button(this).apply {
        text=label; textSize=10f; setTextColor(Color.parseColor("#AADDFF"))
        setBackgroundColor(Color.TRANSPARENT); setPadding(dp(7),0,dp(7),0); setOnClickListener{action()}
    }
    private fun toast(msg: String) = Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val iv    = frame.getChildAt(0) as? ImageView ?: continue
            (iv.drawable as? android.graphics.drawable.BitmapDrawable)
                ?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            iv.setImageBitmap(null)
        }
        canvases.clear(); pageScales.clear()
    }

    // ---- Pinch-to-zoom + double-tap for each page ----------------------
    inner class ZoomPageFrame(ctx: android.content.Context) : FrameLayout(ctx) {
        private var scaleFactor = 1f
        private var lastTapTime = 0L
        private val scaleGD = android.view.ScaleGestureDetector(ctx,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * d.scaleFactor).coerceIn(1f, 4f)
                    scaleX = scaleFactor; scaleY = scaleFactor; return true
                }
            })
        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            scaleGD.onTouchEvent(ev)
            // Only handle double-tap to reset zoom
            if (ev.action == android.view.MotionEvent.ACTION_UP && !scaleGD.isInProgress) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 280L) {
                    scaleFactor = if (scaleFactor > 1.1f) 1f else 2f
                    scaleX = scaleFactor; scaleY = scaleFactor
                }
                lastTapTime = now
            }
            // Only consume events during active pinch - otherwise let ScrollView scroll
            return scaleGD.isInProgress
        }
    }
}
