package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putExtra(EXTRA_PDF_URI, uri.toString())
            )
        }
    }

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var totalPages = 0

    // Views — created programmatically, no XML needed
    private lateinit var scrollView: ScrollView
    private lateinit var pageContainer: LinearLayout
    private lateinit var tvInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build UI programmatically — no layout XML needed
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        tvInfo = TextView(this).apply {
            text = "Loading PDF..."
            setPadding(16, 12, 16, 12)
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#1A73E8"))
            setTextColor(android.graphics.Color.WHITE)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#555555"))
        }

        scrollView.addView(pageContainer)
        rootLayout.addView(tvInfo)
        rootLayout.addView(scrollView)
        setContentView(rootLayout)

        // Back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadPdf()
    }

    private fun loadPdf() {
        try {
            val file = getPdfFile() ?: run {
                tvInfo.text = "Error: No PDF file provided"
                return
            }
            parcelFileDescriptor = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY
            )
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount
            supportActionBar?.title = file.name
            tvInfo.text = "${file.name}  •  $totalPages pages  •  tap page to zoom"
            renderAllPages()
        } catch (e: Exception) {
            tvInfo.text = "Error opening PDF: ${e.message}"
        }
    }

    private fun renderAllPages() {
        val renderer = pdfRenderer ?: return
        for (i in 0 until totalPages) {
            val page = renderer.openPage(i)
            val scale = 2 // 2x for clarity
            val bitmap = Bitmap.createBitmap(
                page.width * scale,
                page.height * scale,
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val imageView = ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(8, 8, 8, 8)
                }
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            pageContainer.addView(imageView)
        }
    }

    private fun getPdfFile(): File? {
        val path = intent.getStringExtra(EXTRA_PDF_PATH)
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        return when {
            path != null -> File(path)
            uriStr != null -> {
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "file") File(uri.path!!)
                else copyUriToFile(uri)
            }
            else -> null
        }
    }

    private fun copyUriToFile(uri: Uri): File {
        val file = File(cacheDir, "temp_${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { input.copyTo(it) }
        }
        return file
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
    }
}
