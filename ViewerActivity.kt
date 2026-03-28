package com.propdf.editor.ui.viewer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.utils.FileHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer as PdfBoxRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

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
    private var totalPages  = 0
    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    private var activeColor = Color.parseColor("#E53935")
    private var strokeWidth = 14f
    private var textSizePx  = 44f
    private var isDark      = false
    private var currentPage = 0
    private var currentTab  = "view"   // view | annotate | pages | tools

    // FIX: store per-page scale (screen pixels / PDF points) for correct annotation saving
    private val pageScales = mutableMapOf<Int, Float>()
    private val canvases   = mutableMapOf<Int, AnnotationCanvasView>()

    // 16-color palette
    private val colorHex  = listOf(
        "#F44336","#E91E63","#9C27B0","#3F51B5",
        "#1A73E8","#009688","#4CAF50","#8BC34A",
        "#FFEB3B","#FF9800","#795548","#000000",
        "#FFFFFF","#607D8B","#FF5722","#00BCD4"
    )
    private val colorNames = listOf(
        "Red","Pink","Purple","Indigo","Blue","Teal","Green","Light Green",
        "Yellow","Orange","Brown","Black","White","Steel","DeepOrange","Cyan"
    )

    // Views
    private lateinit var scrollView     : ScrollView
    private lateinit var pageContainer  : LinearLayout
    private lateinit var tvInfo         : TextView
    private lateinit var progressBar    : ProgressBar
    private lateinit var topBar         : LinearLayout
    private lateinit var bottomNav      : BottomNavigationView
    private lateinit var annotToolbar   : LinearLayout
    private lateinit var sizeBar        : LinearLayout
    private lateinit var seekStroke     : SeekBar
    private lateinit var tvPageCounter  : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ---- Build full-screen premium UI ------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#303030"))
        }

        // ---- TOP BAR ----
        topBar = LinearLayout(this).apply {
            orientation   = LinearLayout.HORIZONTAL
            layoutParams  = LinearLayout.LayoutParams(-1, dp(52))
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(dp(4), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(4).toFloat()
        }
        val btnBack = makeImgBtn(android.R.drawable.ic_menu_close_clear_cancel, "Back") { finish() }
        tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.WHITE); textSize = 13f; text = "Loading..."
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        tvPageCounter = TextView(this).apply {
            text = "0/0"; textSize = 11f; setTextColor(Color.parseColor("#BBDDFF"))
            setPadding(dp(6), 0, dp(6), 0)
        }
        val btnNight = makeTextBtn(if (isDark) "Day" else "Night") { toggleDark() }
        val btnShare = makeImgBtn(android.R.drawable.ic_menu_share, "Share") { sharePdf() }
        topBar.addView(btnBack); topBar.addView(tvInfo); topBar.addView(tvPageCounter)
        topBar.addView(btnNight); topBar.addView(btnShare)

        // ---- PAGE COUNTER/NAV row ----
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(40))
            setBackgroundColor(Color.parseColor("#222222"))
            setPadding(dp(4), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnTop  = makeTextBtn("Top")  { scrollView.smoothScrollTo(0, 0) }
        val btnPrev = makeTextBtn("Prev") { goToPage(currentPage - 1) }
        val spacer  = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }
        val btnFind = makeTextBtn("Find") { showFindDialog() }
        val btnGoto = makeTextBtn("Go")   { showGotoDialog() }
        val btnNext = makeTextBtn("Next") { goToPage(currentPage + 1) }
        val btnBot  = makeTextBtn("End")  {
            scrollView.post { scrollView.smoothScrollTo(0, scrollView.getChildAt(0).height) }
        }
        navRow.addView(btnTop); navRow.addView(btnPrev); navRow.addView(spacer)
        navRow.addView(btnFind); navRow.addView(btnGoto)
        navRow.addView(btnNext); navRow.addView(btnBot)

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#1A73E8"))
        }

        // ---- SCROLL VIEW (pages) ----
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updatePageCounter(scrollY)
            }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        // ---- SIZE SLIDER ----
        sizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val tvSzLabel = TextView(this).apply {
            text = "14"; textSize = 11f; setTextColor(Color.WHITE); minWidth = dp(28)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 100; progress = 14
        }
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

        // ---- ANNOTATION TOOLBAR (shown when Annotate tab active) ----
        annotToolbar = buildAnnotToolbar()

        // ---- BOTTOM NAV ----
        bottomNav = BottomNavigationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(60))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            inflateMenu(0)  // will set items programmatically
        }
        buildBottomNav()

        root.addView(topBar); root.addView(navRow); root.addView(progressBar)
        root.addView(scrollView); root.addView(sizeBar)
        root.addView(annotToolbar); root.addView(bottomNav)
        setContentView(root)
    }

    private fun buildAnnotToolbar(): LinearLayout {
        data class T(val id: String, val label: String, val hex: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,  "Pen",    "#1A73E8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT, "Hi-Lite","#F57C00"),
            T(AnnotationCanvasView.TOOL_TEXT,      "Text",   "#2E7D32"),
            T(AnnotationCanvasView.TOOL_RECT,      "Box",    "#7B1FA2"),
            T(AnnotationCanvasView.TOOL_ERASER,    "Erase",  "#757575"),
            T(AnnotationCanvasView.TOOL_MOVE_TEXT, "Move",   "#0097A7"),
            T("color",                             "Color",  "#E91E63"),
            T("undo",                              "Undo",   "#AAAAAA"),
            T("redo",                              "Redo",   "#AAAAAA"),
            T("extract",                           "Copy",   "#1565C0"),
            T("save",                              "Save",   "#2E7D32"),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setPadding(0, dp(2), 0, dp(2))
            visibility = View.GONE
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.label; textSize = 9f; tag = t.id
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setTextColor(Color.parseColor(t.hex))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onAnnotTool(t.id, Color.parseColor(t.hex)) }
                })
            }
        }
    }

    private fun buildBottomNav() {
        // Build manually since we control the view
        val tabs = listOf(
            Triple("view",     "View",     android.R.drawable.ic_menu_view),
            Triple("annotate", "Annotate", android.R.drawable.ic_menu_edit),
            Triple("pages",    "Pages",    android.R.drawable.ic_menu_agenda),
            Triple("tools",    "Tools",    android.R.drawable.ic_menu_preferences),
        )
        // Replace bottomNav with a custom LinearLayout since BottomNavigationView needs XML menu
        val navContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(60))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        tabs.forEach { (id, label, icon) ->
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { switchTab(id) }
            }
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(icon)
                setColorFilter(if (id == currentTab) Color.parseColor("#1A73E8")
                               else Color.parseColor("#888888"))
                tag = "${id}_icon"
            }
            val tv = TextView(this).apply {
                text = label; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(if (id == currentTab) Color.parseColor("#1A73E8")
                             else Color.parseColor("#888888"))
                setPadding(0, dp(2), 0, 0)
                tag = "${id}_label"
            }
            btn.addView(iv); btn.addView(tv)
            navContainer.addView(btn)
        }
        // Swap bottomNav with custom container
        val root = bottomNav.parent as? LinearLayout ?: return
        val idx  = root.indexOfChild(bottomNav)
        root.removeView(bottomNav)
        root.addView(navContainer, idx)
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        // Update icon/text colors in bottom nav
        val navContainer = (scrollView.parent as? LinearLayout)
            ?.getChildAt(7) as? LinearLayout  // navContainer index
        // Simpler: just update annotToolbar and sizeBar visibility
        annotToolbar.visibility = if (tab == "annotate") View.VISIBLE else View.GONE
        sizeBar.visibility      = View.GONE

        if (tab != "annotate") {
            activeTool = AnnotationCanvasView.TOOL_NONE
            canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, activeColor) }
        }
        when (tab) {
            "pages" -> showPagesBottomSheet()
            "tools" -> {
                startActivity(Intent(this, com.propdf.editor.ui.tools.ToolsActivity::class.java))
            }
        }
    }

    // ---- PDF Loading ---------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    FileHelper.uriToFile(this@ViewerActivity, getUri() ?: return@withContext null)
                }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot open PDF"); return@launch
                }
                pdfFile = file
                if (!tryAndroidRenderer(file)) {
                    pageContainer.removeAllViews(); canvases.clear(); pageScales.clear()
                    tryPdfBoxRenderer(file)
                }
            } catch (e: Exception) { showError("Error: ${e.message}") }
        }
    }

    private fun getUri(): Uri? {
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        val path   = intent.getStringExtra(EXTRA_PDF_PATH)
        return when {
            path != null   -> Uri.fromFile(File(path))
            uriStr != null -> Uri.parse(uriStr)
            intent.data != null -> intent.data
            else -> null
        }
    }

    private suspend fun tryAndroidRenderer(file: File): Boolean = try {
        withContext(Dispatchers.IO) {
            val fd   = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                var scale = 1f
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    // FIX: store the real scale = screen pixels / PDF points
                    scale = sw.toFloat() / p.width.toFloat()
                    val bh = (p.height * scale).toInt().coerceAtLeast(1)
                    val b  = Bitmap.createBitmap(sw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                // Store scale BEFORE adding the page to UI
                pageScales[i] = scale
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying PDFBox..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File) = withContext(Dispatchers.IO) {
        try {
            val doc  = PDDocument.load(file); val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw  = rndr.renderImageWithDPI(i, 150f)
                val scale = sw.toFloat() / raw.width.toFloat()
                pageScales[i] = scale
                val sc2 = Bitmap.createScaledBitmap(raw, sw, (raw.height * scale).toInt().coerceAtLeast(1), true)
                if (sc2 !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(sc2, i) }
            }
            doc.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { showError("Cannot open: ${e.message}") }
        }
    }

    private fun addPage(bmp: Bitmap, idx: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            }
            setBackgroundColor(Color.WHITE)
            tag = "page_$idx"
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
        }
        val tvNum = TextView(this).apply {
            text = "${idx + 1}"; textSize = 9f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(5), dp(1), dp(5), dp(1))
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(3), dp(3), 0)
            }
        }
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTool(AnnotationCanvasView.TOOL_NONE, activeColor)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs); frame.addView(tvNum)
        pageContainer.addView(frame)
        if (isDark) applyDarkFilter(iv)

        // Update page counter once all pages loaded
        if (idx == totalPages - 1) {
            tvPageCounter.text = "1/$totalPages"
        }
    }

    // ---- Navigation ---------------------------------------------------

    private fun updatePageCounter(scrollY: Int) {
        var cum = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < cum + child.height) {
                currentPage = i; tvPageCounter.text = "${i+1}/$totalPages"; return
            }
            cum += child.height
        }
    }

    private fun goToPage(page: Int) {
        val target = page.coerceIn(0, totalPages - 1)
        var cum = 0
        for (i in 0 until target)
            cum += pageContainer.getChildAt(i)?.height ?: 0
        scrollView.smoothScrollTo(0, cum)
    }

    private fun showGotoDialog() {
        val et = EditText(this).apply {
            hint = "Page (1-$totalPages)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n in 1..totalPages) goToPage(n - 1)
                else toast("Enter 1-$totalPages")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFindDialog() {
        val et = EditText(this).apply {
            hint = "Search text in PDF..."; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Find Text").setView(et)
            .setPositiveButton("Search") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) searchPdf(q) else toast("Enter search text")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun searchPdf(query: String) {
        val file = pdfFile ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val doc      = PDDocument.load(file)
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.sortByPosition = true
                    val found = mutableListOf<Pair<Int, String>>()
                    for (i in 1..doc.numberOfPages) {
                        stripper.startPage = i; stripper.endPage = i
                        val t = stripper.getText(doc)
                        if (t.contains(query, ignoreCase = true)) {
                            val excerpt = t.lines().filter { it.contains(query, ignoreCase = true) }
                                .take(2).joinToString("  |  ")
                            found.add(i to excerpt.take(80))
                        }
                    }
                    doc.close(); found
                } catch (_: Exception) { emptyList() }
            }
            progressBar.visibility = View.GONE
            if (results.isEmpty()) { toast("\"$query\" not found"); return@launch }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Found on ${results.size} page(s)")
                .setItems(results.map { (p, e) -> "Page $p: $e" }.toTypedArray()) { _, which ->
                    goToPage(results[which].first - 1)
                }.setNegativeButton("Close", null).show()
        }
    }

    // ---- Annotate tools -----------------------------------------------

    private fun onAnnotTool(id: String, color: Int) {
        highlightAnnotBtn(id)
        when (id) {
            "undo"    -> { canvases.values.forEach { it.undo() }; toast("Undone") }
            "redo"    -> { canvases.values.forEach { it.redo() }; toast("Redone") }
            "color"   -> pickColor { c -> activeColor = c; canvases.values.forEach { it.setColor(c) } }
            "extract" -> extractText()
            "save"    -> saveWithAnnotations()
            AnnotationCanvasView.TOOL_TEXT -> showTextInputDialog()
            else -> {
                activeTool = id; activeColor = color
                canvases.values.forEach { cv ->
                    cv.setTool(id, color); cv.setStrokeWidth(strokeWidth); cv.setTextSize(textSizePx)
                }
                sizeBar.visibility = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND,
                    AnnotationCanvasView.TOOL_HIGHLIGHT,
                    AnnotationCanvasView.TOOL_ERASER -> View.VISIBLE
                    else -> View.GONE
                }
                toast(when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "Draw on page"
                    AnnotationCanvasView.TOOL_HIGHLIGHT -> "Drag to highlight"
                    AnnotationCanvasView.TOOL_RECT      -> "Drag to draw box"
                    AnnotationCanvasView.TOOL_ERASER    -> "Drag to erase"
                    AnnotationCanvasView.TOOL_MOVE_TEXT -> "Drag a text note"
                    else -> id
                })
            }
        }
    }

    private fun highlightAnnotBtn(activeId: String) {
        for (i in 0 until annotToolbar.childCount) {
            val btn = annotToolbar.getChildAt(i) as? Button ?: continue
            btn.setBackgroundColor(
                if (btn.tag == activeId) Color.parseColor("#16213E") else Color.TRANSPARENT
            )
        }
    }

    private fun showTextInputDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply {
            hint = "Type your note (Tamil, Hindi, any language)"; textSize = 15f; maxLines = 4
        }
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val tvSz = TextView(this).apply {
            text = "Size: ${(textSizePx/3).toInt()}"; textSize = 12f
            setTextColor(Color.parseColor("#333333")); minWidth = dp(72)
        }
        val sk = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0,-2,1f); max=80; progress=(textSizePx/3).toInt()
        }
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val sz = ((v+4)*3).toFloat(); textSizePx = sz; tvSz.text = "Size: $v"
                canvases.values.forEach { it.setTextSize(sz) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sRow.addView(tvSz); sRow.addView(sk)
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        cRow.addView(TextView(this).apply { text = "Color: "; textSize = 12f; setPadding(0,0,dp(8),0) })
        var textColor = activeColor
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40),dp(40))
            setBackgroundColor(textColor)
            setOnClickListener { pickColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        cRow.addView(swatch)
        lay.addView(et); lay.addView(sRow); lay.addView(cRow)
        AlertDialog.Builder(this).setTitle("Add Text Note")
            .setMessage("Tap 'Place' then tap the page where you want the text")
            .setView(lay)
            .setPositiveButton("Place") { _, _ ->
                val txt = et.text.toString()
                if (txt.isEmpty()) { toast("Type text first"); return@setPositiveButton }
                activeTool = AnnotationCanvasView.TOOL_TEXT; activeColor = textColor
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    cv.setTextSize(textSizePx); cv.setPendingText(txt)
                }
                sizeBar.visibility = View.GONE
                toast("Tap on the page to place your text")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = 4; setPadding(dp(12),dp(12),dp(12),dp(12)) }
        var dlg: AlertDialog? = null
        colorHex.forEachIndexed { i, hex ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(52); height = dp(52); setMargins(dp(5),dp(5),dp(5),dp(5))
                }
                setBackgroundColor(Color.parseColor(hex))
                elevation = dp(2).toFloat()
                setOnClickListener { onPick(Color.parseColor(hex)); dlg?.dismiss(); toast(colorNames[i]) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Choose Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ---- Dark mode ----------------------------------------------------

    private fun toggleDark() {
        isDark = !isDark
        val filter = if (isDark) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
             0f,-1f, 0f, 0f, 255f,
             0f, 0f,-1f, 0f, 255f,
             0f, 0f, 0f, 1f,   0f
        ))) else null
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            (frame.getChildAt(0) as? ImageView)?.colorFilter = filter
        }
        scrollView.setBackgroundColor(if (isDark) Color.BLACK else Color.parseColor("#303030"))
        // Update button text
        (topBar.getChildAt(3) as? Button)?.text = if (isDark) "Day" else "Night"
        toast(if (isDark) "Dark mode ON" else "Light mode ON")
    }

    private fun applyDarkFilter(iv: ImageView) {
        iv.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f,-1f, 0f, 0f, 255f, 0f, 0f,-1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f
        )))
    }

    // ---- Pages bottom sheet -------------------------------------------

    private fun showPagesBottomSheet() {
        val file = pdfFile ?: return
        val dlg  = AlertDialog.Builder(this).setTitle("Page Actions")
            .setItems(arrayOf(
                "Go to Page...",
                "Extract Page Range...",
                "Delete Page ${ currentPage + 1 }",
                "Rotate Page ${ currentPage + 1 } 90 degrees"
            )) { _, which ->
                when (which) {
                    0 -> showGotoDialog()
                    1 -> doExtractPages(file)
                    2 -> doDeletePage(file, currentPage + 1)
                    3 -> doRotatePage(file, currentPage + 1)
                }
            }.setNegativeButton("Cancel", null).create()
        dlg.show()
    }

    private fun doExtractPages(file: File) {
        val et = EditText(this).apply { hint = "Range e.g. 2-5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages").setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val parts = et.text.toString().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.splitPdf(file, cacheDir, listOf(from..to)).fold(
                        onSuccess = { files ->
                            progressBar.visibility = View.GONE
                            val out = files.firstOrNull() ?: return@fold
                            val named = File(cacheDir, "${file.nameWithoutExtension}_pages${from}_${to}.pdf")
                            out.copyTo(named, overwrite = true)
                            val saved = withContext(Dispatchers.IO) {
                                FileHelper.saveToDownloads(this@ViewerActivity, named)
                            }
                            AlertDialog.Builder(this@ViewerActivity)
                                .setTitle("Extracted!")
                                .setMessage("Pages $from-$to saved to:\n${saved.displayPath}")
                                .setPositiveButton("Open") { _, _ ->
                                    val uri = try {
                                        androidx.core.content.FileProvider.getUriForFile(
                                            this@ViewerActivity, "$packageName.provider",
                                            saved.file ?: named
                                        )
                                    } catch (_: Exception) { Uri.fromFile(named) }
                                    start(this@ViewerActivity, uri)
                                }
                                .setNegativeButton("OK", null).show()
                        },
                        onFailure = { progressBar.visibility = View.GONE; showError("Extract failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePage(file: File, pageNum: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Page $pageNum?")
            .setMessage("This will save a new PDF without page $pageNum to Downloads.")
            .setPositiveButton("Delete") { _, _ ->
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val out = FileHelper.tempFile(this@ViewerActivity, "${file.nameWithoutExtension}_deleted")
                    pdfOps.deletePages(file, out, listOf(pageNum)).fold(
                        onSuccess = {
                            progressBar.visibility = View.GONE
                            val saved = withContext(Dispatchers.IO) { FileHelper.saveToDownloads(this@ViewerActivity, it) }
                            toast("Page $pageNum deleted. Saved to ${saved.displayPath}")
                        },
                        onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRotatePage(file: File, pageNum: Int) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = FileHelper.tempFile(this@ViewerActivity, "${file.nameWithoutExtension}_rotated")
            pdfOps.rotatePages(file, out, mapOf(pageNum to 90)).fold(
                onSuccess = {
                    progressBar.visibility = View.GONE
                    val saved = withContext(Dispatchers.IO) { FileHelper.saveToDownloads(this@ViewerActivity, it) }
                    toast("Page $pageNum rotated. Saved to ${saved.displayPath}")
                },
                onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    // ---- Extract text (multilingual) ----------------------------------

    private fun extractText() {
        val file = pdfFile ?: run { toast("No PDF loaded"); return }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc      = PDDocument.load(file)
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.sortByPosition = true
                    val t = stripper.getText(doc); doc.close()
                    t.trim().ifBlank { null }
                } catch (_: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                AlertDialog.Builder(this@ViewerActivity)
                    .setTitle("No Text Found")
                    .setMessage(
                        "This PDF has no selectable/extractable text.\n\n" +
                        "Note: Tamil and Indic scripts may not extract correctly from PDFs " +
                        "that use non-Unicode font encoding. This is a PDF standard limitation."
                    ).setPositiveButton("OK", null).show()
                return@launch
            }
            val et = EditText(this@ViewerActivity).apply {
                setText(text); textSize = 12f; setTextIsSelectable(true)
                isFocusable = true; isFocusableInTouchMode = true; background = null
                setTextColor(Color.parseColor("#111111"))
            }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Extracted Text")
                .setView(ScrollView(this@ViewerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(380)); addView(et)
                })
                .setPositiveButton("Copy All") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                    toast("Copied!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share Text"))
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- Save annotations (with CORRECT SCALE per page) ---------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }
        if (canvases.values.none { it.hasAnnotations() }) {
            toast("No annotations - draw something first"); return
        }
        val et = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Save Annotated PDF")
            .setMessage("Output saved to Downloads:").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim()
                    .ifBlank { file.nameWithoutExtension + "_annotated" }.removeSuffix(".pdf")
                doSave(file, name)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doSave(file: File, outName: String) {
        progressBar.visibility = View.VISIBLE; tvInfo.text = "Saving..."
        lifecycleScope.launch {
            try {
                val pd = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val td = mutableMapOf<Int, Pair<List<AnnotationCanvasView.TextAnnot>, Float>>()
                for ((idx, cvs) in canvases) {
                    // FIX: use the STORED scale for this specific page
                    val scale = pageScales[idx] ?: 1f
                    cvs.getStrokes().takeIf    { it.isNotEmpty() }?.let { pd[idx] = Pair(it, scale) }
                    cvs.getTextAnnots().takeIf { it.isNotEmpty() }?.let { td[idx] = Pair(it, scale) }
                }
                if (pd.isEmpty() && td.isEmpty()) {
                    progressBar.visibility = View.GONE; tvInfo.text = file.name
                    toast("No annotations found"); return@launch
                }
                val tmp = FileHelper.tempFile(this@ViewerActivity, outName)
                pdfOps.saveAnnotationsToPdf(file, tmp, pd, td).fold(
                    onSuccess = { savedTmp ->
                        val named = File(cacheDir, "$outName.pdf")
                        savedTmp.copyTo(named, overwrite = true)
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@ViewerActivity, named)
                        }
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("Saved!")
                            .setMessage("$outName.pdf\n\n${saved.displayPath}")
                            .setPositiveButton("OK", null).show()
                    },
                    onFailure = {
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        showError("Save failed: ${it.message}")
                    }
                )
            } catch (e: Exception) {
                progressBar.visibility = View.GONE; tvInfo.text = file.name
                showError("Error: ${e.message}")
            }
        }
    }

    // ---- Helpers -------------------------------------------------------

    private fun sharePdf() {
        val f = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C")); tvInfo.text = "Error: $msg"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun makeImgBtn(icon: Int, desc: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.WHITE); contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { action() }
    }

    private fun makeTextBtn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f
        setTextColor(Color.parseColor("#AADDFF")); setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
