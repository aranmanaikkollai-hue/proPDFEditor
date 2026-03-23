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
 * ViewerActivity — Crash-free PDF viewer.
 *
 * ROOT CAUSE FIXES:
 * 1. copyUriToFile() — reads ANY uri scheme via ContentResolver InputStream
 *    into a local File the app owns. PdfRenderer needs a real fd, not a URI.
 * 2. RGB_565 bitmaps + RecyclerView lazy loading — won't OOM on large PDFs
 * 3. Every error shown as Toast — nothing fails silently
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
                    // Grant read permission so the activity can access the URI
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    private lateinit var recyclerView : RecyclerView
    private lateinit var tvHeader     : TextView
    private lateinit var tvPageNum    : TextView
    private lateinit var progress     : ProgressBar
    private lateinit var annotBar     : LinearLayout
    private lateinit var fabAnnotate  : ImageButton
    private lateinit var rootLayout   : FrameLayout

    private var renderer    : PdfRenderer? = null
    private var pfd         : ParcelFileDescriptor? = null
    private var adapter     : PdfPageAdapter? = null
    private var workingFile : File? = null
    private var isNight     = false
    private var barShown    = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadPdfFromIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close()      } catch (_: Exception) {}
    }

    // ── UI ────────────────────────────────────────────────────

    private fun buildUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#2B2B2B"))
        }

        // Full-screen recycler
        recyclerView = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#2B2B2B"))
        }

        // Top header bar
        tvHeader = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(48)).apply { gravity = Gravity.TOP }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), 0, dp(12), 0)
            setBackgroundColor(Color.parseColor("#E8000000"))
            setTextColor(Color.WHITE)
            textSize = 13f
            text = "Opening…"
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Back button
        val btnBack = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.TOP or Gravity.START }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        // Page number badge (top-right)
        tvPageNum = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(56), dp(10), 0)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            textSize = 12f
            visibility = View.GONE
        }

        // Loading spinner
        progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        }

        // Annotation FAB
        fabAnnotate = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(16), dp(16 + 56))
            }
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.WHITE)
            elevation = dp(6).toFloat()
        }

        // Annotation toolbar (hidden until FAB tapped)
        annotBar = buildAnnotBar()

        rootLayout.addView(recyclerView)
        rootLayout.addView(tvHeader)
        rootLayout.addView(btnBack)
        rootLayout.addView(tvPageNum)
        rootLayout.addView(progress)
        rootLayout.addView(annotBar)
        rootLayout.addView(fabAnnotate)
        setContentView(rootLayout)

        fabAnnotate.setOnClickListener { toggleAnnotBar() }
    }

    private fun buildAnnotBar(): LinearLayout {
        data class T(val e: String, val id: String, val c: String)
        val tools = listOf(
            T("✏️","freehand","#1A73E8"), T("🖍","highlight","#FBC02D"),
            T("▭","rect","#7B1FA2"),    T("➡","arrow","#D32F2F"),
            T("⬛","eraser","#424242"),  T("↩","undo","#455A64"),
            T("🌙","night","#1B5E20"),  T("💾","save","#1B5E20"),
            T("✖","close","#B71C1C"),
        )
        return LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(54)).apply { gravity = Gravity.BOTTOM }
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            setPadding(dp(2), dp(4), dp(2), dp(4))
            tools.forEach { t ->
                addView(Button(this@ViewerActivity).apply {
                    text = t.e; textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setTextColor(Color.parseColor(t.c))
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(0,0,0,0)
                    setOnClickListener { onTool(t.id, Color.parseColor(t.c)) }
                })
            }
        }
    }

    // ── PDF loading ───────────────────────────────────────────

    private fun loadPdfFromIntent() {
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val localFile = withContext(Dispatchers.IO) { resolveFile() }

                if (localFile == null || !localFile.exists() || localFile.length() == 0L) {
                    showErr("Cannot open PDF — file not found or unreadable")
                    return@launch
                }

                workingFile = localFile
                val (rndr, fd) = withContext(Dispatchers.IO) {
                    val f  = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r  = PdfRenderer(f)
                    Pair(r, f)
                }
                renderer = rndr; pfd = fd

                val pages = rndr.pageCount
                if (pages == 0) { showErr("PDF has no pages"); return@launch }

                val sw  = resources.displayMetrics.widthPixels
                adapter = PdfPageAdapter(rndr, sw, lifecycleScope)

                val lm = LinearLayoutManager(this@ViewerActivity)
                recyclerView.layoutManager = lm
                recyclerView.adapter = adapter
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(o: Rect, v: View, p: RecyclerView, s: RecyclerView.State) {
                        o.set(dp(8), dp(5), dp(8), dp(5))
                    }
                })
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        val p = lm.findFirstVisibleItemPosition()
                        if (p >= 0) tvPageNum.text = "${p + 1} / $pages"
                    }
                })

                tvHeader.text = "  ${localFile.name}"
                tvPageNum.text = "1 / $pages"
                tvPageNum.visibility = View.VISIBLE
                progress.visibility  = View.GONE

            } catch (e: Exception) {
                progress.visibility = View.GONE
                showErr("Failed: ${e.message}")
            }
        }
    }

    /**
     * Resolves the incoming intent to a local File the app can use
     * with ParcelFileDescriptor.open().
     *
     * Handles:
     *   - content:// from Gmail, WhatsApp, Drive, Downloads
     *   - file:// from file managers
     *   - EXTRA_PDF_PATH (string path)
     *   - EXTRA_PDF_URI  (string uri)
     *   - intent.data    (ACTION_VIEW from other apps)
     */
    private fun resolveFile(): File? {
        // 1. Direct file path (fastest)
        intent.getStringExtra(EXTRA_PDF_PATH)?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) return f
        }

        // 2. URI string (from within the app)
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        val uri: Uri? = when {
            uriStr != null    -> Uri.parse(uriStr)
            intent.data != null -> intent.data
            else              -> null
        }

        uri ?: return null

        // 3. file:// — just open directly
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return if (f.exists() && f.length() > 0) f else null
        }

        // 4. content:// (and everything else) — copy to cache
        return copyUriToCache(uri)
    }

    /**
     * Copies a content URI to a local cache file using ContentResolver.
     * This is the ONLY reliable way to get a File from a content:// URI.
     * Works with Gmail attachments, WhatsApp files, Google Drive, Downloads.
     */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            // Try to get a useful filename from the URI
            val name = getFileName(uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
            val dest = File(cacheDir, name)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16 * 1024)  // 16 KB buffer
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            } ?: return null

            if (dest.exists() && dest.length() > 0) dest else null
        } catch (e: Exception) {
            null
        }
    }

    /** Extract a human-readable filename from a content URI. */
    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    // ── Annotation tools ──────────────────────────────────────

    private fun onTool(id: String, color: Int) {
        val adp = adapter ?: return
        when (id) {
            "undo"  -> adp.undoOnPage(currentPage(), recyclerView)
            "night" -> { isNight = !isNight; adp.nightMode = isNight
                         toast(if (isNight) "🌙 Night mode ON" else "☀️ Night mode OFF") }
            "save"  -> saveAnnotations()
            "close" -> { adp.setActiveTool(AnnotationCanvasView.TOOL_NONE, Color.BLACK)
                         toggleAnnotBar() }
            else -> { adp.setActiveTool(id, color); toast("Tool: $id active") }
        }
    }

    private fun toggleAnnotBar() {
        barShown = !barShown
        annotBar.visibility = if (barShown) View.VISIBLE else View.GONE
        fabAnnotate.setImageResource(
            if (barShown) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_edit
        )
    }

    private fun currentPage() =
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0

    private fun saveAnnotations() {
        val file = workingFile ?: return
        val adp  = adapter ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val annotations = mutableMapOf<Int, Pair<List<AnnotationCanvasView.Stroke>, Float>>()
                (renderer?.pageCount ?: 0).let { n ->
                    for (i in 0 until n) {
                        adp.getAnnotationsForPage(i, recyclerView)?.let { annotations[i] = it }
                    }
                }
                if (annotations.isEmpty()) { toast("No annotations to save"); return@launch }
                val out = File(
                    getExternalFilesDir(null) ?: cacheDir,
                    "${file.nameWithoutExtension}_annotated_${System.currentTimeMillis()}.pdf"
                )
                pdfOps.saveAnnotationsToPdf(file, out, annotations)
                    .onSuccess { toast("✅ Saved: ${it.name}") }
                    .onFailure { toast("❌ ${it.message}") }
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Share PDF")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) sharePdf()
        return super.onOptionsItemSelected(item)
    }

    private fun sharePdf() {
        val f = workingFile ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
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

    private fun showErr(msg: String) {
        runOnUiThread {
            tvHeader.text = "⚠ $msg"
            tvHeader.setBackgroundColor(Color.parseColor("#B71C1C"))
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
