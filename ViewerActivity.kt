package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer // CORRECTED IMPORT
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ViewerActivity : AppCompatActivity() {

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private lateinit var pageContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.DKGRAY) }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 40, 0, 100)
        }
        scroll.addView(pageContainer)
        setContentView(scroll)

        val uriString = intent.getStringExtra("extra_pdf_uri")
        if (uriString != null) {
            loadPdf(Uri.parse(uriString))
        }
    }

    private fun loadPdf(uri: Uri) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val temp = File(cacheDir, "viewer_temp.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                    temp
                }
                
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd?.let {
                    renderer = PdfRenderer(it)
                    renderPages()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ViewerActivity, "Error opening PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderPages() {
        val res = renderer ?: return
        pageContainer.removeAllViews()
        
        for (i in 0 until res.pageCount) {
            val page = res.openPage(i)
            val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 30) }
                setBackgroundColor(Color.WHITE)
                elevation = 10f
            }
            pageContainer.addView(iv)
            page.close()
        }
    }

    override fun onDestroy() {
        try {
            renderer?.close()
            pfd?.close()
        } catch (e: Exception) {}
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
