package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer // CORRECTED IMPORT
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ViewerActivity : AppCompatActivity() {

    private var pdfRenderer: PdfRenderer? = null
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(container) })

        val uriString = intent.getStringExtra("extra_pdf_uri")
        if (uriString != null) {
            loadPdf(Uri.parse(uriString))
        }
    }

    private fun loadPdf(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val tempFile = File(cacheDir, "temp_view.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                tempFile
            }
            setupRenderer(file)
        }
    }

    private fun setupRenderer(file: File) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pfd)
            renderPages()
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPages() {
        val renderer = pdfRenderer ?: return
        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val img = ImageView(this).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 20) }
            }
            container.addView(img)
            page.close()
        }
    }

    override fun onDestroy() {
        pdfRenderer?.close()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra("extra_pdf_uri", uri.toString())
            }
            context.startActivity(intent)
        }
    }
}
