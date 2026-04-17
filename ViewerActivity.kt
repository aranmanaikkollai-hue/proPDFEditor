package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.ui.tools.ToolsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ViewerActivity : AppCompatActivity() {

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private lateinit var pageContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var loadingBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString != null) {
            loadPdf(Uri.parse(uriString))
        } else {
            Toast.makeText(this, "Error: No PDF URI found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#E5E5E5"))
        }

        scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            isFillViewport = true
        }

        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16), 0, dp(100))
        }

        loadingBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER)
            visibility = View.VISIBLE
        }

        scrollView.addView(pageContainer)
        root.addView(scrollView)
        root.addView(loadingBar)
        setContentView(root)
    }

    private fun loadPdf(uri: Uri) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File(cacheDir, "temp_viewer.pdf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                }
                
                initRenderer(file)
                renderAllPages()
                loadingBar.visibility = View.GONE
            } catch (e: Exception) {
                loadingBar.visibility = View.GONE
                Toast.makeText(this@ViewerActivity, "Failed to load PDF", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initRenderer(file: File) {
        parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        parcelFileDescriptor?.let {
            pdfRenderer = PdfRenderer(it)
        }
    }

    private fun renderAllPages() {
        val renderer = pdfRenderer ?: return
        pageContainer.removeAllViews()

        for (i in 0 until renderer.pageCount) {
            val page = renderer.openPage(i)
            
            // Calculate height based on screen width to maintain aspect ratio
            val screenWidth = resources.displayMetrics.widthPixels - dp(32)
            val ratio = screenWidth.toFloat() / page.width.toFloat()
            val calcHeight = (page.height * ratio).toInt()

            val bitmap = Bitmap.createBitmap(screenWidth, calcHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(screenWidth, calcHeight).apply {
                    setMargins(0, 0, 0, dp(16))
                }
                setImageBitmap(bitmap)
                elevation = dp(4).toFloat()
                setBackgroundColor(Color.WHITE)
            }
            
            pageContainer.addView(imageView)
            page.close()
        }
    }

    override fun onDestroy() {
        try {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URI = "extra_pdf_uri"

        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
            }
            context.startActivity(intent)
        }
    }
}
