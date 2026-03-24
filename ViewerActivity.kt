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

        fun start(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ViewerActivity::class.java)
                    .putExtra(EXTRA_PDF_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    private var pdfFile      : File? = null
    private var totalPages   = 0
    private var activeTool   = "none"
    private var activeColor  = Color.YELLOW
    private var annotBarShown = false
    private var currentPageIndex = 0

    // Store annotation canvases per page so annotations persist while scrolling
    private val annotationCanvases = mutableMapOf<Int, AnnotationCanvasView>()

    // Views
    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var annotBar      : LinearLayout
    private lateinit var fabAnnotate   : ImageButton

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
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Loading…"
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(pageContainer)

        // Annotation toolbar (shown when FAB is tapped)
        annotBar = buildAnnotBar()

        // FAB to toggle annotation tools
        fabAnnotate = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { lp ->
                lp as? LinearLayout.LayoutParams
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val fabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(16), dp(4))
            addView(fabAnnotate)
        }

        root.addView(tvInfo)
        root.addView(progressBar)
        root.addView(scrollView)
        root.addView(annotBar)
        root.addView(fabRow)
        setContentView(root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fabAnnotate.setOnClickListener { toggleAnnotBar() }
    }

    private fun buildAnnotBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }

        // Tool definitions: emoji, toolId, color
        data class Tool(val emoji: String, val id: String, val hex: String)
        val tools = listOf(
            Tool("🖊️",  "freehand",  "#1A73E8"),
            Tool("🖍️",  "highlight", "#FFC107"),
            Tool("📝",  "text",      "#2E7D32"),
            Tool("▭",   "rect",      "#7B1FA2"),
            Tool("⬛",  "eraser",    "#424242"),
            Tool("↩️",  "undo",      "#455A64"),
            Tool("💾",  "save",      "#1B5E20"),
            Tool("✖️",  "close",     "#B71C1C")
        )

        tools.forEach { t ->
            bar.addView(Button(this).apply {
                text       = t.emoji
                textSize   = 18f
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setTextColor(Color.parseColor(t.hex))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                setOnClickListener { handleTool(t.id, Color.parseColor(t.hex)) }
            })
        }
        return bar
    }

    // ── Load PDF ──────────────────────────────────────────────

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    FileHelper.uriToFile(
                        this@ViewerActivity,
                        getUri() ?: return@withContext null
                    )
                }

                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot open PDF — file not found")
                    return@launch
                }

                pdfFile = file
                supportActionBar?.title = file.name

                val rendered = tryAndroidRenderer(file)
                if (!rendered) {
                    pageContainer.removeAllViews()
                    annotationCanvases.clear()
                    tryPdfBoxRenderer(file)
                }

            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
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

    // ── Strategy 1: Android PdfRenderer ──────────────────────

    private suspend fun tryAndroidRenderer(file: File): Boolean = try {
        withContext(Dispatchers.IO) {
            val fd    = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val rndr  = PdfRenderer(fd)
            totalPages = rndr.pageCount
            val sw    = resources.displayMetrics.widthPixels

            withContext(Dispatchers.Main) {
                tvInfo.text = "${file.name}  •  $totalPages pages"
                progressBar.visibility = View.VISIBLE
            }

            for (i in 0 until totalPages) {
                val bmp = synchronized(rndr) {
                    val page  = rndr.openPage(i)
                    val scale = sw.toFloat() / page.width
                    val bmpH  = (page.height * scale).toInt().coerceAtLeast(1)
                    val b     = Bitmap.createBitmap(sw, bmpH, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    b
                }
                withContext(Dispatchers.Main) { addPage(bmp, i) }
            }
            rndr.close(); fd.close()
            withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
        }
        true
    } catch (e: Exception) {
        withContext(Dispatchers.Main) { tvInfo.text = "Trying alternate renderer…" }
        false
    }

    // ── Strategy 2: PDFBox ────────────────────────────────────

    private suspend fun tryPdfBoxRenderer(file: File) {
        withContext(Dispatchers.IO) {
            try {
                val doc   = PDDocument.load(file)
                val rndr  = PdfBoxRenderer(doc)
                totalPages = doc.numberOfPages
                val sw    = resources.displayMetrics.widthPixels

                withContext(Dispatchers.Main) {
                    tvInfo.text = "${file.name}  •  $totalPages pages"
                    progressBar.visibility = View.VISIBLE
                }

                for (i in 0 until totalPages) {
                    val raw    = rndr.renderImageWithDPI(i, 150f)
                    val scale  = sw.toFloat() / raw.width
                    val scaledH = (raw.height * scale).toInt().coerceAtLeast(1)
                    val scaled  = Bitmap.createScaledBitmap(raw, sw, scaledH, true)
                    if (scaled !== raw) raw.recycle()
                    withContext(Dispatchers.Main) { addPage(scaled, i) }
                }

                doc.close()
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Cannot open PDF: ${e.message}") }
            }
        }
    }

    // ── Add a rendered page + annotation overlay ──────────────

    private fun addPage(bmp: Bitmap, pageIndex: Int) {
        // FrameLayout holds ImageView + AnnotationCanvasView stacked
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(6), dp(4), dp(6), dp(4))
            }
            setBackgroundColor(Color.WHITE)
        }

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }

        val canvas = AnnotationCanvasView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setTool("none", Color.YELLOW)
        }
        annotationCanvases[pageIndex] = canvas

        frame.addView(iv)
        frame.addView(canvas)
        pageContainer.addView(frame)
    }

    // ── Tool handling ─────────────────────────────────────────

    private fun handleTool(id: String, color: Int) {
        when (id) {
            "undo"  -> annotationCanvases.values.forEach { it.undo() }
            "save"  -> saveWithAnnotations()
            "close" -> {
                activeTool = "none"
                annotationCanvases.values.forEach { it.setTool("none", Color.YELLOW) }
                toggleAnnotBar()
            }
            "text"  -> showTextInputDialog()
            else    -> {
                activeTool  = id
                activeColor = color
                annotationCanvases.values.forEach { it.setTool(id, color) }
                Toast.makeText(this, toolLabel(id), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toolLabel(id: String) = when (id) {
        "freehand"  -> "✏️ Draw — drag finger on page"
        "highlight" -> "🖍️ Highlight — drag over text"
        "rect"      -> "▭ Rectangle — drag to draw"
        "eraser"    -> "⬛ Eraser — drag to erase"
        else -> id
    }

    private fun toggleAnnotBar() {
        annotBarShown = !annotBarShown
        annotBar.visibility = if (annotBarShown) View.VISIBLE else View.GONE
        fabAnnotate.setImageResource(
            if (annotBarShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    // ── Text annotation dialog ────────────────────────────────

    private fun showTextInputDialog() {
        val et = EditText(this).apply {
            hint       = "Enter annotation text"
            inputType  = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("📝 Add Text Note")
            .setMessage("Text will appear on the current page. Tap where you want it after closing.")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    activeTool = "text"
                    activeColor = Color.parseColor("#2E7D32")
                    annotationCanvases.values.forEach {
                        it.setPendingText(text)
                        it.setTool("text", Color.parseColor("#2E7D32"))
                    }
                    Toast.makeText(this, "Tap on the page to place text", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Save annotations into PDF ─────────────────────────────

    private fun saveWithAnnotations() {
        val file = pdfFile ?: run { toast("No file loaded"); return }
        progressBar.visibility = View.VISIBLE
        tvInfo.text = "Saving annotations…"

        lifecycleScope.launch {
            try {
                // Collect annotation data from all pages
                val pageData = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val screenW  = resources.displayMetrics.widthPixels.toFloat()

                for ((idx, canvas) in annotationCanvases) {
                    val strokes = canvas.getStrokes()
                    if (strokes.isNotEmpty()) {
                        // Scale factor: view width / screen width (pages fill screen width)
                        pageData[idx] = Pair(strokes, screenW / canvas.width.toFloat().let {
                            if (it == 0f) 1f else it
                        })
                    }
                }

                if (pageData.isEmpty()) {
                    toast("No annotations to save")
                    progressBar.visibility = View.GONE
                    tvInfo.text = pdfFile?.name ?: ""
                    return@launch
                }

                // Write to temp file
                val tempOut = FileHelper.tempFile(this@ViewerActivity,
                    "${file.nameWithoutExtension}_annotated")

                val result = pdfOps.saveAnnotationsToPdf(file, tempOut, pageData)

                result.fold(
                    onSuccess = { savedTmp ->
                        // Copy to Downloads (visible in Files app)
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@ViewerActivity, savedTmp)
                        }
                        progressBar.visibility = View.GONE
                        tvInfo.text = file.name

                        AlertDialog.Builder(this@ViewerActivity)
                            .setTitle("✅ Annotations Saved!")
                            .setMessage("Saved to:\n📁 ${saved.displayPath}\n\nOpen your Files app → Downloads to find it.")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                    onFailure = {
                        progressBar.visibility = View.GONE
                        tvInfo.text = file.name
                        showError("Save failed: ${it.message}")
                    }
                )
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showError("Save error: ${e.message}")
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share PDF")
        menu?.add(0, 2, 0, "Save Annotations")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        1 -> { sharePdf(); true }
        2 -> { saveWithAnnotations(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun sharePdf() {
        val f = pdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", f
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"
            ))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.text = "⚠ $msg"
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
