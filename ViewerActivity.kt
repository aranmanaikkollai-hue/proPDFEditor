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

    private var pdfFile     : File? = null
    private var totalPages  = 0
    private var activeTool  = AnnotationCanvasView.TOOL_NONE
    private var activeColor = Color.BLUE
    private var strokeWidth = 14f
    private var textSizePx  = 42f
    private var barShown    = false
    private var isNightMode = false

    private val canvases    = mutableMapOf<Int, AnnotationCanvasView>()
    private val colors      = listOf(
        "#1A73E8","#F44336","#4CAF50","#FF9800",
        "#9C27B0","#000000","#795548","#FFC107"
    )
    private val colorNames  = listOf(
        "Blue","Red","Green","Orange","Purple","Black","Brown","Yellow"
    )

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotBar      : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvSizeLabel   : TextView
    private lateinit var fabAnnotate   : ImageButton
    private lateinit var tvNightToggle : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ---- Build UI ----------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#424242"))
        }

        // -- Top info bar --
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(4), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { finish() }
        }

        tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "Loading..."
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }

        // Night mode toggle in top bar
        tvNightToggle = TextView(this).apply {
            text = "Day"
            textSize = 11f
            setTextColor(Color.parseColor("#AADDFF"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                isNightMode = !isNightMode
                text = if (isNightMode) "Night" else "Day"
                updateNightMode()
            }
        }

        // Share button in top bar
        val btnShare = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_share)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { sharePdf() }
        }

        topBar.addView(btnBack)
        topBar.addView(tvInfo)
        topBar.addView(tvNightToggle)
        topBar.addView(btnShare)

        // -- Progress bar --
        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // -- Scrollable pages --
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(pageContainer)

        // -- Size bar (shown when drawing tool active) --
        sizeBar = buildSizeBar()

        // -- Annotation toolbar (slides up from bottom) --
        annotBar = buildAnnotBar()

        // -- FAB to toggle annotation bar --
        fabAnnotate = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val fabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(4), dp(16), dp(4))
            addView(fabAnnotate)
        }

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(scrollView)
        root.addView(sizeBar)
        root.addView(annotBar)
        root.addView(fabRow)
        setContentView(root)

        fabAnnotate.setOnClickListener { toggleAnnotBar() }
    }

    private fun buildSizeBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8E8E8"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        tvSizeLabel = TextView(this).apply {
            text = "Size: 14"; textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            minWidth = dp(72)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 100; progress = 14
        }
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val w = (v + 4).toFloat()
                strokeWidth = w; textSizePx = w * 3f
                tvSizeLabel.text = "Size: $v"
                canvases.values.forEach { it.setStrokeWidth(w); it.setTextSize(w * 3f) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        bar.addView(tvSizeLabel)
        bar.addView(seekStroke)
        return bar
    }

    private fun buildAnnotBar(): LinearLayout {
        // Tools: id, display label, color
        data class T(val id: String, val label: String, val hex: String)
        val tools = listOf(
            T(AnnotationCanvasView.TOOL_FREEHAND,  "Pen",    "#1A73E8"),
            T(AnnotationCanvasView.TOOL_HIGHLIGHT, "Hi-Lite","#E65100"),
            T(AnnotationCanvasView.TOOL_TEXT,      "Text",   "#2E7D32"),
            T(AnnotationCanvasView.TOOL_RECT,      "Box",    "#7B1FA2"),
            T(AnnotationCanvasView.TOOL_ERASER,    "Erase",  "#424242"),
            T("color",                             "Color",  "#E91E63"),
            T("textcopy",                          "Copy",   "#005688"),
            T("undo",                              "Undo",   "#455A64"),
            T("redo",                              "Redo",   "#455A64"),
            T("save",                              "Save",   "#1B5E20"),
            T("close",                             "Close",  "#B71C1C")
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(2), dp(4), dp(2), dp(4))
            elevation = dp(12).toFloat()
            visibility = View.GONE
            val btnH = dp(48)
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.label; textSize = 9f
                    layoutParams = LinearLayout.LayoutParams(0, btnH, 1f)
                    setTextColor(Color.parseColor(t.hex))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(1), 0, dp(1), 0)
                    // Highlight active tool
                    setOnClickListener { onToolTap(t.id, Color.parseColor(t.hex)) }
                })
            }
        }
    }

    // ---- PDF Loading -------------------------------------------------

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
                    pageContainer.removeAllViews(); canvases.clear()
                    tryPdfBoxRenderer(file)
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
            val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val bmp = synchronized(rndr) {
                    val p  = rndr.openPage(i)
                    val sc = sw.toFloat() / p.width
                    val b  = Bitmap.createBitmap(
                        sw, (p.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
                    )
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close(); b
                }
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying alternate renderer..." }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File) = withContext(Dispatchers.IO) {
        try {
            val doc  = PDDocument.load(file)
            val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages
            val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  |  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw = rndr.renderImageWithDPI(i, 150f)
                val sc  = sw.toFloat() / raw.width
                val sc2 = Bitmap.createScaledBitmap(raw, sw, (raw.height * sc).toInt().coerceAtLeast(1), true)
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
                setMargins(dp(6), dp(4), dp(6), dp(4))
            }
            setBackgroundColor(Color.WHITE)
        }
        // Page number label
        val tvPage = TextView(this).apply {
            text = "${idx + 1}"
            textSize = 9f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.TOP or Gravity.END }
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
        }
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs); frame.addView(tvPage)
        pageContainer.addView(frame)
    }

    // ---- Tool Handling -----------------------------------------------

    private fun onToolTap(id: String, color: Int) {
        // Visually highlight selected tool button
        highlightActiveTool(id)

        when (id) {
            "undo"     -> { canvases.values.forEach { it.undo() }; toast("Undone") }
            "redo"     -> { canvases.values.forEach { it.redo() }; toast("Redone") }
            "color"    -> showColorPicker()
            "textcopy" -> extractAndShowText()
            "save"     -> saveWithAnnotations()
            "close"    -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE) }
                sizeBar.visibility = View.GONE
                highlightActiveTool("close")
                toggleAnnotBar()
            }
            AnnotationCanvasView.TOOL_TEXT -> {
                // NEW FLOW: tap the tool -> dialog to type text -> then tap page to place
                showTextInputDialog()
            }
            else -> {
                activeTool  = id; activeColor = color
                canvases.values.forEach { cv ->
                    cv.setTool(id, color)
                    cv.setStrokeWidth(strokeWidth)
                    cv.setTextSize(textSizePx)
                }
                sizeBar.visibility = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND,
                    AnnotationCanvasView.TOOL_HIGHLIGHT,
                    AnnotationCanvasView.TOOL_ERASER -> View.VISIBLE
                    else -> View.GONE
                }
                toast(when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "Draw: drag finger on page"
                    AnnotationCanvasView.TOOL_HIGHLIGHT -> "Highlight: drag over text"
                    AnnotationCanvasView.TOOL_RECT      -> "Box: drag to draw rectangle"
                    AnnotationCanvasView.TOOL_ERASER    -> "Erase: drag to erase"
                    else -> id
                })
            }
        }
    }

    private fun highlightActiveTool(activeId: String) {
        // Find buttons in annotBar and dim/highlight them
        for (i in 0 until annotBar.childCount) {
            val btn = annotBar.getChildAt(i) as? Button ?: continue
            btn.setBackgroundColor(
                if (btn.tag == activeId) Color.parseColor("#E8F0FE")
                else Color.TRANSPARENT
            )
        }
        // Set tags on buttons (done once at build time would be better, but works here)
    }

    // ---- Text annotation (new flow: type first, tap page to place) ---

    private fun showTextInputDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply {
            hint = "Type your annotation text"
            textSize = 15f
            maxLines = 3
        }
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvSz = TextView(this).apply {
            text = "Size: ${(textSizePx / 3).toInt()}"
            textSize = 12f; minWidth = dp(65)
        }
        val sk = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 80; progress = (textSizePx / 3).toInt()
        }
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val sz = ((v + 4) * 3).toFloat()
                textSizePx = sz; tvSz.text = "Size: $v"
                canvases.values.forEach { it.setTextSize(sz) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sizeRow.addView(tvSz); sizeRow.addView(sk)

        // Color swatch
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        colorRow.addView(TextView(this).apply {
            text = "Color: "; textSize = 12f; setPadding(0, 0, dp(8), 0)
        })
        var textColor = activeColor
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundColor(textColor)
            setOnClickListener { pickColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        colorRow.addView(swatch)

        lay.addView(et); lay.addView(sizeRow); lay.addView(colorRow)

        AlertDialog.Builder(this)
            .setTitle("Add Text Note")
            .setMessage("Type text below, then tap anywhere on the page to place it:")
            .setView(lay)
            .setPositiveButton("Ready - tap page") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isEmpty()) { toast("Type some text first"); return@setPositiveButton }
                activeTool  = AnnotationCanvasView.TOOL_TEXT
                activeColor = textColor
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    cv.setTextSize(textSizePx)
                    cv.setPendingText(txt)
                }
                sizeBar.visibility = View.GONE
                toast("Now tap on the page where you want the text")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Color picker ------------------------------------------------

    private fun showColorPicker() {
        pickColor { c ->
            activeColor = c
            canvases.values.forEach { it.setColor(c) }
            toast("Color changed")
        }
    }

    private fun pickColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 4; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        var dlg: AlertDialog? = null
        colors.forEachIndexed { i, hex ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(60); height = dp(60); setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setBackgroundColor(Color.parseColor(hex))
                elevation = dp(3).toFloat()
                setOnClickListener {
                    onPick(Color.parseColor(hex))
                    dlg?.dismiss()
                    toast("Color: ${colorNames[i]}")
                }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Pick Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ---- Text extraction (copy text from PDF) ------------------------

    private fun extractAndShowText() {
        val file = pdfFile ?: run { toast("No PDF loaded"); return }
        progressBar.visibility = View.VISIBLE
        tvInfo.text = "Extracting text..."

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val doc = PDDocument.load(file)
                    val sb  = StringBuilder()
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.sortByPosition = true
                    for (i in 1..minOf(doc.numberOfPages, 20)) {
                        stripper.startPage = i; stripper.endPage = i
                        val pageText = stripper.getText(doc).trim()
                        if (pageText.isNotEmpty()) {
                            sb.appendLine("--- Page $i ---")
                            sb.appendLine(pageText)
                            sb.appendLine()
                        }
                    }
                    doc.close()
                    sb.toString().ifBlank { null }
                } catch (e: Exception) { null }
            }

            progressBar.visibility = View.GONE
            tvInfo.text = pdfFile?.name ?: ""

            if (text.isNullOrBlank()) {
                AlertDialog.Builder(this@ViewerActivity)
                    .setTitle("No Text Found")
                    .setMessage("This PDF does not contain selectable text.\n\n" +
                        "It may be a scanned image PDF. Use the scanner's OCR feature for scanned documents.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }

            // Show extracted text with copy button
            val et = EditText(this@ViewerActivity).apply {
                setText(text)
                textSize = 12f
                isFocusable = true
                isFocusableInTouchMode = true
                isTextSelectable = true
                setTextColor(Color.parseColor("#222222"))
                background = null
            }
            val scroll = ScrollView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(400))
                addView(et)
            }

            AlertDialog.Builder(this@ViewerActivity)
                .setTitle("Extracted Text")
                .setMessage("Select text and use the Copy button, or copy all:")
                .setView(scroll)
                .setPositiveButton("Copy All") { _, _ ->
                    val clip = android.content.ClipboardManager::class.java
                        .cast(getSystemService(CLIPBOARD_SERVICE))
                    clip?.setPrimaryClip(
                        android.content.ClipData.newPlainText("PDF Text", text)
                    )
                    toast("All text copied to clipboard!")
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                        }, "Share Text"
                    ))
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ---- Night mode --------------------------------------------------

    private fun updateNightMode() {
        val filter = if (isNightMode) ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        ) else null
        for (i in 0 until pageContainer.childCount) {
            val frame = pageContainer.getChildAt(i) as? FrameLayout ?: continue
            val iv    = frame.getChildAt(0) as? ImageView ?: continue
            iv.colorFilter = filter
        }
        scrollView.setBackgroundColor(
            if (isNightMode) Color.BLACK else Color.parseColor("#424242")
        )
        toast(if (isNightMode) "Night mode ON" else "Day mode ON")
    }

    // ---- Save annotations -------------------------------------------

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }
        if (canvases.values.none { it.hasAnnotations() }) {
            toast("No annotations to save -- draw something first"); return
        }
        val etName = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Save Annotated PDF")
            .setMessage("File will be saved to Downloads:")
            .setView(etName)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                    .ifBlank { file.nameWithoutExtension + "_annotated" }
                    .removeSuffix(".pdf")
                doSave(file, name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doSave(file: File, outName: String) {
        progressBar.visibility = View.VISIBLE; tvInfo.text = "Saving..."
        lifecycleScope.launch {
            try {
                val pageData     = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val pageTextData = mutableMapOf<Int, Pair<List<AnnotationCanvasView.TextAnnot>, Float>>()
                for ((idx, cvs) in canvases) {
                    val strokes = cvs.getStrokes()
                    if (strokes.isNotEmpty()) pageData[idx] = Pair(strokes, 1f)
                    val texts = cvs.getTextAnnots()
                    if (texts.isNotEmpty()) pageTextData[idx] = Pair(texts, 1f)
                }
                if (pageData.isEmpty() && pageTextData.isEmpty()) {
                    progressBar.visibility = View.GONE; tvInfo.text = file.name
                    toast("No annotations found"); return@launch
                }
                val tmp = FileHelper.tempFile(this@ViewerActivity, outName)
                pdfOps.saveAnnotationsToPdf(file, tmp, pageData, pageTextData).fold(
                    onSuccess = { savedTmp ->
                        val named = File(cacheDir, "$outName.pdf")
                        savedTmp.copyTo(named, overwrite = true)
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@ViewerActivity, named)
                        }
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("Saved!")
                            .setMessage("$outName.pdf\n\nSaved to:\n${saved.displayPath}\n\nOpen Files app > Downloads to find it.")
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
        fabAnnotate.setImageResource(
            if (barShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun sharePdf() {
        val f = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", f
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.text = "Error: $msg"
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
