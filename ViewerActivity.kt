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

    // FIX: per-page scale stored at render time
    private val pageScales  = mutableMapOf<Int, Float>()
    private val canvases    = mutableMapOf<Int, AnnotationCanvasView>()

    private val colorHex  = listOf(
        "#F44336","#E91E63","#9C27B0","#3F51B5",
        "#1A73E8","#009688","#4CAF50","#8BC34A",
        "#FFEB3B","#FF9800","#795548","#000000",
        "#FFFFFF","#607D8B","#FF5722","#00BCD4"
    )
    private val colorNames = listOf(
        "Red","Pink","Purple","Indigo","Blue","Teal","Green","Lt Green",
        "Yellow","Orange","Brown","Black","White","Steel","Dp Orange","Cyan"
    )

    // Views
    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotToolbar  : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvPageCounter : TextView
    private lateinit var bottomNavBar  : LinearLayout  // custom bottom nav
    private lateinit var rootLayout    : LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ---- UI Build (no swaps, no tricks) --------------------------------

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
            setPadding(dp(2), 0, dp(2), 0)
            gravity = Gravity.CENTER_VERTICAL
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
            setPadding(dp(6), 0, dp(6), 0)
        }
        topBar.addView(tvInfo); topBar.addView(tvPageCounter)
        topBar.addView(makeTextBtn("Find")  { showFindDialog() })
        topBar.addView(makeTextBtn("Go")    { showGotoDialog() })
        topBar.addView(makeTextBtn("Mode")  { toggleDark() })
        topBar.addView(makeImgBtn(android.R.drawable.ic_menu_share) { sharePdf() })

        // Nav bar (Top/Prev/Next/End)
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(38))
            setBackgroundColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        navBar.addView(makeTextBtn("Top")  { scrollView.smoothScrollTo(0, 0) })
        navBar.addView(makeTextBtn("Prev") { goToPage(currentPage - 1) })
        navBar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -1, 1f) })
        navBar.addView(makeTextBtn("Next") { goToPage(currentPage + 1) })
        navBar.addView(makeTextBtn("End")  {
            scrollView.post { scrollView.smoothScrollTo(0, scrollView.getChildAt(0)?.height ?: 0) }
        })

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A73E8"))
        }

        // Scroll area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setOnScrollChangeListener { _, _, scrollY, _, _ -> updatePageCounter(scrollY) }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        // Size slider
        sizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val tvSzLabel = TextView(this).apply {
            text = "14"; textSize = 11f; setTextColor(Color.WHITE); minWidth = dp(28)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); max = 100; progress = 14
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

        // Annotation toolbar
        annotToolbar = buildAnnotToolbar()

        // Bottom navigation - built directly, no swap
        bottomNavBar = buildBottomNavBar()

        rootLayout.addView(topBar)
        rootLayout.addView(navBar)
        rootLayout.addView(progressBar)
        rootLayout.addView(scrollView)
        rootLayout.addView(sizeBar)
        rootLayout.addView(annotToolbar)
        rootLayout.addView(bottomNavBar)
        setContentView(rootLayout)
    }

    private fun buildAnnotToolbar(): LinearLayout {
        data class T(val id: String, val lbl: String, val hex: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,  "Pen",    "#5C9EE8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT, "Hi-Lite","#FF9800"),
            T(AnnotationCanvasView.TOOL_TEXT,      "Text",   "#66BB6A"),
            T(AnnotationCanvasView.TOOL_RECT,      "Box",    "#CE93D8"),
            T(AnnotationCanvasView.TOOL_ERASER,    "Erase",  "#90A4AE"),
            T(AnnotationCanvasView.TOOL_MOVE_TEXT, "Move",   "#4DD0E1"),
            T("color",   "Color",  "#F48FB1"),
            T("undo",    "Undo",   "#AAAAAA"),
            T("redo",    "Redo",   "#AAAAAA"),
            T("extract", "CopyTxt","#90CAF9"),
            T("save",    "Save",   "#A5D6A7"),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(Color.parseColor("#0D1B2A"))
            visibility = View.GONE
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.lbl; textSize = 8.5f; tag = t.id
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setTextColor(Color.parseColor(t.hex))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onAnnotTool(t.id, Color.parseColor(t.hex)) }
                })
            }
        }
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
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))
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
                    setPadding(0, dp(2), 0, 0)
                    tag = "${tab.id}_label"
                })
                addView(btn)
            }
        }
    }

    private fun onBottomTab(tab: String) {
        // Update icon colors
        listOf("view","annotate","pages","secure","more").forEach { t ->
            val ic = bottomNavBar.findViewWithTag<ImageView>("${t}_icon")
            val lv = bottomNavBar.findViewWithTag<TextView>("${t}_label")
            val active = (t == tab)
            ic?.setColorFilter(if (active) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
            lv?.setTextColor(if (active) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
        }
        annotToolbar.visibility = if (tab == "annotate") View.VISIBLE else View.GONE
        if (tab != "annotate") {
            sizeBar.visibility = View.GONE
            activeTool = AnnotationCanvasView.TOOL_NONE
            canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, activeColor) }
        }
        when (tab) {
            "pages"  -> showPagesMenu()
            "secure" -> showSecureMenu()
            "more"   -> showMoreMenu()
        }
    }

    // ---- Load PDF -------------------------------------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri = getUri()
                if (uri == null) { showError("No PDF to open"); return@launch }
                val file = withContext(Dispatchers.IO) {
                    FileHelper.uriToFile(this@ViewerActivity, uri)
                }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot read PDF file. Try opening again."); return@launch
                }
                pdfFile = file
                tvInfo.text = file.name
                val ok = tryAndroidRenderer(file)
                if (!ok) {
                    pageContainer.removeAllViews(); canvases.clear(); pageScales.clear()
                    tryPdfBoxRenderer(file)
                }
            } catch (e: Exception) {
                showError("Open failed: ${e.message ?: "Unknown error"}")
            }
        }
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

    private suspend fun tryAndroidRenderer(file: File): Boolean = try {
        withContext(Dispatchers.IO) {
            val fd   = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw   = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                var scale = 1f
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    scale  = sw.toFloat() / p.width.toFloat()
                    val bh = (p.height * scale).toInt().coerceAtLeast(1)
                    val b  = Bitmap.createBitmap(sw, bh, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                pageScales[i] = scale
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                tvPageCounter.text = "1/$totalPages"
            }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying PDFBox..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File) = withContext(Dispatchers.IO) {
        try {
            val doc  = PDDocument.load(file); val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"; progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw   = rndr.renderImageWithDPI(i, 150f)
                val scale = sw.toFloat() / raw.width.toFloat()
                pageScales[i] = scale
                val sc2 = Bitmap.createScaledBitmap(raw, sw, (raw.height * scale).toInt().coerceAtLeast(1), true)
                if (sc2 !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(sc2, i) }
            }
            doc.close()
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                tvPageCounter.text = "1/$totalPages"
            }
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
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp); layoutParams = FrameLayout.LayoutParams(-1, -2); adjustViewBounds = true
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
    }

    // ---- Navigation ----------------------------------------------------

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
        val t = page.coerceIn(0, totalPages - 1)
        var cum = 0
        for (i in 0 until t) cum += pageContainer.getChildAt(i)?.height ?: 0
        scrollView.smoothScrollTo(0, cum)
    }

    private fun showGotoDialog() {
        val et = EditText(this).apply {
            hint = "Page (1-$totalPages)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et)
            .setPositiveButton("Go") { _, _ ->
                et.text.toString().toIntOrNull()?.let { n ->
                    if (n in 1..totalPages) goToPage(n - 1) else toast("Enter 1-$totalPages")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFindDialog() {
        val et = EditText(this).apply { hint = "Search text..."; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        AlertDialog.Builder(this).setTitle("Find Text").setView(et)
            .setPositiveButton("Search") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) searchPdf(q) else toast("Enter search text")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun searchPdf(query: String) {
        val file = pdfFile ?: return; progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val doc      = PDDocument.load(file)
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().also { it.sortByPosition = true }
                    val found    = mutableListOf<Pair<Int, String>>()
                    for (i in 1..doc.numberOfPages) {
                        stripper.startPage = i; stripper.endPage = i
                        val t = stripper.getText(doc)
                        if (t.contains(query, ignoreCase = true)) {
                            found.add(i to t.lines().filter { it.contains(query, ignoreCase = true) }
                                .take(2).joinToString(" | ").take(80))
                        }
                    }
                    doc.close(); found
                } catch (_: Exception) { emptyList() }
            }
            progressBar.visibility = View.GONE
            if (results.isEmpty()) { toast("\"$query\" not found"); return@launch }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Found on ${results.size} page(s)")
                .setItems(results.map { (p, e) -> "Page $p: $e" }.toTypedArray()) { _, i ->
                    goToPage(results[i].first - 1)
                }.setNegativeButton("Close", null).show()
        }
    }

    // ---- Annotation tools ----------------------------------------------

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

    private fun highlightAnnotBtn(id: String) {
        for (i in 0 until annotToolbar.childCount) {
            val b = annotToolbar.getChildAt(i) as? Button ?: continue
            b.setBackgroundColor(if (b.tag == id) Color.parseColor("#16213E") else Color.TRANSPARENT)
        }
    }

    private fun showTextInputDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply {
            hint = "Type note (any language - Tamil, Hindi, etc)"; textSize = 15f; maxLines = 4
        }
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val tvSz = TextView(this).apply {
            text = "Size: ${(textSizePx/3).toInt()}"; textSize = 12f; minWidth = dp(72)
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
            .setMessage("Type text then tap Place, then tap on the page")
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
                toast("Tap page to place the text")
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
                setBackgroundColor(Color.parseColor(hex)); elevation = dp(2).toFloat()
                setOnClickListener { onPick(Color.parseColor(hex)); dlg?.dismiss(); toast(colorNames[i]) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Choose Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ---- Dark mode -----------------------------------------------------

    private fun toggleDark() {
        isDark = !isDark
        val filter = if (isDark) ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f
        ))) else null
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            (frame.getChildAt(0) as? ImageView)?.colorFilter = filter
        }
        scrollView.setBackgroundColor(if (isDark) Color.BLACK else Color.parseColor("#303030"))
        toast(if (isDark) "Dark mode ON" else "Light mode ON")
    }

    private fun applyDarkFilter(iv: ImageView) {
        iv.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f
        )))
    }

    // ---- Pages menu ----------------------------------------------------

    private fun showPagesMenu() {
        AlertDialog.Builder(this).setTitle("Page Actions")
            .setItems(arrayOf(
                "Extract page range...",
                "Delete current page (${currentPage+1})",
                "Rotate current page 90 deg",
                "Go to page..."
            )) { _, which ->
                val file = pdfFile ?: return@setItems
                when (which) {
                    0 -> doExtractPages(file)
                    1 -> doDeletePage(file, currentPage + 1)
                    2 -> doRotatePage(file, currentPage + 1)
                    3 -> showGotoDialog()
                }
            }.setNegativeButton("Close", null).show()
    }

    // ---- Secure menu ---------------------------------------------------

    private fun showSecureMenu() {
        val file = pdfFile ?: run { toast("Open a PDF first"); return }
        AlertDialog.Builder(this).setTitle("Security")
            .setItems(arrayOf("Password protect...", "Remove password...", "Add watermark...")) { _, which ->
                when (which) {
                    0 -> doPasswordProtect(file)
                    1 -> doRemovePassword(file)
                    2 -> doAddWatermark(file)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun doPasswordProtect(file: File) {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et1 = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint = "Confirm"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Protect") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank() || pw != et2.text.toString()) { toast("Passwords must match"); return@setPositiveButton }
                val out = FileHelper.tempFile(this, file.nameWithoutExtension + "_protected")
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.encryptPdf(file, out, pw, pw).fold(
                        onSuccess = { f -> progressBar.visibility = View.GONE; saveAndNotify(f, "${file.nameWithoutExtension}_protected") },
                        onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRemovePassword(file: File) {
        val et = EditText(this).apply { hint = "Current password"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Remove") { _, _ ->
                val out = FileHelper.tempFile(this, file.nameWithoutExtension + "_unlocked")
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.removePdfPassword(file, out, et.text.toString()).fold(
                        onSuccess = { f -> progressBar.visibility = View.GONE; saveAndNotify(f, "${file.nameWithoutExtension}_unlocked") },
                        onFailure = { progressBar.visibility = View.GONE; showError("Wrong password") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doAddWatermark(file: File) {
        val et = EditText(this).apply { setText("CONFIDENTIAL"); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val text = et.text.toString().ifBlank { "CONFIDENTIAL" }
                val out  = FileHelper.tempFile(this, file.nameWithoutExtension + "_watermarked")
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.addTextWatermark(file, out, text).fold(
                        onSuccess = { f -> progressBar.visibility = View.GONE; saveAndNotify(f, "${file.nameWithoutExtension}_watermarked") },
                        onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- More menu (Print, Export, Page Size) --------------------------

    private fun showMoreMenu() {
        AlertDialog.Builder(this).setTitle("More Options")
            .setItems(arrayOf(
                "Print PDF",
                "Export / Share PDF",
                "Page size & orientation...",
                "Compress this PDF",
                "Add page numbers",
                "About"
            )) { _, which ->
                val file = pdfFile
                when (which) {
                    0 -> printPdf()
                    1 -> sharePdf()
                    2 -> showPageSizeDialog()
                    3 -> file?.let { doCompressQuick(it) } ?: toast("Open a PDF first")
                    4 -> file?.let { doAddPageNumbers(it) } ?: toast("Open a PDF first")
                    5 -> showAbout()
                }
            }.setNegativeButton("Close", null).show()
    }

    // ---- Print PDF -----------------------------------------------------

    private fun printPdf() {
        val file = pdfFile ?: run { toast("No PDF loaded"); return }
        val printManager = getSystemService(PRINT_SERVICE) as? PrintManager ?: run {
            toast("Print service not available"); return
        }
        val jobName = "${file.nameWithoutExtension}_print"
        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttr: PrintAttributes?, newAttr: PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback, extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled(); return
                }
                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(totalPages)
                    .build()
                callback.onLayoutFinished(info, true)
            }
            override fun onWrite(
                pageRanges: Array<out PageRange>?,
                destination: android.os.ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    destination?.let { dest ->
                        file.inputStream().use { input ->
                            FileOutputStream(dest.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
        toast("Opening print dialog...")
    }

    // ---- Page Size & Orientation dialog --------------------------------

    private fun showPageSizeDialog() {
        val file = pdfFile ?: run { toast("Open a PDF first"); return }
        val sizes  = arrayOf("A4 (210x297mm)", "A3 (297x420mm)", "A5 (148x210mm)",
            "Letter (216x279mm)", "Legal (216x356mm)", "Custom...")
        var selectedSize = 0
        var isLandscape  = false

        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        lay.addView(TextView(this).apply { text = "Page Size:"; textSize = 13f; setPadding(0,0,0,dp(6)) })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ViewerActivity, android.R.layout.simple_spinner_item, sizes).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedSize = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        lay.addView(spinner)
        lay.addView(TextView(this).apply { text = "Orientation:"; textSize = 13f; setPadding(0,dp(12),0,dp(6)) })
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPort = RadioButton(this).apply { text = "Portrait"; isChecked = true }
        val rbLand = RadioButton(this).apply { text = "Landscape" }
        rg.addView(rbPort); rg.addView(rbLand)
        rg.setOnCheckedChangeListener { _, id -> isLandscape = (id == rbLand.id) }
        lay.addView(rg)
        lay.addView(TextView(this).apply {
            text = "Note: This exports a new PDF with the selected page size applied."; textSize = 11f
            setTextColor(Color.parseColor("#888888")); setPadding(0, dp(12), 0, 0)
        })

        AlertDialog.Builder(this).setTitle("Page Size & Orientation")
            .setView(lay)
            .setPositiveButton("Export") { _, _ ->
                val (w, h) = pageSizeDimensions(selectedSize, isLandscape)
                doExportWithPageSize(file, w, h, sizes[selectedSize].substringBefore(" "))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pageSizeDimensions(sizeIdx: Int, landscape: Boolean): Pair<Float, Float> {
        // Returns dimensions in PDF points (1 pt = 1/72 inch)
        val mm = { mmVal: Float -> mmVal * 2.8346f }
        val (w, h) = when (sizeIdx) {
            0 -> Pair(mm(210f), mm(297f))   // A4
            1 -> Pair(mm(297f), mm(420f))   // A3
            2 -> Pair(mm(148f), mm(210f))   // A5
            3 -> Pair(mm(216f), mm(279f))   // Letter
            4 -> Pair(mm(216f), mm(356f))   // Legal
            else -> Pair(mm(210f), mm(297f)) // Default A4
        }
        return if (landscape) Pair(h, w) else Pair(w, h)
    }

    private fun doExportWithPageSize(file: File, pageW: Float, pageH: Float, sizeName: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outName = "${file.nameWithoutExtension}_${sizeName}"
            val tmp = FileHelper.tempFile(this@ViewerActivity, outName)
            pdfOps.reshapePageSize(file, tmp, pageW, pageH).fold(
                onSuccess = { f ->
                    progressBar.visibility = View.GONE
                    saveAndNotify(f, outName)
                },
                onFailure = {
                    progressBar.visibility = View.GONE
                    showError("Export failed: ${it.message}")
                }
            )
        }
    }

    private fun doCompressQuick(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension + "_compressed")
            pdfOps.compressPdf(file, out, 9).fold(
                onSuccess = { f ->
                    progressBar.visibility = View.GONE
                    saveAndNotify(f, "${file.nameWithoutExtension}_compressed")
                },
                onFailure = { progressBar.visibility = View.GONE; showError("Compress failed: ${it.message}") }
            )
        }
    }

    private fun doAddPageNumbers(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension + "_numbered")
            pdfOps.addPageNumbers(file, out).fold(
                onSuccess = { f ->
                    progressBar.visibility = View.GONE
                    saveAndNotify(f, "${file.nameWithoutExtension}_numbered")
                },
                onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("ProPDF Editor")
            .setMessage("Version 3.0\nFree PDF Editor -- All Premium Features\nNo ads, no watermarks, no subscriptions.\n\nFeatures:\n- PDF Viewer + Dark Mode\n- Freehand Draw, Highlight, Text Notes\n- Merge, Split, Compress, Extract\n- Password Protection\n- Watermark\n- Scan to PDF\n- Print & Export\n- Page Size Conversion\n- Text Extraction (multilingual)")
            .setPositiveButton("OK", null).show()
    }

    // ---- Page operations -----------------------------------------------

    private fun doExtractPages(file: File) {
        val et = EditText(this).apply { hint = "Range e.g. 2-5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages").setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val parts = et.text.toString().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                if (from > to) { toast("Invalid range"); return@setPositiveButton }
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.splitPdf(file, cacheDir, listOf(from..to)).fold(
                        onSuccess = { files ->
                            progressBar.visibility = View.GONE
                            files.firstOrNull()?.let { saveAndNotify(it, "${file.nameWithoutExtension}_pages${from}_$to") }
                        },
                        onFailure = { progressBar.visibility = View.GONE; showError("Extract failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePage(file: File, pageNum: Int) {
        AlertDialog.Builder(this).setTitle("Delete Page $pageNum?")
            .setPositiveButton("Delete") { _, _ ->
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    val out = FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension + "_deleted")
                    pdfOps.deletePages(file, out, listOf(pageNum)).fold(
                        onSuccess = { f -> progressBar.visibility = View.GONE; saveAndNotify(f, "${file.nameWithoutExtension}_deleted") },
                        onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRotatePage(file: File, pageNum: Int) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension + "_rotated")
            pdfOps.rotatePages(file, out, mapOf(pageNum to 90)).fold(
                onSuccess = { f -> progressBar.visibility = View.GONE; saveAndNotify(f, "${file.nameWithoutExtension}_rotated") },
                onFailure = { progressBar.visibility = View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    // ---- Text extraction -----------------------------------------------

    private fun extractText() {
        val file = pdfFile ?: run { toast("No PDF"); return }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc = PDDocument.load(file)
                    val s   = com.tom_roush.pdfbox.text.PDFTextStripper().also { it.sortByPosition = true }
                    val t   = s.getText(doc); doc.close(); t.trim().ifBlank { null }
                } catch (_: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                AlertDialog.Builder(this@ViewerActivity).setTitle("No Text Found")
                    .setMessage("This PDF has no selectable text.\n\nTamil/Indic scripts may not extract correctly from non-Unicode PDFs -- this is a PDF standard limitation, not a bug.").setPositiveButton("OK", null).show()
                return@launch
            }
            val et = EditText(this@ViewerActivity).apply {
                setText(text); textSize = 12f; setTextIsSelectable(true)
                isFocusable = true; isFocusableInTouchMode = true; background = null
            }
            AlertDialog.Builder(this@ViewerActivity).setTitle("Extracted Text")
                .setView(ScrollView(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(-1,dp(380)); addView(et) })
                .setPositiveButton("Copy All") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                    toast("Copied!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, "Share Text"))
                }.setNegativeButton("Close", null).show()
        }
    }

    // ---- Save annotations ----------------------------------------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file"); return }
        if (canvases.values.none { it.hasAnnotations() }) {
            toast("No annotations -- draw something first"); return
        }
        val et = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20),dp(8),dp(20),dp(8))
        }
        AlertDialog.Builder(this).setTitle("Save Annotated PDF").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifBlank { file.nameWithoutExtension + "_annotated" }.removeSuffix(".pdf")
                doSaveAnnotations(file, name)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doSaveAnnotations(file: File, outName: String) {
        progressBar.visibility = View.VISIBLE; tvInfo.text = "Saving..."
        lifecycleScope.launch {
            try {
                val pd = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val td = mutableMapOf<Int, Pair<List<AnnotationCanvasView.TextAnnot>, Float>>()
                for ((idx, cvs) in canvases) {
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
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        saveAndNotify(named, outName)
                    },
                    onFailure = { progressBar.visibility = View.GONE; tvInfo.text = file.name; showError("Save failed: ${it.message}") }
                )
            } catch (e: Exception) {
                progressBar.visibility = View.GONE; tvInfo.text = file.name; showError("Error: ${e.message}")
            }
        }
    }

    // ---- Helper: save to downloads and show dialog ---------------------

    private suspend fun saveAndNotify(file: File, desiredName: String) {
        val saved = withContext(Dispatchers.IO) {
            try { FileHelper.saveToDownloads(this@ViewerActivity, file) }
            catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(file), file) }
        }
        AlertDialog.Builder(this@ViewerActivity).setTitle("Saved!")
            .setMessage("$desiredName.pdf\n\n${saved.displayPath}")
            .setPositiveButton("OK", null).show()
    }

    // ---- Share ---------------------------------------------------------

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

    // ---- Helpers -------------------------------------------------------

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C")); tvInfo.text = "Error"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun makeImgBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { action() }
    }

    private fun makeTextBtn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f
        setTextColor(Color.parseColor("#AADDFF")); setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(7), 0, dp(7), 0); setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
