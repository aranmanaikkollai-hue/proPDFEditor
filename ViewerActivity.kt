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

    // One AnnotationCanvasView per page — so annotations don't move on scroll
    private val canvases = mutableMapOf<Int, AnnotationCanvasView>()

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotBar      : LinearLayout
    private lateinit var sizeBar       : LinearLayout
    private lateinit var fabAnnotate   : ImageButton
    private lateinit var seekStroke    : SeekBar
    private lateinit var tvSizeLabel   : TextView

    // Color palette
    private val colors = listOf(
        "#1A73E8" to "Blue",   "#F44336" to "Red",
        "#4CAF50" to "Green",  "#FF9800" to "Orange",
        "#9C27B0" to "Purple", "#000000" to "Black",
        "#FFFFFF" to "White",  "#FFC107" to "Yellow"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildUI()
        loadPdf()
    }

    // ── UI ────────────────────────────────────────────────────

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
        scrollView = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(pageContainer)

        // ── Size bar (shown when a size-supporting tool is active) ──
        sizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
        }
        tvSizeLabel = TextView(this).apply {
            text = "Size: 14"; textSize = 12f; setPadding(0, 0, dp(8), 0)
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        seekStroke = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            max = 80; progress = 14
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                    val w = (v + 4).toFloat()
                    strokeWidth = w; textSizePx = w * 3f
                    tvSizeLabel.text = "Size: $v"
                    canvases.values.forEach { it.setStrokeWidth(w); it.setTextSize(w * 3f) }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?)  {}
            })
        }
        sizeBar.addView(tvSizeLabel)
        sizeBar.addView(seekStroke)

        // ── Annotation toolbar ────────────────────────────────
        annotBar = buildAnnotBar()

        // ── FAB ───────────────────────────────────────────────
        fabAnnotate = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END; setMargins(0, 0, dp(16), dp(4))
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setColorFilter(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val fabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setBackgroundColor(Color.TRANSPARENT); setPadding(0, dp(4), dp(16), dp(4))
            addView(fabAnnotate)
        }

        root.addView(tvInfo); root.addView(progressBar); root.addView(scrollView)
        root.addView(sizeBar); root.addView(annotBar); root.addView(fabRow)
        setContentView(root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fabAnnotate.setOnClickListener { toggleAnnotBar() }
    }

    private fun buildAnnotBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat(); visibility = View.GONE
        }
        data class T(val e: String, val id: String, val hex: String)
        val tools = listOf(
            T("✏️","freehand","#1A73E8"), T("🖍","highlight","#FFC107"),
            T("📝","text","#2E7D32"),     T("▭","rect","#7B1FA2"),
            T("⬛","eraser","#424242"),   T("🎨","color","#E91E63"),
            T("↩️","undo","#455A64"),     T("↪️","redo","#455A64"),
            T("💾","save","#1B5E20"),     T("✖️","close","#B71C1C")
        )
        tools.forEach { t ->
            bar.addView(Button(this).apply {
                text = t.e; textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setTextColor(Color.parseColor(t.hex))
                setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0)
                setOnClickListener { handleTool(t.id, Color.parseColor(t.hex)) }
            })
        }
        return bar
    }

    // ── PDF loading ───────────────────────────────────────────

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val uri = getUri() ?: return@withContext null
                    FileHelper.uriToFile(this@ViewerActivity, uri)
                }
                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot open PDF — file not found"); return@launch
                }
                pdfFile = file; supportActionBar?.title = file.name
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
            val fd    = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr  = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw    = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  •  $totalPages pages"; progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val bmp = synchronized(rndr) {
                    val p = rndr.openPage(i)
                    val sc = sw.toFloat() / p.width
                    val b  = Bitmap.createBitmap(sw, (p.height * sc).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
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
            val doc   = PDDocument.load(file); val rndr = PdfBoxRenderer(doc)
            totalPages = doc.numberOfPages; val sw = resources.displayMetrics.widthPixels
            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  •  $totalPages pages"; progressBar.visibility = View.VISIBLE
            }
            for (i in 0 until totalPages) {
                val raw = rndr.renderImageWithDPI(i, 150f)
                val sc  = sw.toFloat() / raw.width
                val scaled = Bitmap.createScaledBitmap(raw, sw, (raw.height * sc).toInt().coerceAtLeast(1), true)
                if (scaled !== raw) raw.recycle()
                withContext(Dispatchers.Main) { addPage(scaled, i) }
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
        frame.addView(ImageView(this).apply {
            setImageBitmap(bmp); layoutParams = FrameLayout.LayoutParams(-1, -2); adjustViewBounds = true
        })
        val cvs = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setTool(TOOL_NONE, Color.BLUE)
            setStrokeWidth(strokeWidth); setTextSize(textSizePx)
        }
        canvases[idx] = cvs; frame.addView(cvs); pageContainer.addView(frame)
    }

    // ── Tool handling ─────────────────────────────────────────

    private fun handleTool(id: String, color: Int) {
        when (id) {
            "undo"  -> { canvases.values.forEach { it.undo() }
                         toast(if (canvases.values.any { it.canUndo() }) "↩ Undone" else "Nothing to undo") }
            "redo"  -> { canvases.values.forEach { it.redo() }
                         toast(if (canvases.values.any { it.canRedo() }) "↪ Redone" else "Nothing to redo") }
            "color" -> showColorPicker()
            "save"  -> saveWithAnnotations()
            "close" -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                canvases.values.forEach { it.setTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE) }
                sizeBar.visibility = View.GONE; toggleAnnotBar()
            }
            "text"  -> showTextDialog()
            else    -> {
                activeTool  = id; activeColor = color
                canvases.values.forEach { it.setTool(id, color) }
                // Show size bar for tools that support it
                val showSize = id in listOf(
                    AnnotationCanvasView.TOOL_FREEHAND,
                    AnnotationCanvasView.TOOL_HIGHLIGHT,
                    AnnotationCanvasView.TOOL_ERASER,
                    AnnotationCanvasView.TOOL_TEXT
                )
                sizeBar.visibility = if (showSize) View.VISIBLE else View.GONE
                toast(when (id) {
                    "freehand"  -> "✏️ Draw mode — drag on page"
                    "highlight" -> "🖍 Highlight mode — drag over text"
                    "rect"      -> "▭ Rectangle — drag to draw"
                    "eraser"    -> "⬛ Eraser — drag to erase"
                    else        -> id
                })
            }
        }
    }

    private fun showColorPicker() {
        val grid = GridLayout(this).apply {
            columnCount = 4; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        colors.forEach { (hex, name) ->
            grid.addView(FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(56); height = dp(56); setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setBackgroundColor(Color.parseColor(hex))
                if (hex == "#FFFFFF") {
                    // White needs a border
                    setBackgroundResource(android.R.drawable.editbox_background)
                }
                setOnClickListener { dialog ->
                    activeColor = Color.parseColor(hex)
                    canvases.values.forEach { it.setColor(activeColor) }
                    (dialog as? AlertDialog)?.dismiss()
                    toast("Color: $name")
                }
            })
        }
        AlertDialog.Builder(this).setTitle("🎨 Choose Color")
            .setView(grid).setNegativeButton("Cancel", null).show()
    }

    private fun showTextDialog() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et = EditText(this).apply { hint = "Enter annotation text"; textSize = 16f }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        colorRow.addView(TextView(this).apply { text = "Color: "; textSize = 14f; setPadding(0, dp(8), dp(8), 0) })
        var textColor = Color.parseColor("#2E7D32")
        val colorSwatch = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setBackgroundColor(textColor)
            setOnClickListener {
                showColorPickerForText { c -> textColor = c; setBackgroundColor(c) }
            }
        }
        colorRow.addView(colorSwatch)
        lay.addView(et); lay.addView(colorRow)

        AlertDialog.Builder(this).setTitle("📝 Add Text")
            .setView(lay)
            .setPositiveButton("Place on page") { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isEmpty()) { toast("Enter text first"); return@setPositiveButton }
                activeTool = AnnotationCanvasView.TOOL_TEXT; activeColor = textColor
                canvases.values.forEach {
                    it.setTool(AnnotationCanvasView.TOOL_TEXT, textColor)
                    it.setTextSize(textSizePx)
                    it.setPendingText(txt)
                }
                toast("Tap on the page to place text")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showColorPickerForText(onPick: (Int) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 4; setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        var dialog: AlertDialog? = null
        colors.forEach { (hex, name) ->
            grid.addView(View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(56); height = dp(56); setMargins(dp(6),dp(6),dp(6),dp(6))
                }
                setBackgroundColor(Color.parseColor(hex))
                setOnClickListener { onPick(Color.parseColor(hex)); dialog?.dismiss() }
            })
        }
        dialog = AlertDialog.Builder(this).setTitle("Pick color").setView(grid)
            .setNegativeButton("Cancel", null).show()
    }

    // ── Save annotations ──────────────────────────────────────

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }

        // Check if there are any annotations at all
        val hasAny = canvases.values.any { it.hasAnnotations() }
        if (!hasAny) {
            toast("No annotations found — draw something first")
            return
        }

        // Ask for output filename
        val etName = EditText(this).apply {
            setText(file.nameWithoutExtension + "_annotated")
            selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("💾 Save Annotated PDF")
            .setMessage("Enter a name for the saved file:")
            .setView(etName)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                    .ifBlank { file.nameWithoutExtension + "_annotated" }
                    .removeSuffix(".pdf")
                doSaveAnnotations(file, name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doSaveAnnotations(file: File, outName: String) {
        progressBar.visibility = View.VISIBLE; tvInfo.text = "Saving…"

        lifecycleScope.launch {
            try {
                val pageData = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val sw = resources.displayMetrics.widthPixels.toFloat()

                for ((idx, cvs) in canvases) {
                    val strokes = cvs.getStrokes()
                    if (strokes.isEmpty()) continue
                    val scale = if (cvs.width > 0) sw / cvs.width.toFloat() else 1f
                    pageData[idx] = Pair(strokes, scale)
                }

                if (pageData.isEmpty()) {
                    progressBar.visibility = View.GONE
                    tvInfo.text = file.name
                    toast("No stroke annotations found (text notes not yet saveable to PDF)"); return@launch
                }

                val tmp    = FileHelper.tempFile(this@ViewerActivity, outName)
                val result = pdfOps.saveAnnotationsToPdf(file, tmp, pageData)

                result.fold(
                    onSuccess = { savedTmp ->
                        // Rename temp file to desired name
                        val namedTmp = File(cacheDir, "$outName.pdf")
                        savedTmp.copyTo(namedTmp, overwrite = true)

                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@ViewerActivity, namedTmp)
                        }
                        progressBar.visibility = View.GONE; tvInfo.text = file.name
                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("✅ Saved!")
                            .setMessage("📁 File: $outName.pdf\n\nLocation: ${saved.displayPath}\n\nOpen Files app → Downloads to find it.")
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

    // ── Menu ──────────────────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share"); menu?.add(0, 2, 0, "Save Annotations"); return true
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
        barShown = !barShown; annotBar.visibility = if (barShown) View.VISIBLE else View.GONE
        if (!barShown) sizeBar.visibility = View.GONE
        fabAnnotate.setImageResource(
            if (barShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.text = "⚠ $msg"; tvInfo.setBackgroundColor(Color.parseColor("#B71C1C"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
