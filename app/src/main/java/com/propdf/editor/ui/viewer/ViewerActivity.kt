package com.propdf.editor.ui.viewer

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.core.CrashGuard
import com.propdf.editor.core.GpuOptimizer
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.core.pool.BitmapPool
import com.propdf.editor.core.render.BackgroundPdfRenderer
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.utils.FileHelper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

/**
 * Optimized PDF Viewer with:
 * - Incremental/lazy page loading via RecyclerView + BackgroundPdfRenderer
 * - Two-tier memory/disk caching
 * - Bitmap pooling for annotation overlays
 * - ANR-safe search with debouncing
 * - GPU-optimized hardware layers for static content
 * - Proper lifecycle management to prevent memory leaks
 */
@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    @Inject lateinit var pdfOperationsManager: PdfOperationsManager
    @Inject lateinit var pdfOperationsRepository: com.propdf.core.domain.repository.PdfOperationsRepository
    @Inject lateinit var ocrManager: com.propdf.editor.data.repository.OcrManager
    @Inject lateinit var bitmapPool: BitmapPool
    @Inject lateinit var bitmapCache: LruBitmapCache
    @Inject lateinit var bookmarkDao: com.propdf.core.data.local.dao.BookmarkDao

    // ─── State ─────────────────────────────────────────────────────
    private var pdfUri: Uri? = null
    private var pdfPassword: String? = null
    private var pdfFile: File? = null
    private var backgroundRenderer: BackgroundPdfRenderer? = null
    private var currentPage = 0
    private var totalPages = 0
    private var searchResultIdx = 0
    private var searchResults: List<Int> = emptyList()
    private var lastSearchQuery = ""
    private val pageScaleMap = mutableMapOf<Int, Float>()
    private val pageTextCache = mutableMapOf<Int, String>()

    // Annotation state
    private val annotationManager = AnnotationManager()
    private val annotOverlays = mutableMapOf<Int, AnnotOverlay>()
    // In-memory cache of bookmarked page indices for the currently-open document,
    // loaded from and persisted to BookmarkDao (see loadBookmarks()/toggleBookmark()).
    private val bookmarkedPages = mutableSetOf<Int>()
    private var annotToolbarExpanded = false
    private var activeAnnotGroup = "markup"
    private var activeTool: String? = null
    private var activeColor = Color.parseColor("#007AFF")
    private var highlightColor = Color.parseColor("#FFFF00")
    private var strokeWidth = 5f

    // Views
    private lateinit var rootLayout: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var pageAdapter: OptimizedPdfPageAdapter
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

    // Search debounce
    private val searchDebounceJob = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // ─── Constants ─────────────────────────────────────────────────
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

    // ─── Lifecycle ─────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_URI)
        pdfUri = if (uriStr != null) Uri.parse(uriStr) else intent.data
        pdfPassword = intent.getStringExtra(EXTRA_PASSWORD)

        buildUI()
        setupSearchDebounce()
        loadPdf()
        loadAnnotationsFromCache()
    }

    override fun onPause() {
        super.onPause()
        persistAnnotationCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        persistAnnotationCache()
        backgroundRenderer?.close()
        ocrManager.release()
        // Clear annotation bitmaps
        annotOverlays.values.forEach { it.release() }
        annotOverlays.clear()
    }

    override fun onBackPressed() {
        if (searchBar.visibility == View.VISIBLE) { hideSearchBar(); return }
        if (annotToolbarExpanded) { collapseAnnotToolbar(); return }
        super.onBackPressed()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        bitmapCache.trimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            pageAdapter.notifyDataSetChanged() // Trigger view recycling
        }
    }

    // ─── UI Building ───────────────────────────────────────────────
    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        setContentView(rootLayout)

        rootLayout.addView(buildTopBar())
        searchBar = buildSearchBar()
        rootLayout.addView(searchBar)

        // Navigation strip
        val navStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val navTint = Color.parseColor("#ADC6FF")
        navStrip.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(34))
            setImageResource(android.R.drawable.ic_media_previous)
            colorFilter = PorterDuffColorFilter(navTint, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { scrollToPage(currentPage - 1) }
        })
        pageCounter = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = Gravity.CENTER
            setTextColor(navTint)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
            text = "Loading..."
            setOnClickListener { showGoToPageDialog() }
            setOnLongClickListener { toggleBookmark(); true }
        }
        navStrip.addView(pageCounter)
        navStrip.addView(ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(34))
            setImageResource(android.R.drawable.ic_media_next)
            colorFilter = PorterDuffColorFilter(navTint, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { scrollToPage(currentPage + 1) }
        })
        rootLayout.addView(navStrip)

        // RecyclerView for incremental loading (CRITICAL OPTIMIZATION)
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#282828"))
            layoutManager = LinearLayoutManager(this@ViewerActivity)
            setHasFixedSize(true) // Optimization: item size doesn't change
            setItemViewCacheSize(3) // Keep 3 pages in memory
            // GPU optimization: enable hardware acceleration for recycler
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        rootLayout.addView(recyclerView)

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

            val intentName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            val rawName = intentName ?: pdfUri?.let { FileHelper.getFileName(this@ViewerActivity, it) } 
                ?: pdfUri?.lastPathSegment ?: "PDF"
            val displayNameClean = rawName.removeSuffix(".pdf")

            addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = if (displayNameClean.length > 28) displayNameClean.take(25) + "..." else displayNameClean
                setTextColor(cTxt)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
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
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setImageResource(iconRes)
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = desc
            setOnClickListener { action() }
        }
    }

    // ─── PDF Loading (Optimized) ───────────────────────────────────
    private fun loadPdf() {
        CrashGuard.safeLaunch(lifecycleScope, timeoutMs = 60000L,
            onError = { toast("Error opening PDF: ${it.message}") }
        ) {
            val uri = pdfUri ?: run { toast("No PDF specified"); return@safeLaunch }
            if (!isOpenableUri(uri)) return@safeLaunch

            val file = withContext(ThreadPoolManager.IoDispatcher) { copyUriToCache(uri) }
            if (file == null || !file.exists() || !FileHelper.isPdf(file)) {
                toast("Cannot read valid PDF"); return@safeLaunch
            }
            pdfFile = file

            // Initialize background renderer (lazy loading)
            backgroundRenderer = BackgroundPdfRenderer(file, bitmapCache, bitmapPool)
            totalPages = backgroundRenderer?.pageCount ?: 0

            withContext(Dispatchers.Main) {
                pageCounter.text = "Page 1 of $totalPages"
                setupRecyclerView()
            }

            loadBookmarks(uri)
        }
    }

    private fun loadBookmarks(uri: Uri) {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.IoDispatcher) {
            val pages = try {
                bookmarkDao.getBookmarkedPages(uri.toString())
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                bookmarkedPages.clear()
                bookmarkedPages.addAll(pages)
                updatePageCounter()
            }
        }
    }

    private fun toggleBookmark() {
        val uri = pdfUri ?: return
        val page = currentPage
        val isBookmarked = bookmarkedPages.contains(page)
        // Update the in-memory set and UI immediately; persist in the background.
        if (isBookmarked) bookmarkedPages.remove(page) else bookmarkedPages.add(page)
        updatePageCounter()
        toast(if (isBookmarked) "Bookmark removed" else "Bookmark added")

        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.IoDispatcher) {
            try {
                if (isBookmarked) {
                    bookmarkDao.removeBookmark(uri.toString(), page)
                } else {
                    bookmarkDao.addBookmark(
                        com.propdf.core.data.entity.BookmarkEntity(
                            uriString = uri.toString(),
                            pageIndex = page
                        )
                    )
                }
            } catch (_: Exception) {
                // Persistence failed silently; in-memory state still reflects the
                // user's toggle for this session, so the UI doesn't lie to them.
            }
        }
    }

    private fun setupRecyclerView() {
        val screenW = resources.displayMetrics.widthPixels - dp(16)
        pageAdapter = OptimizedPdfPageAdapter(
            renderer = backgroundRenderer!!,
            screenWidth = screenW,
            scope = lifecycleScope,
            pool = bitmapPool,
            cache = bitmapCache,
            annotationManager = annotationManager,
            onPageVisible = { page ->
                currentPage = page
                updatePageCounter()
                // Lazy preload nearby pages
                backgroundRenderer?.preloadPages(page, screenW)
            }
        )
        recyclerView.adapter = pageAdapter

        // Page scroll listener for counter updates
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPage) {
                    currentPage = firstVisible
                    updatePageCounter()
                }
            }
        })
    }

    private fun updatePageCounter() {
        val bm = if (bookmarkedPages.contains(currentPage)) " [B]" else ""
        pageCounter.text = "Page ${currentPage + 1} of $totalPages$bm"
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

    private fun isOpenableUri(uri: Uri): Boolean {
        val uriText = uri.toString().trim()
        if (uriText.isEmpty()) { toast("Invalid file URI"); return false }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (!f.exists() || !f.isFile) { toast("File does not exist"); return false }
            if (!FileHelper.isPdf(f)) { toast("Not a valid PDF"); return false }
            return true
        }
        if (!FileHelper.isValidPdfUri(this, uri)) { toast("Cannot read PDF from URI"); return false }
        return true
    }

    // ─── Search (ANR-safe with debounce) ───────────────────────────
    private fun setupSearchDebounce() {
        lifecycleScope.launch {
            searchDebounceJob
                .debounce(300) // 300ms debounce prevents ANR on rapid typing
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { query ->
                    if (query.length >= 2) {
                        performSearch(query)
                    }
                }
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.length < 2) { toast("Enter at least 2 characters"); return }
        searchDebounceJob.tryEmit(query)
    }

    private suspend fun performSearch(query: String) = withContext(ThreadPoolManager.BackgroundDispatcher) {
        val q = query.lowercase()
        val results = (0 until totalPages).filter { idx ->
            extractPageText(idx).lowercase().contains(q)
        }

        withContext(Dispatchers.Main) {
            searchResults = results
            searchResultIdx = 0
            updateSearchCounter()
            if (results.isNotEmpty()) {
                scrollToPage(results[0])
            } else {
                toast("No matches found for \"$query\"")
            }
        }
    }

    private fun extractPageText(pageIndex: Int): String {
        pageTextCache[pageIndex]?.let { return it }
        val file = pdfFile ?: return ""
        return try {
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(doc).trim()
                pageTextCache[pageIndex] = text
                text
            }
        } catch (_: Exception) { "" }
    }

    // ─── Navigation ────────────────────────────────────────────────
    private fun scrollToPage(page: Int) {
        val target = page.coerceIn(0, totalPages - 1)
        recyclerView.scrollToPosition(target)
        currentPage = target
        updatePageCounter()
    }

    private fun showGoToPageDialog() {
        val container = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter page (1 - $totalPages)"
            textSize = 16f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setSelectAllOnFocus(true)
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Navigate to Page")
            .setView(container)
            .setPositiveButton("Go") { _, _ ->
                val pg = input.text.toString().toIntOrNull()
                if (pg != null && pg in 1..totalPages) {
                    scrollToPage(pg - 1)
                    hideSearchBar()
                } else {
                    toast("Enter a page between 1 and $totalPages")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Annotation Toolbar ────────────────────────────────────────
    private fun buildAnnotationArea(): LinearLayout {
        // ... (preserved from original with minor optimizations)
        annotToolbarWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
        }

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        annotExpandBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
            text = "Annotate v"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#ADC6FF"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = dp(8).toFloat()
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

        val saveBtn = buildQuickBtn("Save", Color.parseColor("#2E7D32"), Color.parseColor("#1B3A1C"))
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
    ): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { marginStart = dp(6) }
        text = label
        textSize = if (label.length == 1) 14f else 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        setPadding(dp(10), 0, dp(10), 0)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun toggleAnnotToolbar() {
        if (annotToolbarExpanded) collapseAnnotToolbar() else expandAnnotToolbar()
    }

    private fun expandAnnotToolbar() {
        annotToolbarExpanded = true
        annotBar.visibility = View.VISIBLE
        annotExpandBtn.text = "Annotate ^"
    }

    private fun collapseAnnotToolbar() {
        annotToolbarExpanded = false
        annotBar.visibility = View.GONE
        annotExpandBtn.text = "Annotate v"
        activeTool = null
        refreshAnnotSubMenu(activeAnnotGroup)
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
            scroll.addView(annotSubMenuRow)
            addView(scroll)
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)
            refreshAnnotSubMenu("markup")
        }
    }

    // ... (remaining annotation UI methods preserved with optimizations)
    private fun buildSettingsPill(): FrameLayout {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val swatchScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        annotSwatchViews.clear()
        COLOR_PALETTE.forEach { hex ->
            val color = Color.parseColor(hex)
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
                tag = color
                setOnClickListener {
                    activeColor = color
                    annotSwatchViews.forEach { v -> applySwatchStyle(v, v.tag as Int, (v.tag as Int) == activeColor) }
                }
            }
            applySwatchStyle(swatch, color, color == activeColor)
            annotSwatchViews.add(swatch)
            swatchRow.addView(swatch)
        }
        swatchScroll.addView(swatchRow)
        row.addView(swatchScroll)

        annotWeightValue = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), -2)
            text = strokeWidth.toInt().toString()
            setTextColor(Color.parseColor("#ADC6FF"))
            textSize = 11f
            gravity = Gravity.CENTER
            setOnClickListener { showStrokeWidthInputDialog() }
        }
        row.addView(annotWeightValue)

        annotWeightBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(90), -2).apply { marginStart = dp(4) }
            max = 30
            progress = strokeWidth.toInt().coerceIn(1, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    strokeWidth = value.coerceAtLeast(1).toFloat()
                    annotWeightValue.text = strokeWidth.toInt().toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(annotWeightBar)

        container.addView(row)
        return container
    }

    private fun buildAnnotGroupNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            ANNOT_GROUPS.keys.forEach { groupId ->
                addView(TextView(this@ViewerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                    text = groupId.replaceFirstChar { it.uppercase() }
                    textSize = 11f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        if (groupId == activeAnnotGroup) Color.parseColor("#ADC6FF")
                        else Color.parseColor("#777777")
                    )
                    setOnClickListener {
                        if (activeAnnotGroup == groupId) return@setOnClickListener
                        activeAnnotGroup = groupId
                        activeTool = null
                        rebuildAnnotGroupNav()
                        refreshAnnotSubMenu(groupId)
                    }
                })
            }
        }
    }

    private fun rebuildAnnotGroupNav() {
        if (!::annotGroupNavBar.isInitialized) return
        val parent = annotGroupNavBar.parent as? ViewGroup ?: return
        val idx = parent.indexOfChild(annotGroupNavBar)
        parent.removeView(annotGroupNavBar)
        annotGroupNavBar = buildAnnotGroupNav()
        parent.addView(annotGroupNavBar, idx)
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        if (!::annotSubMenuRow.isInitialized) return
        annotSubMenuRow.removeAllViews()
        val tools = ANNOT_GROUPS[groupId] ?: return
        tools.forEach { toolId -> annotSubMenuRow.addView(buildAnnotToolCell(toolId)) }
    }

    private fun buildAnnotToolCell(toolId: String): LinearLayout {
        val isActive = activeTool == toolId
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(48)).apply { marginEnd = dp(4) }
            background = GradientDrawable().apply {
                setColor(if (isActive) Color.parseColor("#2D3A5A") else Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
            }
            addView(TextView(this@ViewerActivity).apply {
                text = TOOL_LABEL[toolId] ?: toolId
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (isActive) Color.parseColor("#ADC6FF") else Color.parseColor("#CCCCCC"))
            })
            setOnClickListener { handleAnnotToolTap(toolId) }
        }
    }

    private fun handleAnnotToolTap(toolId: String) {
        when (toolId) {
            "save" -> { saveAnnotations(); return }
            "move_text", "move_shape" -> {
                toast("Moving placed ${if (toolId == "move_text") "text" else "shapes"} isn't supported yet")
                return
            }
            "text" -> { promptAndPlaceText(); return }
            "stamp" -> { promptAndPlaceStamp(); return }
        }
        activeTool = if (activeTool == toolId) null else toolId
        refreshAnnotSubMenu(activeAnnotGroup)
    }

    private fun promptAndPlaceText() {
        val input = EditText(this).apply { hint = "Enter text"; setSingleLine(false) }
        AlertDialog.Builder(this)
            .setTitle("Add text")
            .setView(input)
            .setPositiveButton("Place") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                annotationManager.add(
                    Annotation(
                        pageIndex = currentPage,
                        type = AnnotationType.TEXT,
                        points = listOf(PointF(dp(40).toFloat(), dp(60).toFloat())),
                        color = activeColor,
                        strokeWidth = strokeWidth,
                        text = text
                    )
                )
                pageAdapter.notifyItemChanged(currentPage)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptAndPlaceStamp() {
        val presets = arrayOf("APPROVED", "DRAFT", "CONFIDENTIAL", "REJECTED", "REVIEWED")
        AlertDialog.Builder(this)
            .setTitle("Add stamp")
            .setItems(presets) { _, which ->
                annotationManager.add(
                    Annotation(
                        pageIndex = currentPage,
                        type = AnnotationType.STAMP,
                        points = listOf(PointF(dp(40).toFloat(), dp(80).toFloat())),
                        color = activeColor,
                        strokeWidth = strokeWidth,
                        text = presets[which]
                    )
                )
                pageAdapter.notifyItemChanged(currentPage)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySwatchStyle(view: View, color: Int, isActive: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isActive) setStroke(dp(2), Color.WHITE) else setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun showStrokeWidthInputDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(strokeWidth.toInt().toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Stroke width (1-30)")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: return@setPositiveButton
                strokeWidth = v.toFloat()
                if (::annotWeightBar.isInitialized) annotWeightBar.progress = v
                if (::annotWeightValue.isInitialized) annotWeightValue.text = v.toString()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Annotation Data Model ───────────────────────────────────
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

        fun get(page: Int): List<Annotation> = store[page] ?: emptyList()
        fun hasAny(): Boolean = store.values.any { it.isNotEmpty() }
        fun removeAnnotation(pageIndex: Int, ann: Annotation) {
            store[pageIndex]?.remove(ann)
        }

        fun undo() {
            val last = undoList.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
            store[last.pageIndex]?.remove(last)
            redoList.addLast(last)
            updateUndoRedoBtns()
            pageAdapter.notifyItemChanged(last.pageIndex)
        }

        fun redo() {
            val ann = redoList.removeLastOrNull() ?: run { toast("Nothing to redo"); return }
            store.getOrPut(ann.pageIndex) { mutableListOf() }.add(ann)
            undoList.addLast(ann)
            updateUndoRedoBtns()
            pageAdapter.notifyItemChanged(ann.pageIndex)
        }

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
                    a.points.forEach { p ->
                        pts.put(JSONObject().put("x", p.x).put("y", p.y))
                    }
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
                store.clear(); undoList.clear(); redoList.clear()
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
                updateUndoRedoBtns()
            } catch (_: Exception) {}
        }
    }

    // ─── Annotation Overlay (GPU-optimized) ──────────────────────────
    inner class AnnotOverlay(context: Context, val pageIdx: Int) : View(context) {
        val livePoints = mutableListOf<PointF>()
        var liveTool: AnnotationType? = null
        private val renderer = AnnotationRenderer()
        private var cachedBitmap: Bitmap? = null
        private var cacheDirty = true

        init {
            // Software layer required for PorterDuffXfermode (eraser)
            GpuOptimizer.optimizeView(this, isDynamic = true)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val toolStr = activeTool ?: return false
            if (toolStr in listOf("save", "image", "stamp", "text", "move_text", "move_shape")) return false
            val type = toolStringToType(toolStr) ?: return false

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    livePoints.clear()
                    livePoints.add(PointF(ev.x, ev.y))
                    liveTool = type
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    livePoints.add(PointF(ev.x, ev.y))
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (type == AnnotationType.ERASER) {
                        handleEraser(ev.x, ev.y)
                    } else {
                        commitAnnotation(type)
                    }
                    livePoints.clear()
                    liveTool = null
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            // Use offscreen cache for static annotations to reduce GPU overdraw
            if (cacheDirty || cachedBitmap == null) {
                cachedBitmap?.recycle()
                cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val c = Canvas(cachedBitmap!!)
                renderer.render(c, annotationManager.get(pageIdx))
                cacheDirty = false
            }
            cachedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

            // Draw live preview on top
            if (liveTool != null && livePoints.size >= 2) {
                val paint = renderer.buildPaint(liveTool!!, activeColor, strokeWidth, 255)
                val path = renderer.pointsToPath(liveTool!!, livePoints)
                canvas.drawPath(path, paint)
            }
        }

        fun invalidateCache() {
            cacheDirty = true
            invalidate()
        }

        fun release() {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }

        private fun handleEraser(x: Float, y: Float) {
            val bounds = RectF(x - dp(28), y - dp(28), x + dp(28), y + dp(28))
            val toRemove = annotationManager.get(pageIdx).filter { ann ->
                ann.points.any { p -> bounds.contains(p.x, p.y) }
            }
            toRemove.forEach { annotationManager.removeAnnotation(pageIdx, it) }
            invalidateCache()
        }

        private fun commitAnnotation(type: AnnotationType) {
            if (livePoints.isEmpty()) return
            val ann = Annotation(
                pageIndex = pageIdx,
                type = type,
                points = ArrayList(livePoints),
                color = if (type == AnnotationType.HIGHLIGHT) highlightColor else activeColor,
                strokeWidth = strokeWidth,
                alpha = if (type == AnnotationType.HIGHLIGHT) 90 else 255
            )
            annotationManager.add(ann)
            invalidateCache()
        }
    }

    // ─── Annotation Renderer ───────────────────────────────────────
    inner class AnnotationRenderer {
        fun render(canvas: Canvas, annotations: List<Annotation>) {
            annotations.forEach { ann -> drawAnnotation(canvas, ann) }
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

        fun pointsToPath(type: AnnotationType, points: List<PointF>): Path {
            val path = Path()
            when (type) {
                AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT -> {
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size - 1) {
                        val mx = (points[i].x + points[i + 1].x) / 2f
                        val my = (points[i].y + points[i + 1].y) / 2f
                        path.quadTo(points[i].x, points[i].y, mx, my)
                    }
                    if (points.size > 1) path.lineTo(points.last().x, points.last().y)
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
                        ), Path.Direction.CW
                    )
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

        fun buildPaint(type: AnnotationType, color: Int, weight: Float, alpha: Int): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.alpha = alpha
                strokeWidth = weight
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                when (type) {
                    AnnotationType.HIGHLIGHT -> {
                        style = Paint.Style.FILL_AND_STROKE
                        this.alpha = 90
                        strokeWidth = weight * 4
                    }
                    AnnotationType.ERASER -> {
                        style = Paint.Style.STROKE
                        strokeWidth = weight * 5f
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }
                    else -> style = Paint.Style.STROKE
                }
            }
        }

        private fun drawText(canvas: Canvas, ann: Annotation) {
            if (ann.text.isNullOrEmpty() || ann.points.isEmpty()) return
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ann.color
                textSize = ann.strokeWidth * 3f
                style = Paint.Style.FILL
            }
            val x = ann.points[0].x
            val y = ann.points[0].y
            canvas.drawText(ann.text, x, y, paint)
        }

        private fun drawStamp(canvas: Canvas, ann: Annotation) {
            if (ann.text.isNullOrEmpty() || ann.points.isEmpty()) return
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                alpha = 160
                textSize = dp(32).toFloat()
                style = Paint.Style.FILL
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(ann.text, ann.points[0].x, ann.points[0].y, paint)
        }
    }

    // ─── Save / Export ─────────────────────────────────────────────
    private fun saveAnnotations() {
        if (!annotationManager.hasAny()) { toast("No annotations to save"); return }
        AlertDialog.Builder(this)
            .setTitle("Save document")
            .setItems(arrayOf("Save (overwrite)", "Save As (new file)")) { _, which ->
                exportAnnotatedPdf(saveAs = which == 1)
            }.show()
    }

    private fun exportAnnotatedPdf(saveAs: Boolean) {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.BackgroundDispatcher,
            timeoutMs = 120000L,
            onError = { toast("Save failed: ${it.message}") }
        ) {
            withContext(Dispatchers.Main) { toast("Saving annotations...") }
            val input = pdfFile ?: run {
                withContext(Dispatchers.Main) { toast("No PDF loaded") }
                return@safeLaunch
            }
            val workingOut = File(cacheDir, "annotated_${System.currentTimeMillis()}.pdf")
            val screenW = (resources.displayMetrics.widthPixels - dp(16)).toFloat()

            // Per-page pixel-to-point scale, derived from the actual PDF page size.
            // Annotation points were captured in on-screen pixels against a bitmap
            // rendered at `screenW` pixels wide, so scale = screenPx / pagePt.
            val pageScales = mutableMapOf<Int, Float>()
            try {
                android.os.ParcelFileDescriptor.open(input, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                pageScales[i] = if (page.width > 0) screenW / page.width else 1f
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Save failed: ${e.message}") }
                return@safeLaunch
            }

            val strokesByPage = mutableMapOf<Int, Pair<List<com.propdf.core.domain.model.AnnotationStroke>, Float>>()
            val textsByPage = mutableMapOf<Int, Pair<List<com.propdf.core.domain.model.AnnotationText>, Float>>()
            for (pageIdx in 0 until totalPages) {
                val anns = annotationManager.get(pageIdx)
                if (anns.isEmpty()) continue
                val scale = pageScales[pageIdx] ?: (screenW / 612f) // fallback: US Letter width in points
                val (strokes, texts) = mapAnnotationsForExport(anns)
                if (strokes.isNotEmpty()) strokesByPage[pageIdx] = strokes to scale
                if (texts.isNotEmpty()) textsByPage[pageIdx] = texts to scale
            }

            if (strokesByPage.isEmpty() && textsByPage.isEmpty()) {
                withContext(Dispatchers.Main) { toast("No annotations to save") }
                return@safeLaunch
            }

            val result = pdfOperationsRepository.saveAnnotations(input, workingOut, strokesByPage, textsByPage)
            when (result) {
                is com.propdf.core.domain.result.AppResult.Success -> {
                    val saveResult = FileHelper.saveToDownloads(this@ViewerActivity, workingOut)
                    withContext(Dispatchers.Main) {
                        toast("Saved to ${saveResult.displayPath}")
                        if (!saveAs) {
                            // Point the open viewer at the newly saved file so further
                            // edits/OCR/ops operate on the annotated version.
                            pdfFile = saveResult.file ?: workingOut
                        }
                    }
                }
                is com.propdf.core.domain.result.AppResult.Error -> {
                    withContext(Dispatchers.Main) { toast("Save failed: ${result.message}") }
                }
                else -> Unit
            }
        }
    }

    /**
     * Maps this activity's local Annotation model onto the shared
     * AnnotationStroke/AnnotationText models consumed by
     * PdfOperationsRepository.saveAnnotations. Rect/circle tools only
     * capture two corner points, so they're expanded into a closed
     * polygon here since the shared exporter draws pathData as a
     * straight polyline.
     */
    private fun mapAnnotationsForExport(
        anns: List<Annotation>
    ): Pair<List<com.propdf.core.domain.model.AnnotationStroke>, List<com.propdf.core.domain.model.AnnotationText>> {
        val strokes = mutableListOf<com.propdf.core.domain.model.AnnotationStroke>()
        val texts = mutableListOf<com.propdf.core.domain.model.AnnotationText>()

        anns.forEach { ann ->
            when (ann.type) {
                AnnotationType.TEXT -> {
                    val p = ann.points.firstOrNull() ?: return@forEach
                    texts.add(
                        com.propdf.core.domain.model.AnnotationText(
                            x = p.x, y = p.y, text = ann.text.orEmpty(),
                            color = ann.color, sizePx = ann.strokeWidth * 3f
                        )
                    )
                }
                AnnotationType.STAMP -> {
                    val p = ann.points.firstOrNull() ?: return@forEach
                    texts.add(
                        com.propdf.core.domain.model.AnnotationText(
                            x = p.x, y = p.y, text = ann.text.orEmpty(),
                            color = Color.RED, sizePx = dp(32).toFloat()
                        )
                    )
                }
                AnnotationType.ERASER -> Unit // already removed from the store, nothing to burn
                AnnotationType.RECT -> {
                    if (ann.points.size < 2) return@forEach
                    val a = ann.points[0]; val b = ann.points[1]
                    val left = minOf(a.x, b.x); val right = maxOf(a.x, b.x)
                    val top = minOf(a.y, b.y); val bottom = maxOf(a.y, b.y)
                    val path = listOf(
                        com.propdf.core.domain.model.PointF(left, top),
                        com.propdf.core.domain.model.PointF(right, top),
                        com.propdf.core.domain.model.PointF(right, bottom),
                        com.propdf.core.domain.model.PointF(left, bottom),
                        com.propdf.core.domain.model.PointF(left, top)
                    )
                    strokes.add(
                        com.propdf.core.domain.model.AnnotationStroke(
                            pathData = path, color = ann.color, strokeWidth = ann.strokeWidth, tool = "rect"
                        )
                    )
                }
                AnnotationType.CIRCLE -> {
                    if (ann.points.size < 2) return@forEach
                    val a = ann.points[0]; val b = ann.points[1]
                    val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
                    val rx = kotlin.math.abs(b.x - a.x) / 2f; val ry = kotlin.math.abs(b.y - a.y) / 2f
                    val steps = 32
                    val path = (0..steps).map { i ->
                        val theta = 2.0 * Math.PI * i / steps
                        com.propdf.core.domain.model.PointF(
                            (cx + rx * kotlin.math.cos(theta)).toFloat(),
                            (cy + ry * kotlin.math.sin(theta)).toFloat()
                        )
                    }
                    strokes.add(
                        com.propdf.core.domain.model.AnnotationStroke(
                            pathData = path, color = ann.color, strokeWidth = ann.strokeWidth, tool = "circle"
                        )
                    )
                }
                else -> {
                    // freehand, highlight, underline, strikeout, arrow -> direct polyline
                    val path = ann.points.map { com.propdf.core.domain.model.PointF(it.x, it.y) }
                    strokes.add(
                        com.propdf.core.domain.model.AnnotationStroke(
                            pathData = path, color = ann.color, strokeWidth = ann.strokeWidth,
                            tool = ann.type.name.lowercase()
                        )
                    )
                }
            }
        }
        return strokes to texts
    }

    // ─── Helpers ───────────────────────────────────────────────────
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

    private fun updateUndoRedoBtns() {
        if (!::undoBtn.isInitialized) return
        val on = Color.parseColor("#ADC6FF")
        val off = Color.parseColor("#444444")
        undoBtn.setTextColor(if (annotationManager.undoCount() > 0) on else off)
        redoBtn.setTextColor(if (annotationManager.redoCount() > 0) on else off)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toggleSearchBar() {
        if (searchBar.visibility == View.VISIBLE) hideSearchBar() else showSearchBar()
    }

    private fun showSearchBar() {
        searchBar.visibility = View.VISIBLE
        searchInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        searchResults = emptyList()
        searchResultIdx = 0
        lastSearchQuery = ""
        hideKeyboard()
    }

    private fun buildSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            searchInput = EditText(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                hint = "Search in document"
                setHintTextColor(Color.parseColor("#777777"))
                setTextColor(Color.parseColor("#FFFFFF"))
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                background = null
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
                }
            }
            addView(searchInput)

            searchCountLabel = TextView(this@ViewerActivity).apply {
                text = ""
                textSize = 11f
                setTextColor(Color.parseColor("#ADC6FF"))
                setPadding(dp(6), 0, dp(6), 0)
            }
            addView(searchCountLabel)

            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Prev", Color.parseColor("#ADC6FF")) {
                if (searchResults.isEmpty()) return@buildIconBtn
                searchResultIdx = (searchResultIdx - 1 + searchResults.size) % searchResults.size
                updateSearchCounter()
                scrollToPage(searchResults[searchResultIdx])
            })
            addView(buildIconBtn(android.R.drawable.ic_media_next, "Next", Color.parseColor("#ADC6FF")) {
                if (searchResults.isEmpty()) return@buildIconBtn
                searchResultIdx = (searchResultIdx + 1) % searchResults.size
                updateSearchCounter()
                scrollToPage(searchResults[searchResultIdx])
            })
            addView(buildIconBtn(android.R.drawable.ic_menu_close_clear_cancel, "Close", Color.parseColor("#ADC6FF")) {
                hideSearchBar()
            })
        }
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = if (searchResults.isEmpty()) "0/0" else "${searchResultIdx + 1}/${searchResults.size}"
    }
    private var nightModeEnabled = false

    private fun showReadingModeDialog() {
        val options = arrayOf("Normal", "Night mode")
        val current = if (nightModeEnabled) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Reading mode")
            .setSingleChoiceItems(options, current) { dialog, which ->
                nightModeEnabled = which == 1
                if (::pageAdapter.isInitialized) pageAdapter.setNightMode(nightModeEnabled)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOcrMenu() {
        val options = arrayOf("Extract text from this page", "Extract text from all pages")
        AlertDialog.Builder(this)
            .setTitle("OCR")
            .setItems(options) { _, which ->
                if (which == 0) ocrCurrentPage() else ocrAllPages()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ocrCurrentPage() {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.BackgroundDispatcher,
            onError = { toast("OCR failed: ${it.message}") }
        ) {
            val renderer = backgroundRenderer ?: return@safeLaunch
            val screenW = resources.displayMetrics.widthPixels
            val bitmap = renderer.getPage(currentPage, screenW) ?: run {
                withContext(Dispatchers.Main) { toast("Could not render page for OCR") }
                return@safeLaunch
            }
            val text = ocrManager.recognize(bitmap)
            withContext(Dispatchers.Main) { showOcrResultDialog(text, "Page ${currentPage + 1}") }
        }
    }

    private fun ocrAllPages() {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.BackgroundDispatcher, timeoutMs = 180000L,
            onError = { toast("OCR failed: ${it.message}") }
        ) {
            withContext(Dispatchers.Main) { toast("Running OCR on $totalPages pages...") }
            val renderer = backgroundRenderer ?: return@safeLaunch
            val screenW = resources.displayMetrics.widthPixels
            val builder = StringBuilder()
            for (i in 0 until totalPages) {
                val bitmap = renderer.getPage(i, screenW) ?: continue
                val text = ocrManager.recognize(bitmap)
                if (text.isNotBlank()) builder.append("--- Page ${i + 1} ---\n").append(text).append("\n\n")
            }
            withContext(Dispatchers.Main) { showOcrResultDialog(builder.toString(), "All pages") }
        }
    }

    private fun showOcrResultDialog(text: String, label: String) {
        if (text.isBlank()) { toast("No text detected"); return }
        val display = TextView(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setTextIsSelectable(true)
            textSize = 13f
            setTextColor(Color.parseColor("#DDDDDD"))
            this.text = text
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(400))
            addView(display)
        }
        AlertDialog.Builder(this)
            .setTitle("OCR result — $label")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR text", text))
                toast("Copied to clipboard")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPdfOpsMenu() {
        val options = arrayOf(
            "Compress PDF", "Add watermark", "Add page numbers",
            "Rotate current page", "Delete current page"
        )
        AlertDialog.Builder(this)
            .setTitle("PDF tools")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runPdfOp("Compressing...") { input, output ->
                        pdfOperationsManager.compressPdf(input, output)
                    }
                    1 -> promptWatermarkText()
                    2 -> runPdfOp("Adding page numbers...") { input, output ->
                        pdfOperationsManager.addPageNumbers(input, output)
                    }
                    3 -> runPdfOp("Rotating page...") { input, output ->
                        pdfOperationsManager.rotatePages(input, output, mapOf(currentPage to 90))
                    }
                    4 -> confirmDeleteCurrentPage()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptWatermarkText() {
        val input = EditText(this).apply { hint = "Watermark text"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Add watermark")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) { toast("Enter watermark text"); return@setPositiveButton }
                runPdfOp("Adding watermark...") { in_, out ->
                    pdfOperationsManager.addTextWatermark(in_, out, text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteCurrentPage() {
        if (totalPages <= 1) { toast("Cannot delete the only page"); return }
        AlertDialog.Builder(this)
            .setTitle("Delete page ${currentPage + 1}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                runPdfOp("Deleting page...") { input, output ->
                    pdfOperationsManager.deletePages(input, output, listOf(currentPage))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runPdfOp(progressMsg: String, op: suspend (File, File) -> Result<File>) {
        val input = pdfFile ?: run { toast("No PDF loaded"); return }
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.BackgroundDispatcher,
            timeoutMs = 120000L,
            onError = { toast("Operation failed: ${it.message}") }
        ) {
            withContext(Dispatchers.Main) { toast(progressMsg) }
            val output = File(cacheDir, "pdfop_${System.currentTimeMillis()}.pdf")
            val result = op(input, output)
            result.onSuccess { newFile ->
                withContext(Dispatchers.Main) {
                    pdfFile = newFile
                    toast("Done — reloading")
                    backgroundRenderer?.close()
                    backgroundRenderer = BackgroundPdfRenderer(newFile, bitmapCache, bitmapPool)
                    totalPages = backgroundRenderer?.pageCount ?: totalPages
                    setupRecyclerView()
                    updatePageCounter()
                }
            }.onFailure {
                withContext(Dispatchers.Main) { toast("Operation failed: ${it.message}") }
            }
        }
    }
    private fun annotationCacheFile(): File? {
        val uri = pdfUri ?: return null
        val key = uri.toString().hashCode().toString()
        val dir = File(filesDir, "annotation_cache").apply { mkdirs() }
        return File(dir, "$key.json")
    }

    private fun loadAnnotationsFromCache() {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.IoDispatcher) {
            val file = annotationCacheFile() ?: return@safeLaunch
            if (!file.exists()) return@safeLaunch
            val json = try { file.readText() } catch (_: Exception) { return@safeLaunch }
            withContext(Dispatchers.Main) {
                annotationManager.fromJson(json)
                updateUndoRedoBtns()
                if (::pageAdapter.isInitialized) pageAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun persistAnnotationCache() {
        if (!annotationManager.hasAny()) return
        val file = annotationCacheFile() ?: return
        val json = annotationManager.toJson()
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.IoDispatcher,
            onError = { Log.w("ViewerActivity", "Failed to persist annotation cache", it) }
        ) {
            try { file.writeText(json) } catch (_: Exception) { }
        }
    }

    companion object {
        const val EXTRA_URI = "extra_pdf_uri"
        const val EXTRA_PASSWORD = "extra_pdf_password"
        const val EXTRA_DISPLAY_NAME = "extra_pdf_display_name"

        fun start(context: Context, uri: Uri, password: String? = null, displayName: String? = null) {
            context.startActivity(Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                if (password != null) putExtra(EXTRA_PASSWORD, password)
                if (displayName != null) putExtra(EXTRA_DISPLAY_NAME, displayName)
            })
        }
    }
}
