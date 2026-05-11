package com.propdf.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.R
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.utils.FileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_PASSWORD = "extra_pdf_password"
        const val EXTRA_DISPLAY_NAME = "extra_pdf_display_name"

        fun start(context: Context, uri: Uri, password: String? = null, displayName: String? = null) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                password?.let { putExtra(EXTRA_PASSWORD, it) }
                displayName?.let { putExtra(EXTRA_DISPLAY_NAME, it) }
            }
            context.startActivity(intent)
        }
    }

    @Inject lateinit var pdfOps: com.propdf.editor.data.repository.PdfOperationsManager

    private lateinit var rootFrame: FrameLayout
    private lateinit var pageContainer: LinearLayout
    private lateinit var pageCounter: TextView
    private lateinit var searchBox: EditText
    private lateinit var searchResultLabel: TextView
    private lateinit var searchCountLabel: TextView
    private lateinit var bottomBar: LinearLayout

    private var pdfUri: Uri? = null
    private var pdfFile: File? = null
    private var pdfRenderer: PdfRenderer? = null
    private var totalPages = 0
    private var currentPage = 0
    private var currentZoom = 1.0f
    private var isDark = true
    private var isSearchMode = false
    private var searchResults = listOf<Int>()
    private var searchResultIdx = 0
    private val pageTextCache = mutableMapOf<Int, String>()
    private val pageBitmapCache = android.util.LruCache<Int, Bitmap>(10)
    private val pageScaleMap = mutableMapOf<Int, Float>()
    private val bookmarkedPages = mutableSetOf<Int>()
    private val annotOverlays = mutableMapOf<Int, AnnotationCanvasView>()
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private val c_pri = Color.parseColor("#448AFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr != null) loadPdf(Uri.parse(uriStr))
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
        pageBitmapCache.evictAll()
        annotOverlays.values.forEach { it.release() }
        annotOverlays.clear()
    }

    // -------------------------------------------------------
    // ROOT UI
    // -------------------------------------------------------

    private fun buildUI() {
        applySystemBarColors()
        rootFrame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        column.addView(buildTopBar())
        column.addView(buildSearchBar())
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ -> updatePageFromScroll(scrollY) }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll.addView(pageContainer)
        column.addView(scroll)
        column.addView(buildBottomBar())
        rootFrame.addView(column)
        setContentView(rootFrame)
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg()); setPadding(dp(12), dp(40), dp(12), dp(10))
            addView(TextView(this@ViewerActivity).apply {
                text = "Back"; textSize = 14f; setTextColor(c_pri); setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
            addView(View(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(TextView(this@ViewerActivity).apply {
                text = "Search"; textSize = 14f; setTextColor(c_pri); setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { toggleSearchMode() }
            })
            addView(TextView(this@ViewerActivity).apply {
                text = "More"; textSize = 14f; setTextColor(c_pri); setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showPdfOpsMenu() }
            })
        }
    }

    private fun buildSearchBar(): LinearLayout {
        val cBg = Color.parseColor("#1E1E2E")
        val cInput = Color.parseColor("#0E0E1A")
        val cBlue = Color.parseColor("#ADC6FF")
        val cDark = Color.parseColor("#4B8EFF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE

            val row1 = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val wrap = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            }
            searchBox = EditText(this@ViewerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                hint = "Search in PDF..."
                setHintTextColor(Color.parseColor("#666888"))
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(cInput)
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(12), 0, dp(12), 0)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setSingleLine(true)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        hideKeyboard()
                        runSearch()
                        true
                    } else false
                }
            }
            wrap.addView(searchBox)
            row1.addView(wrap)

            row1.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(44))
                text = "Find"
                setTextColor(Color.parseColor("#001A4D"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    colors = intArrayOf(cBlue, cDark)
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener { hideKeyboard(); runSearch() }
            })

            val closeBtn = ImageButton(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginStart = dp(4) }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { hideSearchBar() }
            }
            row1.addView(closeBtn)
            addView(row1)
            addView(View(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(6)) })

            val row2 = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row2.addView(buildSearchNavBtn("< Prev") {
                if (searchResults.isNotEmpty()) {
                    searchResultIdx = (searchResultIdx - 1 + searchResults.size) % searchResults.size
                    scrollToPage(searchResults[searchResultIdx])
                    updateSearchCounter()
                }
            })
            searchCountLabel = TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(cBlue)
                text = "Tap Find"
            }
            row2.addView(searchCountLabel)
            row2.addView(buildSearchNavBtn("Next >") {
                if (searchResults.isNotEmpty()) {
                    searchResultIdx = (searchResultIdx + 1) % searchResults.size
                    scrollToPage(searchResults[searchResultIdx])
                    updateSearchCounter()
                }
            })
            row2.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(8) }
                text = "Go to Pg"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(cBlue)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2D2D44"))
                    cornerRadius = dp(8).toFloat()
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
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1"))
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun buildBottomBar(): LinearLayout {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE)
            setPadding(0, dp(10), 0, dp(24))
        }
        pageCounter = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor(txt2())); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bottomBar.addView(pageCounter)
        val navBtns = listOf(
            "< Prev" to { scrollToPage((currentPage - 1).coerceAtLeast(0)) },
            "Next >" to { scrollToPage((currentPage + 1).coerceAtMost(totalPages - 1)) },
            "Zoom+" to { applyZoom(1.2f) },
            "Zoom-" to { applyZoom(0.8f) },
            "Fit" to { applyZoom(1.0f) },
            "Annot" to { showAnnotationMenu() },
            "OCR" to { showOcrMenu() },
            "Theme" to { toggleTheme() }
        )
        navBtns.forEach { (label, action) ->
            bottomBar.addView(TextView(this).apply {
                text = label; textSize = 11f; setTextColor(c_pri); gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { action() }
            })
        }
        return bottomBar
    }

    // -------------------------------------------------------
    // PDF LOADING
    // -------------------------------------------------------

    private fun loadPdf(uri: Uri) {
        pdfUri = uri
        lifecycleScope.launch {
            try {
                if (!isOpenableUri(uri)) return@launch
                val file = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                if (file == null) { toast("Cannot read PDF"); return@launch }
                if (!file.exists() || !FileHelper.isPdf(file)) { toast("Not a valid PDF"); return@launch }
                pdfFile = file; pageTextCache.clear()
                val ok = withContext(Dispatchers.IO) { try { openPdfRenderer(file); true } catch (_: Exception) { false } }
                if (!ok) { toast("Error opening PDF"); return@launch }
                renderAllPages()
            } catch (_: CancellationException) {}
            catch (_: Exception) { toast("Error opening PDF") }
        }
    }

    private fun isOpenableUri(uri: Uri): Boolean {
        val t = uri.toString().trim(); if (t.isEmpty()) { toast("Invalid file URI"); return false }
        if (uri.scheme == "file") {
            val f = java.io.File(uri.path ?: ""); if (!f.exists() || !f.isFile) { toast("File does not exist"); return false }
            if (!FileHelper.isPdf(f)) { toast("Not a valid PDF"); return true }; return true
        }
        if (!FileHelper.isValidPdfUri(this, uri)) { toast("Cannot read PDF from URI"); return false }; return true
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") { val f = java.io.File(uri.path ?: ""); return if (f.exists() && f.isFile) f else null }
            val dest = File(cacheDir, "viewer_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(dest).use { inp.copyTo(it) } }
            if (dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    private fun openPdfRenderer(file: File) {
        closePdfRenderer()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(pfd)
        totalPages = pdfRenderer?.pageCount ?: 0
    }

    private fun closePdfRenderer() { try { pdfRenderer?.close() } catch (_: Exception) {}; pdfRenderer = null }

    private suspend fun renderAllPages() {
        val renderer = pdfRenderer ?: return; val screenW = resources.displayMetrics.widthPixels - dp(16)
        totalPages = renderer.pageCount; pageScaleMap.clear(); pageBitmapCache.evictAll()
        withContext(Dispatchers.Main) { pageContainer.removeAllViews(); annotOverlays.clear(); pageCounter.text = "Page 1 of $totalPages" }
        for (i in 0 until totalPages) {
            val bmp = withContext(Dispatchers.IO) { renderPageBitmap(i, screenW) } ?: continue
            withContext(Dispatchers.Main) { addPageView(bmp, i) }
        }
        preloadNearbyPages(currentPage)
        withContext(Dispatchers.Main) { applyViewerTheme(); annotOverlays.values.forEach { it.invalidate() } }
    }

    private fun renderPageBitmap(pageIndex: Int, screenW: Int): Bitmap? {
        val cached = pageBitmapCache.get(pageIndex); if (cached != null && !cached.isRecycled) return cached
        val renderer = pdfRenderer ?: return null
        synchronized(renderer) {
            val page = renderer.openPage(pageIndex)
            return try {
                val scale = screenW.toFloat() / page.width.coerceAtLeast(1).toFloat()
                val bmpW = (page.width * scale).toInt().coerceAtLeast(1); val bmpH = (page.height * scale).toInt().coerceAtLeast(1)
                val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageScaleMap[pageIndex] = scale; pageBitmapCache.put(pageIndex, b); b
            } finally { page.close() }
        }
    }

    private fun preloadNearbyPages(anchorPage: Int) {
        val screenW = resources.displayMetrics.widthPixels - dp(16)
        lifecycleScope.launch(Dispatchers.IO) {
            val start = (anchorPage - 2).coerceAtLeast(0); val end = (anchorPage + 2).coerceAtMost(totalPages - 1)
            for (idx in start..end) { if (pageBitmapCache.get(idx) == null) renderPageBitmap(idx, screenW) }
        }
    }

    private fun addPageView(bmp: Bitmap, pageIndex: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply { setColor(cardBg()); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.parseColor("#2A2A2A")) }
        }
        val zoomView = ZoomableImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            setImageBitmap(bmp); tag = pageIndex
        }
        val annotOverlay = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            setBackgroundColor(Color.TRANSPARENT)
        }
        annotOverlays[pageIndex] = annotOverlay
        frame.addView(zoomView); frame.addView(annotOverlay)
        pageContainer.addView(frame)
    }

    private fun updatePageFromScroll(scrollY: Int) {
        if (pageContainer.childCount == 0) return
        var accumulated = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < accumulated + child.height / 2) { currentPage = i; break }
            accumulated += child.height
        }
        updatePageCounterFromScroll()
    }

    private fun updatePageCounterFromScroll() {
        val bmIndicator = if (bookmarkedPages.contains(currentPage)) " \u2605" else ""
        pageCounter.text = "Page ${currentPage + 1} of $totalPages$bmIndicator"
    }

    private fun scrollToPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageContainer.childCount) return
        val child = pageContainer.getChildAt(pageIndex)
        val scroll = (child.parent as? ScrollView) ?: return
        scroll.smoothScrollTo(0, child.top)
        currentPage = pageIndex; preloadNearbyPages(pageIndex); updatePageCounterFromScroll()
    }

    // -------------------------------------------------------
    // ZOOM
    // -------------------------------------------------------

    private fun applyZoom(factor: Float) {
        currentZoom = (currentZoom * factor).coerceIn(0.5f, 5.0f)
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val zoomView = frame.getChildAt(0) as? ZoomableImageView ?: continue
            zoomView.setZoom(currentZoom)
        }
        toast("Zoom: ${(currentZoom * 100).toInt()}%")
    }

    // -------------------------------------------------------
    // SEARCH
    // -------------------------------------------------------

    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        val searchBar = (pageContainer.parent as? ScrollView)?.parent as? LinearLayout
        searchBar?.getChildAt(1)?.visibility = if (isSearchMode) View.VISIBLE else View.GONE
        if (!isSearchMode) { searchBox.setText(""); searchResults = emptyList(); searchResultIdx = 0 }
    }

    private fun hideSearchBar() {
        isSearchMode = false
        val searchBar = (pageContainer.parent as? ScrollView)?.parent as? LinearLayout
        searchBar?.getChildAt(1)?.visibility = View.GONE
        searchBox.setText(""); searchResults = emptyList(); searchResultIdx = 0
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun runSearch() {
        val query = searchBox.text.toString().trim()
        if (query.isEmpty()) { searchCountLabel.text = "Enter query"; return }
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val matches = mutableListOf<Int>()
                for (i in 0 until totalPages) {
                    val text = getPageText(i)
                    if (text.contains(query, ignoreCase = true)) matches.add(i)
                }
                matches
            }
            searchResults = results; searchResultIdx = 0
            updateSearchCounter()
            if (results.isNotEmpty()) scrollToPage(results[0])
            else toast("No matches found")
        }
    }

    private fun updateSearchCounter() {
        searchCountLabel.text = if (searchResults.isNotEmpty()) "${searchResultIdx + 1} / ${searchResults.size}"
        else "No matches"
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) { searchResultLabel.text = ""; searchResults = emptyList(); return }
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val matches = mutableListOf<Int>()
                for (i in 0 until totalPages) {
                    val text = getPageText(i)
                    if (text.contains(query, ignoreCase = true)) matches.add(i)
                }
                matches
            }
            searchResults = results; searchResultIdx = 0
            searchResultLabel.text = "${results.size} found"
            if (results.isNotEmpty()) scrollToPage(results[0])
        }
    }

    private fun getPageText(pageIndex: Int): String {
        pageTextCache[pageIndex]?.let { return it }
        return try {
            val file = pdfFile ?: return ""
            val doc = PDDocument.load(file)
            val stripper = PDFTextStripper()
            stripper.startPage = pageIndex + 1; stripper.endPage = pageIndex + 1
            val text = stripper.getText(doc); doc.close()
            pageTextCache[pageIndex] = text; text
        } catch (_: Exception) { "" }
    }

    // -------------------------------------------------------
    // THEME
    // -------------------------------------------------------

    private fun toggleTheme() {
        isDark = !isDark; prefs.edit().putBoolean("dark_mode", isDark).apply(); recreate()
    }

    private fun applyViewerTheme() {
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val zoomView = frame.getChildAt(0) as? ZoomableImageView ?: continue
            val bmp = zoomView.getRenderedBitmap() ?: continue
            val themed = if (isDark) applyNightMode(bmp) else bmp
            zoomView.setImageBitmap(themed)
        }
    }

    private fun applyNightMode(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { set(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f
        )) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint); return out
    }

    // -------------------------------------------------------
    // ANNOTATION MENU
    // -------------------------------------------------------

    private fun showAnnotationMenu() {
        val tools = arrayOf(
            "Freehand", "Highlighter", "Rectangle", "Circle", "Arrow",
            "Underline", "Strikeout", "Text", "Stamp", "Move Text", "Move Shape", "Eraser", "Clear All"
        )
        val colors = intArrayOf(
            Color.parseColor("#E53935"), Color.parseColor("#448AFF"), Color.parseColor("#43A047"),
            Color.parseColor("#FB8C00"), Color.parseColor("#8E24AA"), Color.parseColor("#FFD60A")
        )
        AlertDialog.Builder(this).setTitle("Annotation Tool").setItems(tools) { _, which ->
            if (which == 12) { annotOverlays[currentPage]?.clearAll(); toast("Annotations cleared"); return@setItems }
            val tool = when (which) {
                0 -> AnnotationCanvasView.TOOL_FREEHAND
                1 -> AnnotationCanvasView.TOOL_HIGHLIGHT
                2 -> AnnotationCanvasView.TOOL_RECT
                3 -> AnnotationCanvasView.TOOL_CIRCLE
                4 -> AnnotationCanvasView.TOOL_ARROW
                5 -> AnnotationCanvasView.TOOL_UNDERLINE
                6 -> AnnotationCanvasView.TOOL_STRIKEOUT
                7 -> AnnotationCanvasView.TOOL_TEXT
                8 -> AnnotationCanvasView.TOOL_STAMP
                9 -> AnnotationCanvasView.TOOL_MOVE_TEXT
                10 -> AnnotationCanvasView.TOOL_MOVE_SHAPE
                11 -> AnnotationCanvasView.TOOL_ERASER
                else -> AnnotationCanvasView.TOOL_NONE
            }
            if (tool == AnnotationCanvasView.TOOL_TEXT) {
                val et = EditText(this).apply { hint = "Enter text" }
                AlertDialog.Builder(this).setTitle("Text Annotation").setView(et).setPositiveButton("Place") { _, _ ->
                    annotOverlays[currentPage]?.setPendingText(et.text.toString())
                    annotOverlays[currentPage]?.setTool(tool, colors[1])
                }.setNegativeButton("Cancel", null).show()
            } else if (tool == AnnotationCanvasView.TOOL_STAMP) {
                val et = EditText(this).apply { hint = "Stamp text (e.g. CONFIDENTIAL)" }
                AlertDialog.Builder(this).setTitle("Stamp").setView(et).setPositiveButton("Place") { _, _ ->
                    annotOverlays[currentPage]?.setPendingStamp(et.text.toString())
                    annotOverlays[currentPage]?.setTool(tool, colors[0])
                }.setNegativeButton("Cancel", null).show()
            } else {
                annotOverlays[currentPage]?.setTool(tool, colors[which % colors.size])
            }
        }.show()
    }

    // -------------------------------------------------------
    // OCR MENU
    // -------------------------------------------------------

    private fun showOcrMenu() {
        val items = arrayOf("Extract Text (this page)", "Extract Text (all pages)", "Copy to Clipboard", "ML Kit OCR")
        AlertDialog.Builder(this).setTitle("OCR Options").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val text = getPageText(currentPage)
                    showTextDialog("Page ${currentPage + 1}", text.ifEmpty { "No text found on this page." })
                }
                1 -> {
                    lifecycleScope.launch {
                        val allText = withContext(Dispatchers.IO) {
                            val sb = StringBuilder()
                            for (i in 0 until totalPages) { sb.appendLine("--- Page ${i + 1} ---"); sb.appendLine(getPageText(i)) }
                            sb.toString()
                        }
                        showTextDialog("All Pages", allText)
                    }
                }
                2 -> {
                    val text = getPageText(currentPage)
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("PDF", text))
                    toast("Page ${currentPage + 1} text copied")
                }
                3 -> runMlKitOcrOnPage()
            }
        }.show()
    }

    private fun runMlKitOcrOnPage() {
        val frame = pageContainer.getChildAt(currentPage) as? FrameLayout ?: return
        val zoom = frame.getChildAt(0) as? ZoomableImageView ?: return
        zoom.getRenderedBitmap() ?: return
        toast("OCR running on page ${currentPage + 1}...")
        val info = "ML Kit OCR on page ${currentPage + 1}.\n\n" +
            "Full impl:\n" +
            " val image = InputImage.fromBitmap(bmp, 0)\n" +
            " TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)\n" +
            " .process(image).addOnSuccessListener { showTextDialog(it.text) }"
        showTextDialog("ML Kit OCR", info)
    }

    private fun showTextDialog(title: String, text: String) {
        val et = EditText(this).apply {
            setText(text); textSize = 13f; setTextColor(Color.parseColor("#E5E2E1"))
            setBackgroundColor(Color.parseColor("#1A1A1A")); setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("PDF", text)); toast("Copied")
            }.setNegativeButton("Close", null).show()
    }

    private fun showPdfOpsMenu() {
        val bm = if (bookmarkedPages.contains(currentPage)) "Remove Bookmark" else "Bookmark This Page"
        AlertDialog.Builder(this).setTitle("PDF Operations").setItems(arrayOf(
            bm, "All Bookmarks", "Go to Page", "Add Watermark", "Rotate Page", "Delete Page",
            "Compress PDF", "Page to Image", "Image to PDF", "Merge / Split", "Share PDF", "Open Tools"
        )) { _, which ->
            when (which) {
                0 -> toggleBookmark(); 1 -> showBookmarksDialog(); 2 -> showGoToPageDialog()
                3 -> toast("Watermark: via PdfOperationsManager.addWatermark()")
                4 -> showRotatePageDialog(); 5 -> toast("Delete page: via PdfOperationsManager.deletePage()")
                6 -> showCompressPdfDialog(); 7 -> showPageToImageDialog(); 8 -> showImageToPdfDialog()
                9 -> startActivity(Intent(this, ToolsActivity::class.java))
                10 -> sharePdf(); 11 -> startActivity(Intent(this, ToolsActivity::class.java))
            }
        }.show()
    }

    private fun toggleBookmark() {
        val bPrefs = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
        val key = pdfUri?.toString()?.hashCode().toString()
        val existing = bPrefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (bookmarkedPages.contains(currentPage)) {
            bookmarkedPages.remove(currentPage)
            existing.removeAll { it.startsWith("$currentPage:") || it == currentPage.toString() }
            toast("Bookmark removed")
        } else {
            bookmarkedPages.add(currentPage)
            val fname = pdfUri?.let { FileHelper.getFileName(this, it) } ?: "document.pdf"
            existing.add("$currentPage:$fname"); toast("Page ${currentPage + 1} bookmarked")
        }
        bPrefs.edit().putStringSet(key, existing).apply(); updatePageCounterFromScroll()
    }

    private fun showBookmarksDialog() {
        if (bookmarkedPages.isEmpty()) { toast("No bookmarks -- use More > Bookmark This Page"); return }
        val bPrefs = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
        val key = pdfUri?.toString()?.hashCode().toString()
        val raw = bPrefs.getStringSet(key, emptySet()) ?: emptySet()
        data class BM(val page: Int, val label: String)
        val entries = raw.mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            val pg = parts[0].toIntOrNull() ?: return@mapNotNull null
            BM(pg, if (parts.size > 1) parts[1] else "Page ${pg + 1}")
        }.sortedBy { it.page }
        if (entries.isEmpty()) { toast("No bookmarks yet"); return }
        AlertDialog.Builder(this).setTitle("Bookmarks (${entries.size})")
            .setItems(entries.map { "Page ${it.page + 1} -- ${it.label}" }.toTypedArray()) { _, i -> scrollToPage(entries[i].page) }
            .setNeutralButton("Add Label") { _, _ ->
                val et = EditText(this).apply { hint = "Label for page ${currentPage + 1}" }
                AlertDialog.Builder(this).setTitle("Bookmark Label").setView(et).setPositiveButton("Save") { _, _ ->
                    val lbl = et.text.toString().trim(); if (!bookmarkedPages.contains(currentPage)) toggleBookmark()
                    val k2 = pdfUri?.toString()?.hashCode().toString()
                    val set2 = bPrefs.getStringSet(k2, emptySet()).orEmpty().toMutableSet()
                    set2.removeAll { it.startsWith("$currentPage:") }; set2.add("$currentPage:$lbl")
                    bPrefs.edit().putStringSet(k2, set2).apply(); toast("Labelled: $lbl")
                }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun showRotatePageDialog() {
        AlertDialog.Builder(this).setTitle("Rotate Page ${currentPage + 1}").setItems(arrayOf(
            "90 degrees clockwise", "90 degrees counter-clockwise", "180 degrees", "Custom angle..."
        )) { _, which ->
            when (which) {
                0 -> applyPageRotation(90f); 1 -> applyPageRotation(-90f); 2 -> applyPageRotation(180f)
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
        pageContainer.removeViewAt(currentPage); addPageView(rotated, currentPage)
        toast("Page rotated ${degrees.toInt()} degrees")
    }

    private fun showCompressPdfDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        val origSize = pdfFile?.length() ?: 0L
        container.addView(TextView(this).apply { text = "Current size: ${formatSize(origSize)}"; textSize = 13f; setTextColor(Color.WHITE) })
        val estimateLabel = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#ADC6FF")) }
        val pctLabel = TextView(this).apply { textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        container.addView(estimateLabel); container.addView(pctLabel)
        val slider = android.widget.SeekBar(this).apply {
            max = 90; progress = 40
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        fun updateEst(p: Int) {
            val t = p + 10; val est = (origSize * (100 - t) / 100L).coerceAtLeast(1024L)
            pctLabel.text = "Reduce by ~$t%"; estimateLabel.text = "Estimated: ${formatSize(est)}"
        }
        updateEst(slider.progress); slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, f: Boolean) { updateEst(p) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        container.addView(slider)
        AlertDialog.Builder(this).setTitle("Compress PDF").setView(container)
            .setPositiveButton("Compress") { _, _ ->
                toast("Compress: impl via PdfOperationsManager.compressPdf(pdfFile, quality=${slider.progress + 10})")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showPageToImageDialog() {
        AlertDialog.Builder(this).setTitle("Export Page ${currentPage + 1} as Image").setItems(arrayOf(
            "PNG (lossless)", "JPEG High (95%)", "JPEG Medium (80%)", "JPEG Compressed (60%)"
        )) { _, which ->
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { renderPageBitmap(currentPage, resources.displayMetrics.widthPixels - dp(16)) } ?: return@launch
                val format = when (which) { 0 -> Bitmap.CompressFormat.PNG else -> Bitmap.CompressFormat.JPEG }
                val quality = when (which) { 0 -> 100; 1 -> 95; 2 -> 80; else -> 60 }
                val ext = if (which == 0) "png" else "jpg"
                val fileName = "Page_${currentPage + 1}_${System.currentTimeMillis()}.$ext"
                val uri = withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (which == 0) "image/png" else "image/jpeg")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                        }
                        val outUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        outUri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(format, quality, out) } }
                        outUri
                    } else {
                        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                        val outFile = File(dir, fileName)
                        FileOutputStream(outFile).use { bmp.compress(format, quality, it) }
                        Uri.fromFile(outFile)
                    }
                }
                if (uri != null) toast("Saved: $fileName") else toast("Save failed")
            }
        }.show()
    }

    private fun showImageToPdfDialog() {
        val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) {
                    val doc = android.graphics.pdf.PdfDocument()
                    try {
                        uris.forEachIndexed { i, uri ->
                            val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@withContext null
                            val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                            val page = doc.startPage(pi); page.canvas.drawBitmap(bmp, 0f, 0f, null); doc.finishPage(page); bmp.recycle()
                        }
                        val fileName = "Images_${System.currentTimeMillis()}.pdf"
                        var outUri: Uri? = null
                        val out: java.io.OutputStream? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                            }
                            outUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            outUri?.let { contentResolver.openOutputStream(it) }
                        } else {
                            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                            val outFile = File(dir, fileName)
                            outUri = Uri.fromFile(outFile)
                            java.io.FileOutputStream(outFile)
                        }
                        if (out == null) return@withContext null
                        out.use { doc.writeTo(it) }
                        outUri
                    } finally { doc.close() }
                }
                if (uri != null) { toast("PDF created from ${uris.size} images"); openPdf(uri) } else toast("Failed")
            }
        }
        picker.launch("image/*")
    }

    private fun showGoToPageDialog() {
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Page 1-$totalPages"; setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et).setPositiveButton("Go") { _, _ ->
            val pg = et.text.toString().toIntOrNull()?.let { it - 1 }?.coerceIn(0, totalPages - 1)
            if (pg != null) scrollToPage(pg) else toast("Invalid page number")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun sharePdf() {
        val uri = pdfUri ?: return
        val share = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri) }
        startActivity(Intent.createChooser(share, "Share PDF"))
    }

    private fun openPdf(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java).apply { putExtra(EXTRA_URI, uri.toString()) })
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun applySystemBarColors() {
        window.statusBarColor = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
        window.navigationBarColor = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: ZoomableImageView
    // -------------------------------------------------------

    inner class ZoomableImageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
        private var scaleDetector: android.view.ScaleGestureDetector? = null
        private var scaleFactor = 1.0f
        private var lastTouchX = 0f; private var lastTouchY = 0f
        private var posX = 0f; private var posY = 0f
        private var activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
        private var renderedBmp: Bitmap? = null

        init {
            scaleType = ScaleType.MATRIX
            scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                    applyMatrix(); return true
                }
            })
        }

        fun setZoom(zoom: Float) { scaleFactor = zoom.coerceIn(0.5f, 5.0f); applyMatrix() }
        fun getRenderedBitmap(): Bitmap? = renderedBmp

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm); renderedBmp = bm; applyMatrix()
        }

        private fun applyMatrix() {
            val matrix = Matrix()
            val drawable = drawable ?: return
            val dW = drawable.intrinsicWidth.toFloat(); val dH = drawable.intrinsicHeight.toFloat()
            val vW = width.toFloat(); val vH = height.toFloat()
            val fitScale = minOf(vW / dW, vH / dH)
            val finalScale = fitScale * scaleFactor
            matrix.setScale(finalScale, finalScale)
            val dx = (vW - dW * finalScale) / 2f + posX
            val dy = (vH - dH * finalScale) / 2f + posY
            matrix.postTranslate(dx, dy)
            imageMatrix = matrix
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaleDetector?.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.x; lastTouchY = ev.y
                    activePointerId = ev.getPointerId(0)
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = ev.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val dx = ev.getX(pointerIndex) - lastTouchX
                        val dy = ev.getY(pointerIndex) - lastTouchY
                        posX += dx; posY += dy
                        lastTouchX = ev.getX(pointerIndex); lastTouchY = ev.getY(pointerIndex)
                        applyMatrix()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = (ev.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                    val pointerId = ev.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        lastTouchX = ev.getX(newPointerIndex); lastTouchY = ev.getY(newPointerIndex)
                        activePointerId = ev.getPointerId(newPointerIndex)
                    }
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh); applyMatrix()
        }
    }
}
