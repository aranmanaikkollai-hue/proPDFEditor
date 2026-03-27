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

    private var pdfFile    : File? = null
    private var totalPages = 0
    private var activeTool = AnnotationCanvasView.TOOL_NONE
    private var activeColor = Color.parseColor("#E53935")
    private var strokeWidth = 14f
    private var textSizePx  = 44f
    private var barShown    = false
    private var isDark      = false
    private var currentPage = 0

    private val canvases = mutableMapOf<Int, AnnotationCanvasView>()

    // Colors: 16-color expanded palette
    private val colorHex = listOf(
        "#E53935","#1A73E8","#2E7D32","#F57C00",
        "#9C27B0","#000000","#FFFFFF","#FFC107",
        "#795548","#00ACC1","#D81B60","#43A047",
        "#3949AB","#FF7043","#8D6E63","#546E7A"
    )
    private val colorNames = listOf(
        "Red","Blue","Green","Orange","Purple","Black","White","Yellow",
        "Brown","Cyan","Pink","Dark Green","Indigo","Deep Orange","Taupe","Steel Blue"
    )

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotBar      : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvSizeLabel   : TextView
    private lateinit var fabAnnotate   : FloatingToolButton
    private lateinit var navBar        : LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ---- Build UI -------------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#424242"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(4), 0, dp(4), 0); gravity = Gravity.CENTER_VERTICAL
        }
        val btnBack = makeIconBtn(android.R.drawable.ic_menu_close_clear_cancel, "Back") { finish() }
        tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.WHITE); textSize = 12f; text = "Loading..."
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val btnFind  = makeTextBtn("Find")  { showFindDialog() }
        val btnGoto  = makeTextBtn("Go")    { showGotoDialog() }
        val btnNight = makeTextBtn("Mode")  { toggleDarkMode() }
        val btnShare = makeIconBtn(android.R.drawable.ic_menu_share, "Share") { sharePdf() }

        topBar.addView(btnBack); topBar.addView(tvInfo)
        topBar.addView(btnFind); topBar.addView(btnGoto)
        topBar.addView(btnNight); topBar.addView(btnShare)

        progressBar = ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(3)) }

        // Zoom controls row
        val zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#222222"))
            setPadding(dp(8), dp(4), dp(8), dp(4)); gravity = Gravity.CENTER_VERTICAL
        }
        val btnTop  = makeTextBtn("Top")   { scrollView.smoothScrollTo(0, 0) }
        val btnPrev = makeTextBtn("< Prev"){ goToPage(currentPage - 1) }
        val tvPageNum = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = Gravity.CENTER; setTextColor(Color.parseColor("#AAAAAA")); textSize = 11f
            text = "1 / --"
        }
        val btnNext = makeTextBtn("Next >") { goToPage(currentPage + 1) }
        val btnBot  = makeTextBtn("Bottom") {
            scrollView.post { scrollView.smoothScrollTo(0, scrollView.getChildAt(0).height) }
        }
        zoomBar.addView(btnTop); zoomBar.addView(btnPrev)
        zoomBar.addView(tvPageNum); zoomBar.addView(btnNext); zoomBar.addView(btnBot)

        // Update page number label on scroll
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCurrentPageFromScroll(scrollY, tvPageNum)
            }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        sizeBar = buildSizeBar()
        annotBar = buildAnnotBar()

        fabAnnotate = FloatingToolButton(this).also { fab ->
            fab.setOnClickListener { toggleAnnotBar() }
        }
        val fabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(2), dp(12), dp(2))
            addView(fabAnnotate)
        }

        navBar = zoomBar

        root.addView(topBar); root.addView(progressBar); root.addView(navBar)
        root.addView(scrollView); root.addView(sizeBar)
        root.addView(annotBar); root.addView(fabRow)
        setContentView(root)
    }

    private fun buildSizeBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
        }
        tvSizeLabel = TextView(this).apply {
            text = "Size: 14"; textSize = 12f; setTextColor(0xFF333333.toInt()); minWidth = dp(72)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f); max = 100; progress = 14
        }
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val w = (v + 4).toFloat()
                strokeWidth = w; textSizePx = w * 3f; tvSizeLabel.text = "Size: $v"
                canvases.values.forEach { it.setStrokeWidth(w); it.setTextSize(w * 3f) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        bar.addView(tvSizeLabel); bar.addView(seekStroke); return bar
    }

    private fun buildAnnotBar(): LinearLayout {
        data class T(val id: String, val label: String, val colorHex: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,  "Pen",    "#1A73E8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT, "Hi-Lite","#F57C00"),
            T(AnnotationCanvasView.TOOL_TEXT,      "Text+",  "#2E7D32"),
            T(AnnotationCanvasView.TOOL_RECT,      "Box",    "#7B1FA2"),
            T(AnnotationCanvasView.TOOL_ERASER,    "Erase",  "#757575"),
            T(AnnotationCanvasView.TOOL_MOVE_TEXT, "Move",   "#0097A7"),
            T("color",                             "Color",  "#E91E63"),
            T("undo",                              "Undo",   "#546E7A"),
            T("redo",                              "Redo",   "#546E7A"),
            T("save",                              "Save",   "#2E7D32"),
            T("extract",                           "Copy",   "#1565C0"),
            T("close",                             "Close",  "#B71C1C")
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(2), dp(2), dp(2), dp(2)); elevation = dp(12).toFloat()
            visibility = View.GONE
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.label; textSize = 9f; tag = t.id
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                    setTextColor(Color.parseColor(t.colorHex))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { onToolTap(t.id, Color.parseColor(t.colorHex)) }
                })
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
                    pageContainer.removeAllViews(); canvases.clear(); tryPdfBoxRenderer(file)
                }
            } catch (e: Exception) { showError("Error: ${e.message}") }
        }
    }

    private fun getUri(): Uri? {
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        val path   = intent.getStringExtra(EXTRA_PDF_PATH)
        return when {
            path   != null -> Uri.fromFile(File(path))
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
            val sw   = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    val sc = sw.toFloat() / p.width
                    val b  = Bitmap.createBitmap(sw, (p.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                withContext(Dispatchers.Main) { addPage(bmp, i, file.name) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying PDFBox renderer..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File) = withContext(Dispatchers.IO) {
        try {
            val doc  = PDDocument.load(file); val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"; progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw = rndr.renderImageWithDPI(i, 150f)
                val sc  = sw.toFloat() / raw.width
                val sc2 = Bitmap.createScaledBitmap(raw, sw, (raw.height * sc).toInt().coerceAtLeast(1), true)
                if (sc2 !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(sc2, i, file.name) }
            }
            doc.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { showError("Cannot open: ${e.message}") }
        }
    }

    private fun addPage(bmp: Bitmap, idx: Int, docName: String) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(4), dp(3), dp(4), dp(3))
            }
            tag = "page_$idx"
            setBackgroundColor(Color.WHITE)
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp); layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
        }
        val tvPageNum = TextView(this).apply {
            text = "${idx + 1}"; textSize = 9f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(5), dp(2), dp(5), dp(2))
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(4), dp(4), 0)
            }
        }
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTool(AnnotationCanvasView.TOOL_NONE, activeColor)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs); frame.addView(tvPageNum)
        pageContainer.addView(frame)
        if (isDark) applyDarkFilter(iv)
    }

    // ---- Navigation ---------------------------------------------------

    private fun updateCurrentPageFromScroll(scrollY: Int, tvPageNum: TextView) {
        var cumH = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < cumH + child.height) {
                currentPage = i
                tvPageNum.text = "${i + 1} / $totalPages"
                return
            }
            cumH += child.height
        }
    }

    private fun goToPage(page: Int) {
        val target = page.coerceIn(0, totalPages - 1)
        var cumH = 0
        for (i in 0 until target) {
            cumH += pageContainer.getChildAt(i)?.height ?: 0
        }
        scrollView.smoothScrollTo(0, cumH)
    }

    private fun showGotoDialog() {
        val et = EditText(this).apply {
            hint = "Page number (1-$totalPages)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n != null && n in 1..totalPages) goToPage(n - 1)
                else toast("Enter a page number between 1 and $totalPages")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFindDialog() {
        val et = EditText(this).apply {
            hint = "Search text in PDF..."
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Find Text")
            .setMessage("Searches extracted text content:")
            .setView(et)
            .setPositiveButton("Search") { _, _ ->
                val query = et.text.toString().trim()
                if (query.isNotEmpty()) searchPdf(query)
                else toast("Enter search text")
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
                        val text = stripper.getText(doc)
                        if (text.contains(query, ignoreCase = true)) {
                            val lines = text.lines().filter {
                                it.contains(query, ignoreCase = true)
                            }
                            found.add(Pair(i, lines.joinToString("\n")))
                        }
                    }
                    doc.close(); found
                } catch (e: Exception) { emptyList() }
            }
            progressBar.visibility = View.GONE
            if (results.isEmpty()) {
                toast("\"$query\" not found in this PDF")
                return@launch
            }
            // Show results list
            val items = results.map { (page, excerpt) ->
                "Page $page:\n${excerpt.take(100)}..."
            }.toTypedArray()
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Found on ${results.size} page(s)")
                .setItems(items) { _, which ->
                    goToPage(results[which].first - 1)
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- Dark mode ---------------------------------------------------

    private fun toggleDarkMode() {
        isDark = !isDark
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val iv    = frame.getChildAt(0) as? ImageView ?: continue
            if (isDark) applyDarkFilter(iv) else iv.colorFilter = null
        }
        scrollView.setBackgroundColor(if (isDark) Color.BLACK else Color.parseColor("#424242"))
        toast(if (isDark) "Dark mode ON" else "Light mode ON")
    }

    private fun applyDarkFilter(iv: ImageView) {
        iv.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    // ---- Tool handling -----------------------------------------------

    private fun onToolTap(id: String, color: Int) {
        highlightActiveBtn(id)
        when (id) {
            "undo"    -> { canvases.values.forEach { it.undo() }; toast("Undone") }
            "redo"    -> { canvases.values.forEach { it.redo() }; toast("Redone") }
            "color"   -> showColorPicker()
            "extract" -> extractAndCopyText()
            "save"    -> saveWithAnnotations()
            "close"   -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, activeColor) }
                sizeBar.visibility = View.GONE; toggleAnnotBar()
            }
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
                val hint = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "Pen active - draw on page"
                    AnnotationCanvasView.TOOL_HIGHLIGHT -> "Highlight active - drag over text"
                    AnnotationCanvasView.TOOL_RECT      -> "Box active - drag to draw"
                    AnnotationCanvasView.TOOL_ERASER    -> "Eraser active - drag to erase"
                    AnnotationCanvasView.TOOL_MOVE_TEXT -> "Move Text - drag a text note"
                    else -> id
                }
                toast(hint)
            }
        }
    }

    private fun highlightActiveBtn(activeId: String) {
        for (i in 0 until annotBar.childCount) {
            val btn = annotBar.getChildAt(i) as? Button ?: continue
            btn.setBackgroundColor(
                if (btn.tag == activeId) Color.parseColor("#E3F2FD") else Color.TRANSPARENT
            )
        }
    }

    // ---- Text annotation dialog (type first, then tap to place) -----

    private fun showTextInputDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply {
            hint = "Type annotation (supports all languages)"
            textSize = 15f; maxLines = 4
            // Enable IME for better multilingual keyboard support
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }

        // Size slider
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val tvSz = TextView(this).apply { text = "Size: ${(textSizePx/3).toInt()}"; textSize = 12f; minWidth = dp(65) }
        val sk   = SeekBar(this).apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f); max=80; progress=(textSizePx/3).toInt() }
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val sz = ((v+4)*3).toFloat(); textSizePx = sz; tvSz.text = "Size: $v"
                canvases.values.forEach { it.setTextSize(sz) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sRow.addView(tvSz); sRow.addView(sk)

        // Color swatch
        val cRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        cRow.addView(TextView(this).apply { text = "Color: "; textSize = 12f; setPadding(0,0,dp(8),0) })
        var textColor = activeColor
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundColor(textColor)
            setOnClickListener { pickColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        cRow.addView(swatch)
        lay.addView(et); lay.addView(sRow); lay.addView(cRow)

        AlertDialog.Builder(this)
            .setTitle("Add Text Annotation")
            .setMessage("Type text, then tap on the page to place it.\nSupports Tamil, Hindi, Arabic and all languages.")
            .setView(lay)
            .setPositiveButton("Ready") { _, _ ->
                val txt = et.text.toString()
                if (txt.isEmpty()) { toast("Type text first"); return@setPositiveButton }
                activeTool = AnnotationCanvasView.TOOL_TEXT; activeColor = textColor
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    cv.setTextSize(textSizePx); cv.setPendingText(txt)
                }
                sizeBar.visibility = View.GONE
                toast("Tap on the page where you want the text")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Color picker (16 colors) ------------------------------------

    private fun showColorPicker() { pickColor { c -> activeColor = c; canvases.values.forEach { it.setColor(c) } } }

    private fun pickColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = 4; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        var dlg: AlertDialog? = null
        colorHex.forEachIndexed { i, hex ->
            val clr = Color.parseColor(hex)
            grid.addView(FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(52); height = dp(52); setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                setBackgroundColor(clr)
                elevation = dp(2).toFloat()
                // White border around white swatch
                if (hex == "#FFFFFF") setBackgroundColor(Color.parseColor("#EEEEEE"))
                setOnClickListener { onPick(clr); dlg?.dismiss(); toast(colorNames[i]) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Choose Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ---- Text extraction + copy (multilingual) -----------------------

    private fun extractAndCopyText() {
        val file = pdfFile ?: run { toast("No PDF loaded"); return }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc      = PDDocument.load(file)
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.sortByPosition = true
                    // Extract all pages
                    val extracted = stripper.getText(doc)
                    doc.close()
                    extracted.trim().ifBlank { null }
                } catch (e: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                AlertDialog.Builder(this@ViewerActivity)
                    .setTitle("No Text Found")
                    .setMessage(
                        "This PDF has no selectable text.\n\n" +
                        "This is likely a scanned image PDF.\n" +
                        "Note: Tamil and Indic script text in PDFs may not extract correctly " +
                        "if the PDF uses non-Unicode font encoding."
                    )
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            // Show in scrollable, selectable field
            val et = EditText(this@ViewerActivity).apply {
                setText(text); textSize = 12f
                setTextIsSelectable(true)
                isFocusable = true; isFocusableInTouchMode = true
                background = null
                setTextColor(Color.parseColor("#111111"))
            }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Extracted Text (select to copy)")
                .setView(ScrollView(this@ViewerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(400)); addView(et)
                })
                .setPositiveButton("Copy All") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                    toast("Copied to clipboard!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                        "Share Text"
                    ))
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- Save --------------------------------------------------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }
        if (canvases.values.none { it.hasAnnotations() }) {
            toast("No annotations -- draw something first"); return
        }
        val et = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Save Annotated PDF")
            .setMessage("File will be saved to Downloads:").setView(et)
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
                    cvs.getStrokes().takeIf { it.isNotEmpty() }?.let { pd[idx] = Pair(it, 1f) }
                    cvs.getTextAnnots().takeIf { it.isNotEmpty() }?.let { td[idx] = Pair(it, 1f) }
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
                            .setTitle("Saved to Downloads!")
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

    // ---- Helpers -----------------------------------------------------

    private fun toggleAnnotBar() {
        barShown = !barShown
        annotBar.visibility = if (barShown) View.VISIBLE else View.GONE
        if (!barShown) sizeBar.visibility = View.GONE
        fabAnnotate.setActive(barShown)
    }

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

    private fun makeIconBtn(icon: Int, desc: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.WHITE); contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { action() }
    }

    private fun makeTextBtn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f
        setTextColor(Color.parseColor("#AADDFF")); setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

// Simple FAB-like button using ImageButton
class FloatingToolButton(context: Context) : ImageButton(context) {
    init {
        setImageResource(android.R.drawable.ic_menu_edit)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1A73E8"))
        setPadding(
            (14 * resources.displayMetrics.density).toInt(),
            (14 * resources.displayMetrics.density).toInt(),
            (14 * resources.displayMetrics.density).toInt(),
            (14 * resources.displayMetrics.density).toInt()
        )
        elevation = (8 * resources.displayMetrics.density)
    }
    fun setActive(active: Boolean) {
        setImageResource(
            if (active) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }
}
