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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * ViewerActivity — opens any PDF without crashing.
 *
 * Strategy (tries in order):
 * 1. Android PdfRenderer  — fast, built-in, but fails on some PDFs
 * 2. PDFBox PDFRenderer   — slower but handles all PDF types including
 *    WhatsApp PDFs, scanned PDFs, PDF 1.3/1.4 with special encoding
 *
 * "Unsupported pixel format" = Android PdfRenderer can't handle the PDF.
 * PDFBox handles it every time.
 */
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

    private var pdfFile : File? = null

    private lateinit var scrollView    : ScrollView
    private lateinit var pageContainer : LinearLayout
    private lateinit var tvInfo        : TextView
    private lateinit var progressBar   : ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PDFBox needs this initialised once before use
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
            setBackgroundColor(Color.parseColor("#424242"))
        }
        scrollView.addView(pageContainer)

        root.addView(tvInfo)
        root.addView(progressBar)
        root.addView(scrollView)
        setContentView(root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    // ── Load PDF ──────────────────────────────────────────────

    private fun loadPdf() {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { resolveToFile() }

                if (file == null || !file.exists() || file.length() == 0L) {
                    showError("Cannot read PDF file.\nCheck that the file exists.")
                    return@launch
                }

                pdfFile = file
                supportActionBar?.title = file.name

                // Try fast Android renderer first; fall back to PDFBox on any error
                val success = tryAndroidRenderer(file)
                if (!success) {
                    pageContainer.removeAllViews()
                    tryPdfBoxRenderer(file)
                }

            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    // ── Strategy 1: Android built-in PdfRenderer ─────────────

    private suspend fun tryAndroidRenderer(file: File): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val fd  = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val rndr = PdfRenderer(fd)
                val total = rndr.pageCount
                val screenW = resources.displayMetrics.widthPixels

                withContext(Dispatchers.Main) {
                    tvInfo.text = "${file.name}  •  $total pages  •  loading…"
                    progressBar.visibility = View.VISIBLE
                }

                for (i in 0 until total) {
                    val bmp = synchronized(rndr) {
                        val page  = rndr.openPage(i)
                        val scale = screenW.toFloat() / page.width
                        val bmpW  = screenW
                        val bmpH  = (page.height * scale).toInt().coerceAtLeast(1)
                        // Use ARGB_8888 here — RGB_565 can cause "unsupported pixel format"
                        val b = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        page.render(b, null, null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        b
                    }

                    val pageNum = i + 1
                    withContext(Dispatchers.Main) {
                        addPageToView(bmp, pageNum, total, file.name)
                    }
                }

                rndr.close()
                fd.close()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvInfo.text = "${file.name}  •  $total pages"
                }
            }
            true
        } catch (e: Exception) {
            // Android renderer failed — will try PDFBox
            withContext(Dispatchers.Main) {
                tvInfo.text = "Trying alternate renderer…"
            }
            false
        }
    }

    // ── Strategy 2: PDFBox renderer ───────────────────────────

    private suspend fun tryPdfBoxRenderer(file: File) {
        withContext(Dispatchers.IO) {
            try {
                val doc     = PDDocument.load(file)
                val rndr    = PDFRenderer(doc)
                val total   = doc.numberOfPages
                val screenW = resources.displayMetrics.widthPixels

                withContext(Dispatchers.Main) {
                    tvInfo.text = "${file.name}  •  $total pages  •  loading…"
                    progressBar.visibility = View.VISIBLE
                }

                for (i in 0 until total) {
                    // PDFBox renders at DPI — 150 DPI is a good balance of quality/speed
                    val bmp: Bitmap = rndr.renderImageWithDPI(i, 150f)

                    // Scale to screen width
                    val scale   = screenW.toFloat() / bmp.width
                    val scaledH = (bmp.height * scale).toInt().coerceAtLeast(1)
                    val scaled  = Bitmap.createScaledBitmap(bmp, screenW, scaledH, true)
                    if (scaled !== bmp) bmp.recycle()

                    val pageNum = i + 1
                    withContext(Dispatchers.Main) {
                        addPageToView(scaled, pageNum, total, file.name)
                    }
                }

                doc.close()

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvInfo.text = "${file.name}  •  $total pages"
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Cannot open PDF: ${e.message}")
                }
            }
        }
    }

    // ── Add rendered page bitmap to scroll view ───────────────

    private fun addPageToView(bmp: Bitmap, pageNum: Int, total: Int, name: String) {
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(6), dp(4), dp(6), dp(4))
            }
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }
        pageContainer.addView(iv)
        tvInfo.text = "$name  •  $pageNum / $total loaded"
    }

    // ── URI → File ────────────────────────────────────────────

    private fun resolveToFile(): File? {
        intent.getStringExtra(EXTRA_PDF_PATH)?.let { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) return f
        }

        val uri: Uri? = when {
            intent.getStringExtra(EXTRA_PDF_URI) != null ->
                Uri.parse(intent.getStringExtra(EXTRA_PDF_URI))
            intent.data != null -> intent.data
            else -> null
        }
        uri ?: return null

        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return if (f.exists() && f.length() > 0) f else null
        }

        return copyUriToCache(uri)
    }

    /**
     * Copy any content:// URI to a local cache file.
     * Required because PdfRenderer and PDFBox both need a real File.
     */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME
                    )
                    if (c.moveToFirst() && col >= 0) c.getString(col) else null
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
        } catch (_: Exception) { null }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun showError(msg: String) {
        progressBar.visibility = View.GONE
        tvInfo.text = "⚠ $msg"
        tvInfo.setBackgroundColor(Color.parseColor("#B71C1C"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
