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

    private var pdfFile      : File? = null
    private var totalPages   = 0
    private var activeTool   = AnnotationCanvasView.TOOL_NONE
    private var activeColor  = Color.BLUE
    private var strokeWidth  = 14f   // tracks current slider value
    private var textSizePx   = 42f
    private var barShown     = false

    // One canvas per rendered page
    private val canvases = mutableMapOf<Int, AnnotationCanvasView>()

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotBar      : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvSizeLabel   : TextView
    private lateinit var fabAnnotate   : ImageButton

    private val colors = listOf(
        "#1A73E8","#F44336","#4CAF50","#FF9800",
        "#9C27B0","#000000","#795548","#FFC107"
    )
    private val colorNames = listOf(
        "Blue","Red","Green","Orange","Purple","Black","Brown","Yellow"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ── Build UI ───────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#424242"))
        }

        tvInfo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE); textSize = 13f; text = "Loading…"
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        // ── SIZE BAR — shown when pen/highlight/eraser/text active ──
        sizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8E8E8"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        tvSizeLabel = TextView(this).apply {
            text = "Size: 14"; textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            minWidth = dp(70)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 100; progress = 14
        }
        // FIX: SeekBar listener updates both Activity vars AND all existing canvases
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val w = (progress + 4).toFloat()   // min 4px
                strokeWidth = w
                textSizePx  = w * 3f
                tvSizeLabel.text = "Size: $progress"
                // Apply to ALL canvases immediately
                canvases.values.forEach { cv ->
                    cv.setStrokeWidth(w)
                    cv.setTextSize(w * 3f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
        })
        sizeBar.addView(tvSizeLabel)
        sizeBar.addView(seekStroke)

        // ── ANNOTATION TOOLBAR ────────────────────────────────
        annotBar = buildAnnotBar()

        // ── FAB ───────────────────────────────────────────────
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

        root.addView(tvInfo)
        root.addView(progressBar)
        root.addView(scrollView)
        root.addView(sizeBar)
        root.addView(annotBar)
        root.addView(fabRow)
        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fabAnnotate.setOnClickListener { toggleAnnotBar() }
    }

    private fun buildAnnotBar(): LinearLayout {
        data class T(val label: String, val id: String, val hex: String)
        val tools = listOf(
            T("✏️ Draw",    AnnotationCanvasView.TOOL_FREEHAND,  "#1A73E8"),
            T("🖍 Highlight",AnnotationCanvasView.TOOL_HIGHLIGHT,"#E65100"),
            T("📝 Text",    AnnotationCanvasView.TOOL_TEXT,      "#2E7D32"),
            T("▭ Box",      AnnotationCanvasView.TOOL_RECT,      "#7B1FA2"),
            T("⬛ Erase",   AnnotationCanvasView.TOOL_ERASER,    "#424242"),
            T("🎨 Color",   "color",  "#E91E63"),
            T("↩ Undo",     "undo",   "#455A64"),
            T("↪ Redo",     "redo",   "#455A64"),
            T("💾 Save",    "save",   "#1B5E20"),
            T("✖ Close",    "close",  "#B71C1C")
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(dp(2), dp(4), dp(2), dp(4))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.label; textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setTextColor(Color.parseColor(t.hex))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(1), 0, dp(1), 0)
                    setOnClickListener { handleTool(t.id, Color.parseColor(t.hex)) }
                })
            }
        }
    }

    // ── PDF loading ────────────────────────────────────────────

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    FileHelper.uriToFile(this@ViewerActivity, getUri() ?: return@withContext null)
                }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot open PDF — file not found"); return@launch
                }
                pdfFile = file; supportActionBar?.title = file.name
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
            totalPages = rndr.pageCount; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  •  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val bmp = synchronized(rndr) {
                    val p = rndr.openPage(i)
                    val sc = sw.toFloat() / p.width
                    val b  = Bitmap.createBitmap(sw, (p.height*sc).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close(); b
                }
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }; true
    } catch (_: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying alternate renderer…" }; false
    }

    private suspend fun tryPdfBoxRenderer(file: File) = withContext(Dispatchers.IO) {
        try {
            val doc = PDDocument.load(file); val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  •  $totalPages pages"; progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw = rndr.renderImageWithDPI(i, 150f)
                val sc  = sw.toFloat() / raw.width
                val sc2 = Bitmap.createScaledBitmap(raw, sw, (raw.height*sc).toInt().coerceAtLeast(1), true)
                if (sc2 !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(sc2, i) }
            }
            doc.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        } catch (e: Exception) { withContext(Dispatchers.Main) { showError("Cannot open: ${e.message}") } }
    }

    private fun addPage(bmp: Bitmap, idx: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
            setBackgroundColor(Color.WHITE)
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
        }
        // FIX: create canvas with current strokeWidth / textSize already set
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE)
            setStrokeWidth(strokeWidth)
            setTextSize(textSizePx)
        }
        canvases[idx] = cvs
        frame.addView(iv); frame.addView(cvs)
        pageContainer.addView(frame)
    }

    // ── Tool handling ──────────────────────────────────────────

    private fun handleTool(id: String, color: Int) {
        when (id) {
            "undo"  -> {
                canvases.values.forEach { it.undo() }
                toast("↩ Undone")
            }
            "redo"  -> {
                canvases.values.forEach { it.redo() }
                toast("↪ Redone")
            }
            "color" -> showColorPicker()
            "save"  -> saveWithAnnotations()
            "close" -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE) }
                sizeBar.visibility = View.GONE
                toggleAnnotBar()
            }
            AnnotationCanvasView.TOOL_TEXT -> showTextDialog()
            else -> {
                activeTool  = id; activeColor = color
                canvases.values.forEach { cv ->
                    cv.setTool(id, color)
                    cv.setStrokeWidth(strokeWidth)
                    cv.setTextSize(textSizePx)
                }
                // Show size bar for tools that support variable width
                sizeBar.visibility = when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND,
                    AnnotationCanvasView.TOOL_HIGHLIGHT,
                    AnnotationCanvasView.TOOL_ERASER -> View.VISIBLE
                    else -> View.GONE
                }
                toast(when (id) {
                    AnnotationCanvasView.TOOL_FREEHAND  -> "✏️ Draw — drag finger on page"
                    AnnotationCanvasView.TOOL_HIGHLIGHT -> "🖍 Highlight — drag over text"
                    AnnotationCanvasView.TOOL_RECT      -> "▭ Box — drag to draw"
                    AnnotationCanvasView.TOOL_ERASER    -> "⬛ Erase — drag to erase strokes"
                    else -> id
                })
            }
        }
    }

    private fun showColorPicker() {
        val grid = GridLayout(this).apply {
            columnCount = 4; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        var dlg: AlertDialog? = null
        colors.forEachIndexed { i, hex ->
            val v = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(60); height = dp(60); setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setBackgroundColor(Color.parseColor(hex))
                elevation = dp(3).toFloat()
                setOnClickListener {
                    activeColor = Color.parseColor(hex)
                    canvases.values.forEach { it.setColor(activeColor) }
                    dlg?.dismiss()
                    toast("Color: ${colorNames[i]}")
                }
            }
            grid.addView(v)
        }
        dlg = AlertDialog.Builder(this).setTitle("🎨 Pick Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    private fun showTextDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply { hint = "Type your annotation"; textSize = 16f }

        // Size slider inside text dialog
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvSz = TextView(this).apply {
            text = "Size: ${(textSizePx / 3).toInt()}"; textSize = 13f; minWidth = dp(60)
        }
        val sk = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 100; progress = (textSizePx / 3).toInt()
        }
        sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                val sz = ((v + 4) * 3).toFloat()
                textSizePx = sz; tvSz.text = "Size: $v"
                canvases.values.forEach { it.setTextSize(sz) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
        })
        sizeRow.addView(tvSz); sizeRow.addView(sk)

        // Color row
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        colorRow.addView(TextView(this).apply { text = "Color: "; textSize = 13f; setPadding(0, 0, dp(8), 0) })
        var textColor = activeColor
        val swatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setBackgroundColor(textColor); elevation = dp(2).toFloat()
            setOnClickListener { pickTextColor { c -> textColor = c; setBackgroundColor(c) } }
        }
        colorRow.addView(swatch)

        lay.addView(et); lay.addView(sizeRow); lay.addView(colorRow)

        AlertDialog.Builder(this).setTitle("📝 Add Text Annotation")
            .setView(lay)
            .setPositiveButton("Tap page to place") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isEmpty()) { toast("Enter text first"); return@setPositiveButton }
                activeTool  = AnnotationCanvasView.TOOL_TEXT
                activeColor = textColor
                canvases.values.forEach { cv ->
                    cv.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    cv.setTextSize(textSizePx)
                    cv.setPendingText(txt)
                }
                sizeBar.visibility = View.GONE
                toast("Now tap anywhere on the page to place the text")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickTextColor(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 4; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        var dlg: AlertDialog? = null
        colors.forEach { hex ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(56); height = dp(56); setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setBackgroundColor(Color.parseColor(hex))
                setOnClickListener { onPick(Color.parseColor(hex)); dlg?.dismiss() }
            })
        }
        dlg = AlertDialog.Builder(this).setTitle("Text color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    // ── Save annotations ───────────────────────────────────────

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }

        // Check ANY canvas has annotations
        val hasAny = canvases.values.any { it.hasAnnotations() }
        if (!hasAny) {
            toast("Nothing to save — draw on the page first")
            return
        }

        val etName = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("💾 Save Annotated PDF")
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
        progressBar.visibility = View.VISIBLE; tvInfo.text = "Saving…"
        lifecycleScope.launch {
            try {
                // Collect stroke annotations
                val pageData = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                for ((idx, cvs) in canvases) {
                    val strokes = cvs.getStrokes()
                    if (strokes.isEmpty()) continue
                    // FIX: scale = 1f because canvas is same pixel size as bitmap
                    // The bitmap fills the screen width, canvas also fills same FrameLayout
                    val scale = 1f
                    pageData[idx] = Pair(strokes, scale)
                }

                if (pageData.isEmpty()) {
                    progressBar.visibility = View.GONE; tvInfo.text = file.name
                    toast("No stroke annotations to save (text-only notes not yet embeddable)")
                    return@launch
                }

                val tmp = FileHelper.tempFile(this@ViewerActivity, outName)
                pdfOps.saveAnnotationsToPdf(file, tmp, pageData).fold(
                    onSuccess = { savedTmp ->
                        val namedFile = File(cacheDir, "$outName.pdf")
                        savedTmp.copyTo(namedFile, overwrite = true)
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@ViewerActivity, namedFile)
                        }
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("✅ Saved to Downloads!")
                            .setMessage("📄 $outName.pdf\n\nFind it in:\nFiles app → Downloads\n\n(${saved.displayPath})")
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

    // ── Menu ───────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share PDF")
        menu?.add(0, 2, 0, "Save Annotations")
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        1 -> { sharePdf(); true }
        2 -> { saveWithAnnotations(); true }
        else -> super.onOptionsItemSelected(item)
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

    private fun toggleAnnotBar() {
        barShown = !barShown
        annotBar.visibility = if (barShown) View.VISIBLE else View.GONE
        if (!barShown) sizeBar.visibility = View.GONE
        fabAnnotate.setImageResource(
            if (barShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.text = "⚠ $msg"
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
