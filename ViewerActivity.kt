package com.propdf.editor.ui.viewer

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * ViewerActivity — Stable, lightweight PDF viewer.
 *
 * Key fixes:
 *  - URI resolved via ContentResolver to local cache file (supports content:// and file://)
 *  - RecyclerView lazy rendering — only visible pages rendered
 *  - RGB_565 bitmaps — 50% less RAM than ARGB_8888
 *  - All errors shown via Toast — no silent crashes
 *  - Annotation toolbar per-page (correct coordinates)
 */
@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI  = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"

        fun start(ctx: Context, uri: Uri) =
            ctx.startActivity(
                Intent(ctx, ViewerActivity::class.java)
                    .putExtra(EXTRA_PDF_URI, uri.toString())
            )
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    // ── Views ────────────────────────────────────────────────
    private lateinit var recyclerView : RecyclerView
    private lateinit var tvInfo       : TextView
    private lateinit var progressBar  : ProgressBar
    private lateinit var bottomBar    : LinearLayout
    private lateinit var fabTools     : android.widget.ImageButton
    private lateinit var rootFrame    : FrameLayout

    // ── PDF state ─────────────────────────────────────────────
    private var pdfRenderer  : PdfRenderer? = null
    private var pdfFd        : ParcelFileDescriptor? = null
    private var adapter      : PdfPageAdapter? = null
    private var localFile    : File? = null
    private var isNightMode  = false
    private var activeTool   = AnnotationCanvasView.TOOL_NONE
    private var activeColor  = Color.BLUE
    private var toolbarShown = false

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadPdf()
    }

    override fun onDestroy() {
        super.onDestroy()
        safeCloseRenderer()
    }

    // ── UI construction ───────────────────────────────────────

    private fun buildUI() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A3A"))
        }

        // RecyclerView for lazy page rendering
        recyclerView = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
        }

        // Top info bar
        tvInfo = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(44)).apply { gravity = Gravity.TOP }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(Color.parseColor("#DD000000"))
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Loading…"
        }

        // Loading spinner
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        }

        // Floating annotation toggle button
        fabTools = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(80))
            }
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.WHITE)
        }

        // Bottom annotation toolbar (hidden by default)
        bottomBar = buildAnnotationBar()

        rootFrame.addView(recyclerView)
        rootFrame.addView(tvInfo)
        rootFrame.addView(progressBar)
        rootFrame.addView(fabTools)
        rootFrame.addView(bottomBar)
        setContentView(rootFrame)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fabTools.setOnClickListener { toggleAnnotationBar() }
    }

    private fun buildAnnotationBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(56)).apply { gravity = Gravity.BOTTOM }
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        data class ToolDef(val emoji: String, val tool: String, val color: Int)

        val tools = listOf(
            ToolDef("✏️", AnnotationCanvasView.TOOL_FREEHAND,  Color.parseColor("#1A73E8")),
            ToolDef("🟡", AnnotationCanvasView.TOOL_HIGHLIGHT, Color.parseColor("#FFC107")),
            ToolDef("▭",  AnnotationCanvasView.TOOL_RECTANGLE, Color.parseColor("#9C27B0")),
            ToolDef("➡", AnnotationCanvasView.TOOL_ARROW,     Color.parseColor("#F44336")),
            ToolDef("⬛", AnnotationCanvasView.TOOL_ERASER,   Color.BLACK),
            ToolDef("↩",  "undo",   Color.parseColor("#607D8B")),
            ToolDef("🌙", "night",  Color.parseColor("#37474F")),
            ToolDef("💾", "save",   Color.parseColor("#2E7D32")),
            ToolDef("✖",  "close",  Color.parseColor("#B71C1C")),
        )

        tools.forEach { def ->
            bar.addView(Button(this).apply {
                text = def.emoji
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setTextColor(def.color)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                setOnClickListener { handleToolAction(def.tool, def.color) }
            })
        }
        return bar
    }

    // ── PDF loading ───────────────────────────────────────────

    private fun loadPdf() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // Step 1: Resolve URI to local File safely
                val file = withContext(Dispatchers.IO) { resolveToLocalFile() }

                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot read PDF file. Check permissions.")
                    progressBar.visibility = View.GONE
                    return@launch
                }

                localFile = file

                // Step 2: Open PdfRenderer
                val (renderer, fd) = withContext(Dispatchers.IO) {
                    val parcel   = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(parcel)
                    Pair(renderer, parcel)
                }

                pdfRenderer = renderer
                pdfFd       = fd

                val pageCount = renderer.pageCount
                if (pageCount == 0) {
                    showError("PDF has no pages.")
                    progressBar.visibility = View.GONE
                    return@launch
                }

                // Step 3: Set up RecyclerView
                val screenW = resources.displayMetrics.widthPixels
                adapter     = PdfPageAdapter(renderer, screenW, lifecycleScope)

                val lm = LinearLayoutManager(this@ViewerActivity)
                recyclerView.layoutManager = lm
                recyclerView.adapter = adapter

                // Page indicator on scroll
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        val first = lm.findFirstVisibleItemPosition()
                        if (first >= 0) tvInfo.text = "  ${file.name}   ${first + 1} / $pageCount"
                    }
                })

                // Item spacing
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(r: Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
                        r.set(dp(6), dp(4), dp(6), dp(4))
                    }
                })

                supportActionBar?.title = file.name
                tvInfo.text = "  ${file.name}   1 / $pageCount"
                progressBar.visibility = View.GONE

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showError("Failed to open PDF: ${e.message}")
            }
        }
    }

    /**
     * Resolves any URI scheme to a local File in cache.
     * Supports: content://, file://, direct path strings.
     * Runs on Dispatchers.IO.
     */
    private fun resolveToLocalFile(): File? {
        return try {
            val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
            val path   = intent.getStringExtra(EXTRA_PDF_PATH)

            when {
                path != null -> {
                    val f = File(path)
                    if (f.exists()) f else null
                }
                uriStr != null -> {
                    val uri = Uri.parse(uriStr)
                    uriToLocalFile(uri)
                }
                // Opened from another app via ACTION_VIEW
                intent.data != null -> {
                    uriToLocalFile(intent.data!!)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copy any URI (content:// or file://) to a temp file in cacheDir.
     * This is required because PdfRenderer needs a real file descriptor.
     */
    private fun uriToLocalFile(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return null)
                return if (f.exists()) f else null
            }

            // content:// or other schemes → copy via ContentResolver
            val tmpFile = File(cacheDir, "pdf_view_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                    }
                }
            } ?: return null

            if (tmpFile.exists() && tmpFile.length() > 0) tmpFile else null
        } catch (e: Exception) {
            null
        }
    }

    // ── Tool actions ──────────────────────────────────────────

    private fun handleToolAction(tool: String, color: Int) {
        when (tool) {
            "undo"  -> {
                val idx = currentPage()
                adapter?.undoOnPage(idx, recyclerView)
            }
            "night" -> {
                isNightMode = !isNightMode
                adapter?.nightMode = isNightMode
                toast(if (isNightMode) "Night mode ON" else "Night mode OFF")
            }
            "save"  -> saveAnnotations()
            "close" -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                adapter?.setActiveTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE)
                toggleAnnotationBar()
            }
            else -> {
                activeTool  = tool
                activeColor = color
                adapter?.setActiveTool(tool, color)
                toast("Tool: $tool")
            }
        }
    }

    private fun toggleAnnotationBar() {
        toolbarShown = !toolbarShown
        bottomBar.visibility = if (toolbarShown) View.VISIBLE else View.GONE
        fabTools.setImageResource(
            if (toolbarShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun currentPage(): Int =
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0

    // ── Annotation saving ─────────────────────────────────────

    private fun saveAnnotations() {
        val file = localFile ?: run { toast("No file loaded"); return }
        val adp  = adapter   ?: return
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val pageAnnotations = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                val count = pdfRenderer?.pageCount ?: 0
                for (i in 0 until count) {
                    adp.getAnnotationsForPage(i, recyclerView)?.let { pageAnnotations[i] = it }
                }
                if (pageAnnotations.isEmpty()) {
                    progressBar.visibility = View.GONE
                    toast("No annotations to save")
                    return@launch
                }
                val outFile = File(
                    getExternalFilesDir(null) ?: cacheDir,
                    "${file.nameWithoutExtension}_annotated_${System.currentTimeMillis()}.pdf"
                )
                pdfOps.saveAnnotationsToPdf(file, outFile, pageAnnotations)
                    .onSuccess { toast("✅ Saved: ${it.name}") }
                    .onFailure { toast("❌ Save failed: ${it.message}") }
            } catch (e: Exception) {
                toast("❌ Error: ${e.message}")
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share")
        menu?.add(0, 2, 0, "Night Mode")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        1 -> { sharePdf(); true }
        2 -> { handleToolAction("night", Color.BLACK); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun sharePdf() {
        val file = localFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"
            ))
        } catch (e: Exception) {
            toast("Share error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun safeCloseRenderer() {
        try { pdfRenderer?.close() } catch (_: Exception) {}
        try { pdfFd?.close() } catch (_: Exception) {}
        pdfRenderer = null
        pdfFd       = null
    }

    private fun showError(msg: String) {
        tvInfo.text = "⚠ $msg"
        tvInfo.setBackgroundColor(Color.parseColor("#C62828"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
