package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
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

        fun start(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(ctx, ViewerActivity::class.java)
                    .putExtra(EXTRA_PDF_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    @Inject lateinit var pdfOps: PdfOperationsManager

    private var renderer : PdfRenderer? = null
    private var pfd      : ParcelFileDescriptor? = null
    private var pdfFile  : File? = null

    // Views
    private lateinit var scrollView     : ScrollView
    private lateinit var pageContainer  : LinearLayout
    private lateinit var tvInfo         : TextView
    private lateinit var progressBar    : ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadPdf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close()      } catch (_: Exception) {}
    }

    private fun buildUI() {
        // Root layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#424242"))
        }

        // Info bar at top
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

        // Loading spinner
        progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            visibility = View.VISIBLE
        }

        // Scrollable page area
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#424242"))
        }
        scrollView.addView(pageContainer)

        root.addView(tvInfo)
        root.addView(progressBar)
        root.addView(scrollView)
        setContentView(root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                // Step 1: Get a real File from whatever URI we received
                val file = withContext(Dispatchers.IO) { resolveToFile() }

                if (file == null || !file.exists() || file.length() == 0L) {
                    tvInfo.text = "Error: Cannot open PDF"
                    Toast.makeText(this@ViewerActivity,
                        "Cannot open PDF file", Toast.LENGTH_LONG).show()
                    progressBar.visibility = View.GONE
                    return@launch
                }

                pdfFile = file

                // Step 2: Open PdfRenderer
                val (rndr, fd) = withContext(Dispatchers.IO) {
                    val f = ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    Pair(PdfRenderer(f), f)
                }
                renderer = rndr
                pfd = fd

                val total = rndr.pageCount
                supportActionBar?.title = file.name
                tvInfo.text = "${file.name}  •  $total pages"
                progressBar.visibility = View.GONE

                // Step 3: Render pages one by one
                renderPages(rndr, total)

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvInfo.text = "Error: ${e.message}"
                Toast.makeText(this@ViewerActivity,
                    "Failed to open: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun renderPages(rndr: PdfRenderer, total: Int) {
        val screenW = resources.displayMetrics.widthPixels

        for (i in 0 until total) {
            val bitmap = withContext(Dispatchers.Default) {
                synchronized(rndr) {
                    val page   = rndr.openPage(i)
                    val scale  = screenW.toFloat() / page.width
                    val bmpW   = screenW
                    val bmpH   = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp    = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.RGB_565)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bmp
                }
            }

            // Add page to scroll view
            val iv = ImageView(this@ViewerActivity).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(dp(6), dp(5), dp(6), dp(5))
                }
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
            }
            pageContainer.addView(iv)

            // Update page count as pages load
            tvInfo.text = "${pdfFile?.name}  •  ${i + 1} / $total loaded"
        }

        tvInfo.text = "${pdfFile?.name}  •  $total pages"
    }

    /**
     * Convert any URI or path to a local File.
     * This is the key fix — PdfRenderer needs a real file, not a content URI.
     */
    private fun resolveToFile(): File? {
        // Try direct path first
        intent.getStringExtra(EXTRA_PDF_PATH)?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) return f
        }

        // Try URI
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        val uri: Uri? = when {
            uriStr != null      -> Uri.parse(uriStr)
            intent.data != null -> intent.data
            else                -> null
        }
        uri ?: return null

        // file:// — use directly
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return if (f.exists() && f.length() > 0) f else null
        }

        // content:// — copy via ContentResolver into cache
        return copyUriToCache(uri)
    }

    /**
     * Read content URI via ContentResolver InputStream → local cache File.
     * Works with Gmail, WhatsApp, Drive, Downloads — any content provider.
     */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            // Get a proper filename if available
            val name = try {
                contentResolver.query(
                    uri, null, null, null, null
                )?.use { cursor ->
                    val col = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (cursor.moveToFirst() && col >= 0)
                        cursor.getString(col)
                    else null
                }
            } catch (_: Exception) { null }
                ?: "pdf_${System.currentTimeMillis()}.pdf"

            val dest = File(cacheDir, name)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
