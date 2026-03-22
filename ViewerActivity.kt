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
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        fun start(context: Context, uri: Uri) {
            context.startActivity(Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_PDF_URI, uri.toString())
            })
        }
    }

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentPage = 0
    private var totalPages = 0
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvPageInfo: TextView
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer_simple)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.rv_pages)
        tvPageInfo = findViewById(R.id.tv_page_info)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadPdf()
    }

    private fun loadPdf() {
        try {
            val file = getPdfFile() ?: return
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount
            supportActionBar?.title = file.name
            tvPageInfo.text = "Pages: $totalPages"
            recyclerView.adapter = PdfPageAdapter()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getPdfFile(): File? {
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        val path = intent.getStringExtra(EXTRA_PDF_PATH)

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
        val file = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
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

    inner class PdfPageAdapter : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {
        inner class PageVH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                setPadding(0, 4, 0, 4)
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val page = pdfRenderer!!.openPage(position)
            val bitmap = Bitmap.createBitmap(
                page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            holder.imageView.setImageBitmap(bitmap)
        }

        override fun getItemCount() = totalPages
    }
}
