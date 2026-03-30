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
    private var pdfPassword : String? = null
    private var totalPages  = 0
    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    // FIX: Highlight default = neon yellow #FFFF00
    private var activeColor = AnnotationCanvasView.HIGHLIGHT_DEFAULT_COLOR
    private var strokeWidth = 14f
    private var textSizePx  = 44f
    private var isDark      = false
    private var isSepia     = false
    private var currentPage = 0

    private val pageScales  = mutableMapOf<Int, Float>()
    private val canvases    = mutableMapOf<Int, AnnotationCanvasView>()

    // 16 colors - yellow first as default for highlight
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
        "White","Steel","Deep Orange","Cyan"
    )

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotToolbar  : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvPageCounter : TextView
    private lateinit var bottomNavBar  : LinearLayout
    private lateinit var rootLayout    : LinearLayout

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
        topBar.addView(makeTextBtn("Find") { showFindDialog() })
        topBar.addView(makeTextBtn("Go")   { showGotoDialog() })
        topBar.addView(makeTextBtn("Mode") { showReadingModeMenu() })
        topBar.addView(makeImgBtn(android.R.drawable.ic_menu_share) { sharePdf() })

        // Nav bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(38))
            setBackgroundColor(Color.parseColor("#212121"))
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

        annotToolbar = buildAnnotToolbar()
        bottomNavBar = buildBottomNavBar()

        rootLayout.addView(topBar); rootLayout.addView(navBar); rootLayout.addView(progressBar)
        rootLayout.addView(scrollView); rootLayout.addView(sizeBar)
        rootLayout.addView(annotToolbar); rootLayout.addView(bottomNavBar)
        setContentView(rootLayout)
    }

    private fun buildAnnotToolbar(): LinearLayout {
        data class T(val id: String, val lbl: String, val hex: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,  "Pen",    "#5C9EE8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT, "Hi-Lite","#FFFF00"),
            T(AnnotationCanvasView.TOOL_UNDERLINE, "Undrln", "#00BCD4"),
            T(AnnotationCanvasView.TOOL_STRIKEOUT, "Strike", "#F44336"),
            T(AnnotationCanvasView.TOOL_TEXT,      "Text",   "#66BB6A"),
            T(AnnotationCanvasView.TOOL_RECT,      "Box",    "#CE93D8"),
            T(AnnotationCanvasView.TOOL_CIRCLE,    "Circle", "#90CAF9"),
            T(AnnotationCanvasView.TOOL_ARROW,     "Arrow",  "#FFB74D"),
            T(AnnotationCanvasView.TOOL_STAMP,     "Stamp",  "#EF9A9A"),
            T(AnnotationCanvasView.TOOL_ERASER,    "Erase",  "#90A4AE"),
            T(AnnotationCanvasView.TOOL_MOVE_TEXT, "Move",   "#4DD0E1"),
            T("color",   "Color",  "#F48FB1"),
            T("undo",    "Undo",   "#AAAAAA"),
            T("redo",    "Redo",   "#AAAAAA"),
            T("save",    "Save",   "#A5D6A7"),
            T("extract", "CopyTxt","#90CAF9"),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(Color.parseColor("#0D1B2A"))
            visibility = View.GONE
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.lbl; textSize = 8f; tag = t.id
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setTextColor(Color.parseColor(t.hex))
                    setBackgroundColor(Color.TRANSPARENT); setPadding(0, 0, 0, 0)
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
        annotToolbar.visibility = if (tab == "annotate") View.VISIBLE else View.GONE
        if (tab != "annotate") {
            sizeBar.visibility = View.GONE; activeTool = AnnotationCanvasView.TOOL_NONE
            canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, activeColor) }
        }
        when (tab) {
            "pages"  -> showPagesMenu()
            "secure" -> showSecureMenu()
            "more"   -> showMoreMenu()
        }
    }

    // ---- Load PDF (with password support) --------------------------

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val uri = getUri() ?: run { showError("No PDF to open"); return@launch }
                val file = withContext(Dispatchers.IO) { FileHelper.uriToFile(this@ViewerActivity, uri) }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot read PDF. Try opening again."); return@launch
                }
                pdfFile = file; tvInfo.text = file.name

                // FIX: Check if PDF is password-protected before rendering
                val isEncrypted = withContext(Dispatchers.IO) { isPdfEncrypted(file) }
                if (isEncrypted) {
                    promptPasswordAndLoad(file); return@launch
                }
                renderPdf(file, null)
            } catch (e: Exception) {
                showError("Open failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun isPdfEncrypted(file: File): Boolean = try {
        val doc = PDDocument.load(file)
        val enc = doc.isEncrypted; doc.close(); enc
    } catch (_: Exception) { true } // if it fails to open, assume encrypted

    private fun promptPasswordAndLoad(file: File) {
        val et = EditText(this).apply {
            hint = "Enter PDF password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Password Protected PDF")
            .setMessage("This PDF is password protected. Enter the password to open it:")
            .setView(et)
            .setPositiveButton("Open") { _, _ ->
                val pw = et.text.toString()
                if (pw.isBlank()) { toast("Enter a password"); return@setPositiveButton }
                lifecycleScope.launch { renderPdf(file, pw) }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private suspend fun renderPdf(file: File, password: String?) {
        pdfPassword = password
        val ok = tryAndroidRenderer(file, password)
        if (!ok) {
            pageContainer.removeAllViews(); canvases.clear(); pageScales.clear()
            tryPdfBoxRenderer(file, password)
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

    private suspend fun tryAndroidRenderer(file: File, password: String?): Boolean = try {
        withContext(Dispatchers.IO) {
            val fd   = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw   = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"; progressBar.visibility = View.VISIBLE
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
                progressBar.visibility = View.GONE; tvPageCounter.text = "1/$totalPages"
            }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying PDFBox..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File, password: String?) = withContext(Dispatchers.IO) {
        try {
            val doc = if (password != null) {
                try { PDDocument.load(file, password) }
                catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showError("Incorrect password. Cannot open this PDF.")
                        // Re-prompt if wrong password
                        promptPasswordAndLoad(file)
                    }; return@withContext
                }
            } else { PDDocument.load(file) }

            val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages
            val sw = resources.displayMetrics.widthPixels
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
                progressBar.visibility = View.GONE; tvPageCounter.text = "1/$totalPages"
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { showError("Cannot open: ${e.message}") }
        }
    }

    private fun addPage(bmp: Bitmap, idx: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
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
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setTool(AnnotationCanvasView.TOOL_NONE, activeColor)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs); frame.addView(tvNum)
        pageContainer.addView(frame)
        applyReadingFilter(iv)
        if (idx == totalPages - 1) tvPageCounter.text = "1/$totalPages"
    }

    // ---- Navigation ---------------------------------------------------

    private fun updatePageCounter(scrollY: Int) {
        var cum = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < cum + child.height) { currentPage = i; tvPageCounter.text = "${i+1}/$totalPages"; return }
            cum += child.height
        }
    }

    private fun goToPage(page: Int) {
        val t = page.coerceIn(0, totalPages - 1); var cum = 0
        for (i in 0 until t) cum += pageContainer.getChildAt(i)?.height ?: 0
        scrollView.smoothScrollTo(0, cum)
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

    private fun showFindDialog() {
        val et = EditText(this).apply { hint = "Search..."; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Find Text").setView(et)
            .setPositiveButton("Search") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isNotEmpty()) searchPdf(q) else toast("Enter text")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun searchPdf(query: String) {
        val file = pdfFile ?: return; progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword) else PDDocument.load(file)
                    val s   = com.tom_roush.pdfbox.text.PDFTextStripper().also { it.sortByPosition = true }
                    val found = mutableListOf<Pair<Int,String>>()
                    for (i in 1..doc.numberOfPages) {
                        s.startPage = i; s.endPage = i
                        val t = s.getText(doc)
                        if (t.contains(query, ignoreCase = true))
                            found.add(i to t.lines().filter { it.contains(query, ignoreCase = true) }.take(2).joinToString(" | ").take(80))
                    }
                    doc.close(); found
                } catch (_: Exception) { emptyList() }
            }
            progressBar.visibility = View.GONE
            if (results.isEmpty()) { toast("\"$query\" not found"); return@launch }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Found on ${results.size} page(s)")
                .setItems(results.map { (p,e) -> "Page $p: $e" }.toTypedArray()) { _,i -> goToPage(results[i].first-1) }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- Reading modes ------------------------------------------------

    private fun showReadingModeMenu() {
        AlertDialog.Builder(this).setTitle("Reading Mode")
            .setItems(arrayOf("Normal", "Night (Dark)", "Sepia (Warm)", "High Contrast")) { _, which ->
                isDark  = false; isSepia = false
                when (which) { 1 -> isDark = true; 2 -> isSepia = true }
                applyReadingModeToAll()
                toast(arrayOf("Normal","Night mode","Sepia mode","High contrast")[which])
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
                0.393f,0.769f,0.189f,0f,0f,
                0.349f,0.686f,0.168f,0f,0f,
                0.272f,0.534f,0.131f,0f,0f,
                0f,0f,0f,1f,0f)))
            else    -> null
        }
    }

    // ---- Annotation tools ---------------------------------------------

    private fun onAnnotTool(id: String, color: Int) {
        highlightAnnotBtn(id)
        when (id) {
            "undo"    -> { canvases.values.forEach { it.undo() }; toast("Undone") }
            "redo"    -> { canvases.values.forEach { it.redo() }; toast("Redone") }
            "color"   -> pickColor { c -> activeColor = c; canvases.values.forEach { it.setColor(c) } }
            "extract" -> extractText()
            "save"    -> saveWithAnnotations()
            AnnotationCanvasView.TOOL_TEXT   -> showTextInputDialog()
            AnnotationCanvasView.TOOL_STAMP  -> showStampPicker()
            AnnotationCanvasView.TOOL_HIGHLIGHT -> {
                // FIX: Set default highlight color to neon yellow before activating
                activeTool  = id
                // Only reset to yellow if user hasn't explicitly picked another color for highlight
                val highlightColor = if (activeColor == Color.parseColor("#E53935") ||
                    activeColor == Color.parseColor("#1A73E8")) {
                    AnnotationCanvasView.HIGHLIGHT_DEFAULT_COLOR
                } else activeColor
                activeColor = highlightColor
                canvases.values.forEach { cv ->
                    cv.setTool(id, highlightColor); cv.setStrokeWidth(strokeWidth)
                }
                sizeBar.visibility = View.VISIBLE
                toast("Highlight active - drag to highlight text")
            }
            else -> {
                activeTool = id; activeColor = color
                canvases.values.forEach { cv ->
                    cv.setTool(id, color); cv.setStrokeWidth(strokeWidth); cv.setTextSize(textSizePx)
                }
                sizeBar.visibility = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND,
                    AnnotationCanvasView.TOOL_ERASER -> View.VISIBLE
                    else -> View.GONE
                }
                toast(when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "Draw on page"
                    AnnotationCanvasView.TOOL_UNDERLINE -> "Drag under text to underline"
                    AnnotationCanvasView.TOOL_STRIKEOUT -> "Drag across text to strike"
                    AnnotationCanvasView.TOOL_RECT      -> "Drag to draw rectangle"
                    AnnotationCanvasView.TOOL_CIRCLE    -> "Drag to draw circle"
                    AnnotationCanvasView.TOOL_ARROW     -> "Drag to draw arrow"
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

    private fun showStampPicker() {
        val stamps = arrayOf("APPROVED","REJECTED","DRAFT","CONFIDENTIAL","SIGN HERE","REVIEWED","VOID","FINAL")
        AlertDialog.Builder(this).setTitle("Choose Stamp")
            .setItems(stamps) { _, i ->
                activeTool = AnnotationCanvasView.TOOL_STAMP
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_STAMP, Color.parseColor("#D32F2F"))
                    cv.setPendingStamp(stamps[i])
                }
                toast("Tap page to place stamp: ${stamps[i]}")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTextInputDialog() {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et  = EditText(this).apply { hint = "Type note (any language - Tamil, Hindi, etc)"; textSize = 15f; maxLines = 4 }
        val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,dp(4)) }
        val tvSz = TextView(this).apply { text = "Size: ${(textSizePx/3).toInt()}"; textSize = 12f; minWidth = dp(72) }
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
        val cRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,0) }
        cRow.addView(TextView(this).apply { text = "Color: "; textSize = 12f; setPadding(0,0,dp(8),0) })
        var textColor = Color.BLACK
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40),dp(40)); setBackgroundColor(textColor)
            setOnClickListener { pickColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        cRow.addView(swatch); lay.addView(et); lay.addView(sRow); lay.addView(cRow)
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
                toast("Tap page to place the text")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = 4; setPadding(dp(12),dp(12),dp(12),dp(12)) }
        var dlg: AlertDialog? = null
        colorHex.forEachIndexed { i, hex ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply { width=dp(52); height=dp(52); setMargins(dp(5),dp(5),dp(5),dp(5)) }
                setBackgroundColor(Color.parseColor(hex)); elevation = dp(2).toFloat()
                setOnClickListener { onPick(Color.parseColor(hex)); dlg?.dismiss(); toast(colorNames[i]) }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Choose Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ---- Pages menu ---------------------------------------------------

    private fun showPagesMenu() {
        AlertDialog.Builder(this).setTitle("Page Actions")
            .setItems(arrayOf(
                "Extract page range...",
                "Delete current page (${currentPage+1})",
                "Rotate page ${currentPage+1}",
                "Add page numbers to entire PDF",
                "Go to page..."
            )) { _, which ->
                val file = pdfFile ?: return@setItems
                when (which) {
                    0 -> doExtractPages(file)
                    1 -> doDeletePage(file, currentPage+1)
                    2 -> doRotatePage(file, currentPage+1)
                    3 -> doAddPageNumbers(file)
                    4 -> showGotoDialog()
                }
            }.setNegativeButton("Close", null).show()
    }

    // ---- Secure menu --------------------------------------------------

    private fun showSecureMenu() {
        val file = pdfFile ?: run { toast("Open a PDF first"); return }
        AlertDialog.Builder(this).setTitle("Security")
            .setItems(arrayOf("Password protect...", "Remove password...", "Add watermark...", "Redact text...")) { _, which ->
                when (which) {
                    0 -> doPasswordProtect(file)
                    1 -> doRemovePassword(file)
                    2 -> doAddWatermark(file)
                    3 -> showRedactInfo()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun doPasswordProtect(file: File) {
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint="Confirm"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Protect") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank() || pw != et2.text.toString()) { toast("Passwords must match"); return@setPositiveButton }
                val out = FileHelper.tempFile(this, file.nameWithoutExtension+"_protected")
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.encryptPdf(file, out, pw, pw).fold(
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
                val out = FileHelper.tempFile(this, file.nameWithoutExtension+"_unlocked")
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.removePdfPassword(file, out, et.text.toString()).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_unlocked") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Incorrect password. Try again.") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doAddWatermark(file: File) {
        val et = EditText(this).apply { setText("CONFIDENTIAL"); setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val text = et.text.toString().ifBlank { "CONFIDENTIAL" }
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.addTextWatermark(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_watermarked"), text).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_watermarked") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showRedactInfo() {
        AlertDialog.Builder(this)
            .setTitle("Redaction")
            .setMessage("To redact text:\n\n1. Use the Annotate tab\n2. Select the Box tool\n3. Draw a black rectangle over sensitive text\n4. Save the annotated PDF\n\nThe black rectangle will permanently cover the text in the saved PDF.")
            .setPositiveButton("OK, Annotate Now") { _, _ -> onBottomTab("annotate") }
            .setNegativeButton("Close", null).show()
    }

    // ---- More menu ----------------------------------------------------

    private fun showMoreMenu() {
        AlertDialog.Builder(this).setTitle("More Options")
            .setItems(arrayOf(
                "Print PDF",
                "Export / Share PDF",
                "Page size & orientation...",
                "Compress this PDF",
                "OCR - Extract text from scanned PDF",
                "About"
            )) { _, which ->
                val file = pdfFile
                when (which) {
                    0 -> printPdf()
                    1 -> sharePdf()
                    2 -> file?.let { showPageSizeDialog() } ?: toast("Open a PDF first")
                    3 -> file?.let { doCompressQuick(it) } ?: toast("Open a PDF first")
                    4 -> file?.let { doOcrExtract(it) } ?: toast("Open a PDF first")
                    5 -> showAbout()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun printPdf() {
        val file = pdfFile ?: run { toast("No PDF loaded"); return }
        val pm   = getSystemService(PRINT_SERVICE) as? PrintManager ?: run { toast("Print not available"); return }
        val name = "${file.nameWithoutExtension}_print"
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?,
                cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, b: android.os.Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(totalPages).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, dest: android.os.ParcelFileDescriptor?,
                cancel: android.os.CancellationSignal?, cb: WriteResultCallback) {
                try {
                    dest?.let { d -> file.inputStream().use { i -> FileOutputStream(d.fileDescriptor).use { o -> i.copyTo(o) } } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
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
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedSize = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val rg   = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbP  = RadioButton(this).apply { text = "Portrait"; isChecked = true }
        val rbL  = RadioButton(this).apply { text = "Landscape" }
        rg.addView(rbP); rg.addView(rbL)
        rg.setOnCheckedChangeListener { _, id -> landscape = (id == rbL.id) }
        lay.addView(spinner); lay.addView(rg)
        AlertDialog.Builder(this).setTitle("Page Size").setView(lay)
            .setPositiveButton("Export") { _, _ ->
                val mm = { v: Float -> v * 2.8346f }
                val (w, h) = when (selectedSize) {
                    0 -> Pair(mm(210f), mm(297f)); 1 -> Pair(mm(297f), mm(420f))
                    2 -> Pair(mm(148f), mm(210f)); 3 -> Pair(mm(216f), mm(279f))
                    else -> Pair(mm(216f), mm(356f))
                }
                val (pw, ph) = if (landscape) Pair(h, w) else Pair(w, h)
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.reshapePageSize(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_resized"), pw, ph).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_resized") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Export failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompressQuick(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            pdfOps.compressPdf(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_compressed"), 9).fold(
                onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_compressed") },
                onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    private fun doAddPageNumbers(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            pdfOps.addPageNumbers(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_numbered")).fold(
                onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_numbered") },
                onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    private fun doOcrExtract(file: File) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword) else PDDocument.load(file)
                    val s   = com.tom_roush.pdfbox.text.PDFTextStripper().also { it.sortByPosition = true }
                    val t   = s.getText(doc); doc.close(); t.trim().ifBlank { null }
                } catch (_: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                AlertDialog.Builder(this@ViewerActivity).setTitle("OCR / Text Extraction")
                    .setMessage("No extractable text found.\n\nThis PDF contains scanned images (no embedded text).\n\nFull OCR (optical character recognition) requires Google ML Kit integration which downloads a 10MB model on first use.\n\nTo extract Tamil/Indic text: The PDF must have been created with Unicode-compatible fonts. Image-based scans cannot be extracted without OCR.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            val et = EditText(this@ViewerActivity).apply {
                setText(text); textSize = 12f; setTextIsSelectable(true)
                isFocusable = true; isFocusableInTouchMode = true; background = null
            }
            AlertDialog.Builder(this@ViewerActivity).setTitle("Extracted Text (${text.length} chars)")
                .setView(ScrollView(this@ViewerActivity).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(380)); addView(et) })
                .setPositiveButton("Copy All") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text))
                    toast("Copied!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share Text"))
                }.setNegativeButton("Close", null).show()
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("ProPDF Editor v3.0")
            .setMessage("Free PDF Editor - All Premium Features\n\nAnnotate: Pen, Highlight (Yellow), Underline, Strikeout, Text, Shapes, Arrows, Stamps\nView: Night/Sepia/High Contrast modes\nPages: Extract, Delete, Rotate\nSecurity: Encrypt, Watermark\nTools: Merge, Split, Compress, Convert\nScanner: CameraX with tap-to-focus\nPrint: System print dialog\nExport: Page size conversion\n\nNo ads. No watermarks. 100% free.")
            .setPositiveButton("OK", null).show()
    }

    // ---- Page operations ----------------------------------------------

    private fun doExtractPages(file: File) {
        val et = EditText(this).apply { hint="Range e.g. 2-5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages").setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val parts = et.text.toString().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                if (from > to) { toast("Invalid range"); return@setPositiveButton }
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.splitPdf(file, cacheDir, listOf(from..to)).fold(
                        onSuccess = { files -> progressBar.visibility=View.GONE; files.firstOrNull()?.let { saveAndNotify(it,"${file.nameWithoutExtension}_pages${from}_$to") } },
                        onFailure = { progressBar.visibility=View.GONE; showError("Extract failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePage(file: File, pageNum: Int) {
        AlertDialog.Builder(this).setTitle("Delete Page $pageNum?")
            .setPositiveButton("Delete") { _, _ ->
                progressBar.visibility = View.VISIBLE
                lifecycleScope.launch {
                    pdfOps.deletePages(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_deleted"), listOf(pageNum)).fold(
                        onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_deleted") },
                        onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
                    )
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRotatePage(file: File, pageNum: Int) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            pdfOps.rotatePages(file, FileHelper.tempFile(this@ViewerActivity, file.nameWithoutExtension+"_rotated"), mapOf(pageNum to 90)).fold(
                onSuccess = { f -> progressBar.visibility=View.GONE; saveAndNotify(f,"${file.nameWithoutExtension}_rotated") },
                onFailure = { progressBar.visibility=View.GONE; showError("Failed: ${it.message}") }
            )
        }
    }

    // ---- Extract text -------------------------------------------------

    private fun extractText() {
        val file = pdfFile ?: run { toast("No PDF"); return }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc = if (pdfPassword != null) PDDocument.load(file, pdfPassword) else PDDocument.load(file)
                    val s   = com.tom_roush.pdfbox.text.PDFTextStripper().also { it.sortByPosition = true }
                    val t   = s.getText(doc); doc.close(); t.trim().ifBlank { null }
                } catch (_: Exception) { null }
            }
            progressBar.visibility = View.GONE
            if (text.isNullOrBlank()) {
                toast("No extractable text found in this PDF"); return@launch
            }
            val et = EditText(this@ViewerActivity).apply {
                setText(text); textSize = 12f; setTextIsSelectable(true)
                isFocusable = true; isFocusableInTouchMode = true; background = null
            }
            AlertDialog.Builder(this@ViewerActivity).setTitle("Extracted Text")
                .setView(ScrollView(this@ViewerActivity).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(380)); addView(et) })
                .setPositiveButton("Copy All") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("PDF Text", text)); toast("Copied!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share"))
                }.setNegativeButton("Close", null).show()
        }
    }

    // ---- Save annotations ---------------------------------------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file"); return }
        if (canvases.values.none { it.hasAnnotations() }) { toast("No annotations - draw something first"); return }
        val et = EditText(this).apply { setText(file.nameWithoutExtension+"_annotated"); selectAll(); setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Save Annotated PDF").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifBlank { file.nameWithoutExtension+"_annotated" }.removeSuffix(".pdf")
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
                if (pd.isEmpty() && td.isEmpty()) { progressBar.visibility=View.GONE; tvInfo.text=file.name; toast("No annotations"); return@launch }
                pdfOps.saveAnnotationsToPdf(file, FileHelper.tempFile(this@ViewerActivity, outName), pd, td).fold(
                    onSuccess = { savedTmp ->
                        val named = File(cacheDir, "$outName.pdf"); savedTmp.copyTo(named, overwrite=true)
                        progressBar.visibility=View.GONE; tvInfo.text=file.name; saveAndNotify(named, outName)
                    },
                    onFailure = { progressBar.visibility=View.GONE; tvInfo.text=file.name; showError("Save failed: ${it.message}") }
                )
            } catch (e: Exception) { progressBar.visibility=View.GONE; tvInfo.text=file.name; showError("Error: ${e.message}") }
        }
    }

    private suspend fun saveAndNotify(file: File, desiredName: String) {
        val saved = withContext(Dispatchers.IO) {
            try { FileHelper.saveToDownloads(this@ViewerActivity, file) }
            catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(file), file) }
        }
        AlertDialog.Builder(this@ViewerActivity).setTitle("Saved!")
            .setMessage("$desiredName.pdf\n\n${saved.displayPath}").setPositiveButton("OK", null).show()
    }

    private fun sharePdf() {
        val f = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C")); tvInfo.text = "Error"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun makeImgBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE)
        setPadding(dp(10),dp(10),dp(10),dp(10)); setOnClickListener { action() }
    }
    private fun makeTextBtn(label: String, action: () -> Unit) = Button(this).apply {
        text=label; textSize=10f; setTextColor(Color.parseColor("#AADDFF"))
        setBackgroundColor(Color.TRANSPARENT); setPadding(dp(7),0,dp(7),0); setOnClickListener { action() }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
