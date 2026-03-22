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
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF_URI = "extra_pdf_uri"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
    }

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var totalPages = 0
    private var isNightMode = false
    private var currentTool = "none"
    private lateinit var pageContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var tvInfo: TextView
    private lateinit var annotationCanvas: AnnotationCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Root layout
        val root = android.widget.FrameLayout(this)

        // Main scroll view
        scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#424242"))
        }
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(pageContainer)

        // Annotation canvas overlay
        annotationCanvas = AnnotationCanvasView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            visibility = View.GONE
        }

        // Top info bar
        tvInfo = TextView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, dpToPx(48)).apply {
                gravity = Gravity.TOP
            }
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        // Annotation FAB
        val fabAnnotate = FloatingActionButton(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dpToPx(16), dpToPx(80))
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#1A73E8"))
            )
        }

        root.addView(scrollView)
        root.addView(annotationCanvas)
        root.addView(tvInfo)
        root.addView(fabAnnotate)
        setContentView(root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fabAnnotate.setOnClickListener { showAnnotationTools() }

        loadPdf()
    }

    private fun loadPdf() {
        try {
            val file = getPdfFile() ?: run { tvInfo.text = "Error: No file"; return }
            parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount
            supportActionBar?.title = file.name
            tvInfo.text = "  ${file.name}  •  $totalPages pages"
            renderPages()
        } catch (e: Exception) {
            tvInfo.text = "Error: ${e.message}"
        }
    }

    private fun renderPages() {
        val renderer = pdfRenderer ?: return
        val screenWidth = resources.displayMetrics.widthPixels

        for (i in 0 until totalPages) {
            val page = renderer.openPage(i)
            val scale = screenWidth.toFloat() / page.width
            val bitmapWidth = screenWidth
            val bitmapHeight = (page.height * scale).toInt()
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

            if (isNightMode) {
                // Dark mode: invert colors
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
                val invertedBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                Canvas(invertedBitmap).drawBitmap(bitmap, 0f, 0f, paint)
                bitmap.recycle()
                addPageView(invertedBitmap, i + 1)
            } else {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                addPageView(bitmap, i + 1)
            }
            page.close()
        }
    }

    private fun addPageView(bitmap: Bitmap, pageNum: Int) {
        val container = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(4))
            }
        }
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            layoutParams = ViewGroup.LayoutParams(-1, -2)
            adjustViewBounds = true
            setBackgroundColor(Color.WHITE)
        }
        val pageLabel = TextView(this).apply {
            text = "$pageNum"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            textSize = 11f
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0,0,dpToPx(4),dpToPx(4)) }
        }
        container.addView(imageView)
        container.addView(pageLabel)
        pageContainer.addView(container)
    }

    private fun showAnnotationTools() {
        val sheet = BottomSheetDialog(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(32))
        }

        val title = TextView(this).apply {
            text = "Annotation Tools"
            textSize = 18f
            setTextColor(Color.parseColor("#1A1A1A"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(title)

        val tools = listOf(
            Triple("✏️  Freehand Draw", "freehand", Color.parseColor("#1A73E8")),
            Triple("🟡  Highlight", "highlight", Color.parseColor("#FFC107")),
            Triple("📝  Sticky Note", "note", Color.parseColor("#4CAF50")),
            Triple("▭  Rectangle", "rect", Color.parseColor("#9C27B0")),
            Triple("➡️  Arrow", "arrow", Color.parseColor("#F44336")),
            Triple("⬛  Eraser", "eraser", Color.parseColor("#607D8B")),
            Triple("🌙  Night Mode", "night", Color.parseColor("#37474F")),
            Triple("↩️  Undo", "undo", Color.parseColor("#FF5722")),
            Triple("✅  Done / Hide Tools", "none", Color.parseColor("#2E7D32")),
        )

        tools.forEach { (label, tool, color) ->
            val btn = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dpToPx(8)) }
                textSize = 15f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                setOnClickListener {
                    when (tool) {
                        "night" -> {
                            isNightMode = !isNightMode
                            pageContainer.removeAllViews()
                            renderPages()
                            Toast.makeText(this@ViewerActivity,
                                if (isNightMode) "Night mode ON" else "Night mode OFF",
                                Toast.LENGTH_SHORT).show()
                        }
                        "undo" -> annotationCanvas.undo()
                        "none" -> {
                            annotationCanvas.visibility = View.GONE
                            currentTool = "none"
                        }
                        else -> {
                            annotationCanvas.visibility = View.VISIBLE
                            annotationCanvas.setTool(tool, getToolColor(tool))
                            currentTool = tool
                            Toast.makeText(this@ViewerActivity,
                                "Draw on PDF — tap Done when finished", Toast.LENGTH_SHORT).show()
                        }
                    }
                    sheet.dismiss()
                }
            }
            layout.addView(btn)
        }
        sheet.setContentView(layout)
        sheet.show()
    }

    private fun getToolColor(tool: String) = when (tool) {
        "highlight" -> Color.argb(120, 255, 235, 59)
        "freehand"  -> Color.parseColor("#1A73E8")
        "rect"      -> Color.parseColor("#9C27B0")
        "arrow"     -> Color.parseColor("#F44336")
        "eraser"    -> Color.WHITE
        else        -> Color.parseColor("#1A73E8")
    }

    private fun getPdfFile(): File? {
        val path = intent.getStringExtra(EXTRA_PDF_PATH)
        val uriStr = intent.getStringExtra(EXTRA_PDF_URI)
        return when {
            path != null -> File(path)
            uriStr != null -> {
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "file") File(uri.path!!)
                else {
                    val f = File(cacheDir, "pdf_${System.currentTimeMillis()}.pdf")
                    contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(f).use { i.copyTo(it) } }
                    f
                }
            }
            else -> null
        }
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

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
