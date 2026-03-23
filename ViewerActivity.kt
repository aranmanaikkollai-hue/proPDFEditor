package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import com.propdf.editor.data.repository.PdfOperationsManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * ViewerActivity — Production-quality PDF viewer.
 *
 * Architecture:
 * - RecyclerView with LinearLayoutManager for lazy page rendering
 * - PdfPageAdapter handles one-page-at-a-time rendering + bitmap recycling
 * - AnnotatedPageView per item = correct per-page annotation coordinates
 * - Night mode via ColorFilter (zero extra bitmap allocation)
 * - Annotation saving converts view-space paths → PDF-space iText operations
 * - All heavy work on Dispatchers.IO / Default; UI updates on Main
 */
@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI  = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putExtra(EXTRA_PDF_URI, uri.toString())
            )
        }
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    // ── Views ────────────────────────────────────────────────
    private lateinit var recyclerView  : RecyclerView
    private lateinit var tvPageInfo    : TextView
    private lateinit var progressBar   : ProgressBar
    private lateinit var toolbarTop    : androidx.appcompat.widget.Toolbar
    private lateinit var bottomToolbar : LinearLayout
    private lateinit var fabAnnotate   : FloatingActionButton
    private lateinit var rootView      : View

    // ── PDF state ─────────────────────────────────────────────
    private var pdfRenderer           : PdfRenderer? = null
    private var parcelFileDescriptor  : ParcelFileDescriptor? = null
    private var adapter               : PdfPageAdapter? = null
    private var currentPdfFile        : File? = null

    // ── Tool state ────────────────────────────────────────────
    private var toolbarVisible = false
    private var isNightMode    = false
    private var activeTool     = AnnotationCanvasView.TOOL_NONE
    private var activeColor    = Color.BLUE

    // ── Lifecycle ─────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootView = buildLayout()
        setContentView(rootView)
        setSupportActionBar(toolbarTop)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupRecyclerView()
        setupBottomToolbar()

        loadPdf()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeRenderer()
    }

    // ── UI construction (programmatic — no missing XML errors) ─

    private fun buildLayout(): View {
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#424242"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        // Toolbar (top)
        toolbarTop = androidx.appcompat.widget.Toolbar(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, dp(56)).apply {
                gravity = Gravity.TOP
            }
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            setTitleTextColor(Color.WHITE)
        }

        // RecyclerView (full screen)
        recyclerView = RecyclerView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        }

        // Page info bar (bottom-ish, above bottom toolbar)
        tvPageInfo = TextView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(64), dp(12), 0)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        // Progress bar (centered, shown while loading)
        progressBar = ProgressBar(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER
            }
        }

        // Bottom annotation toolbar (hidden by default)
        bottomToolbar = buildAnnotationToolbar()

        // FAB to toggle annotation toolbar
        fabAnnotate = FloatingActionButton(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(16 + 56))  // above bottom toolbar
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#1A73E8")
            )
        }

        root.addView(recyclerView)
        root.addView(toolbarTop)
        root.addView(tvPageInfo)
        root.addView(progressBar)
        root.addView(bottomToolbar)
        root.addView(fabAnnotate)

        fabAnnotate.setOnClickListener { toggleAnnotationToolbar() }

        return root
    }

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, dp(56)).apply {
                gravity = Gravity.BOTTOM
            }
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
    }

    private fun setupBottomToolbar() {
        val toolDefs = listOf(
            // label, tool, color
            Triple("✏️", AnnotationCanvasView.TOOL_FREEHAND,  Color.parseColor("#1A73E8")),
            Triple("🟡", AnnotationCanvasView.TOOL_HIGHLIGHT, Color.parseColor("#FFC107")),
            Triple("▭",  AnnotationCanvasView.TOOL_RECTANGLE, Color.parseColor("#9C27B0")),
            Triple("➡", AnnotationCanvasView.TOOL_ARROW,     Color.parseColor("#F44336")),
            Triple("⬛", AnnotationCanvasView.TOOL_ERASER,   Color.BLACK),
            Triple("↩",  "undo",  Color.parseColor("#607D8B")),
            Triple("↪",  "redo",  Color.parseColor("#607D8B")),
            Triple("🌙", "night", Color.parseColor("#37474F")),
            Triple("💾", "save",  Color.parseColor("#2E7D32")),
            Triple("✖",  "close", Color.parseColor("#B71C1C")),
        )

        for ((label, tool, color) in toolDefs) {
            val btn = Button(this).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setTextColor(color)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                setOnClickListener { onToolSelected(tool, color) }
            }
            bottomToolbar.addView(btn)
        }
    }

    // ── RecyclerView setup ────────────────────────────────────

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this).also { lm ->
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val first = lm.findFirstVisibleItemPosition()
                    val total = adapter?.itemCount ?: 0
                    if (first >= 0 && total > 0) {
                        tvPageInfo.text = "${first + 1} / $total"
                    }
                }
            })
        }

        // Add page divider spacing
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: android.graphics.Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(dp(8), dp(6), dp(8), dp(6))
            }
        })
    }

    // ── PDF loading ───────────────────────────────────────────

    private fun loadPdf() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { getPdfFile() }
                    ?: run {
                        showError("Cannot open PDF file")
                        return@launch
                    }

                currentPdfFile = file

                withContext(Dispatchers.IO) {
                    parcelFileDescriptor = ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
                }

                val renderer = pdfRenderer ?: return@launch
                val screenW  = resources.displayMetrics.widthPixels

                adapter = PdfPageAdapter(renderer, screenW, lifecycleScope)
                recyclerView.adapter = adapter

                supportActionBar?.title = file.name
                tvPageInfo.text = "1 / ${renderer.pageCount}"
                progressBar.visibility = View.GONE

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                showError("Error opening PDF: ${e.message}")
            }
        }
    }

    private suspend fun getPdfFile(): File? = withContext(Dispatchers.IO) {
        val path   = intent.getStringExtra(EXTRA_PDF_PATH)
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)

        return@withContext when {
            path != null   -> File(path).takeIf { it.exists() }
            uriStr != null -> {
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "file") {
                    File(uri.path!!).takeIf { it.exists() }
                } else {
                    val tmp = File(cacheDir, "viewer_${System.currentTimeMillis()}.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    }
                    tmp.takeIf { it.exists() }
                }
            }
            else -> null
        }
    }

    private fun closeRenderer() {
        try {
            adapter = null
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (_: Exception) {}
    }

    // ── Tool actions ──────────────────────────────────────────

    private fun onToolSelected(tool: String, color: Int) {
        when (tool) {
            "undo"  -> {
                val idx = currentPageIndex()
                adapter?.undoOnPage(idx, recyclerView)
            }
            "redo"  -> {
                val idx = currentPageIndex()
                adapter?.redoOnPage(idx, recyclerView)
            }
            "night" -> {
                isNightMode = !isNightMode
                adapter?.nightMode = isNightMode
                showSnack(if (isNightMode) "Night mode ON" else "Night mode OFF")
            }
            "save"  -> saveAnnotations()
            "close" -> {
                activeTool = AnnotationCanvasView.TOOL_NONE
                adapter?.setActiveTool(AnnotationCanvasView.TOOL_NONE, Color.BLUE)
                toggleAnnotationToolbar()
            }
            else -> {
                activeTool  = tool
                activeColor = color
                adapter?.setActiveTool(tool, color)
                showSnack("Tool: ${toolLabel(tool)}")
            }
        }
    }

    private fun toolLabel(tool: String) = when (tool) {
        AnnotationCanvasView.TOOL_FREEHAND  -> "Freehand Draw"
        AnnotationCanvasView.TOOL_HIGHLIGHT -> "Highlight"
        AnnotationCanvasView.TOOL_RECTANGLE -> "Rectangle"
        AnnotationCanvasView.TOOL_ARROW     -> "Arrow"
        AnnotationCanvasView.TOOL_ERASER    -> "Eraser"
        else -> tool
    }

    private fun toggleAnnotationToolbar() {
        toolbarVisible = !toolbarVisible
        bottomToolbar.visibility = if (toolbarVisible) View.VISIBLE else View.GONE
        fabAnnotate.setImageResource(
            if (toolbarVisible) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun currentPageIndex(): Int {
        return (recyclerView.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    }

    // ── Annotation saving ─────────────────────────────────────

    /**
     * Save annotations from ALL visible pages into the PDF.
     *
     * Process:
     * 1. Collect (strokes, renderScale) from each page ViewHolder
     * 2. Map stroke paths from view-coords → PDF-coords (divide by scale)
     * 3. Draw onto PdfCanvas using iText
     * 4. Write to a new file, then replace the working copy
     */
    private fun saveAnnotations() {
        val file = currentPdfFile ?: run { showError("No file loaded"); return }
        val adp  = adapter        ?: return

        progressBar.visibility = View.VISIBLE
        showSnack("Saving annotations…")

        lifecycleScope.launch {
            try {
                // Collect annotation data from all currently bound holders
                val pageAnnotations = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                withContext(Dispatchers.Main) {
                    val pageCount = pdfRenderer?.pageCount ?: 0
                    for (i in 0 until pageCount) {
                        val data = adp.getAnnotationsForPage(i, recyclerView)
                        if (data != null) pageAnnotations[i] = data
                    }
                }

                if (pageAnnotations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        showSnack("No annotations to save")
                    }
                    return@launch
                }

                // Write annotated PDF
                val outFile = File(
                    getExternalFilesDir(null) ?: cacheDir,
                    "${file.nameWithoutExtension}_annotated_${System.currentTimeMillis()}.pdf"
                )

                val result = pdfOps.saveAnnotationsToPdf(file, outFile, pageAnnotations)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    result.fold(
                        onSuccess = { saved ->
                            showSnack("✅ Annotations saved: ${saved.name}")
                        },
                        onFailure = { e ->
                            showError("Failed to save: ${e.message}")
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showError("Save error: ${e.message}")
                }
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 2, 0, "Night Mode")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu?.add(0, 3, 0, "Save Annotations")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        1 -> { sharePdf(); true }
        2 -> { onToolSelected("night", Color.BLACK); true }
        3 -> { saveAnnotations(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun sharePdf() {
        val file = currentPdfFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF via"
            ))
        } catch (e: Exception) {
            showError("Share error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun showSnack(msg: String) {
        Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun showError(msg: String) {
        Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(Color.parseColor("#C62828"))
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
