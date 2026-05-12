package com.propdf.editor.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.propdf.editor.R
import com.propdf.editor.utils.FileHelper
import java.io.File

class ToolsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ToolsActivity"
        private const val REQ_PICK = 1001
        private const val REQ_PICK_IMAGES = 1002
        private const val REQ_PICK_MERGE = 1003
    }

    private var isDark = true
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private val c_pri = Color.parseColor("#448AFF")

    private var selectedPdf: File? = null
    private var selectedImage: File? = null
    private var mergeFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
    }

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        column.addView(buildTopBar())
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        body.addView(buildSection("Merge PDFs", "Combine multiple PDFs into one") { startMergeFlow() })
        body.addView(buildSection("Split PDF", "Extract pages into separate files") { showSplitDialog() })
        body.addView(buildSection("Compress PDF", "Reduce file size") { showCompressDialog() })
        body.addView(buildSection("Add Watermark", "Stamp text on every page") { showWatermarkDialog() })
        body.addView(buildSection("Rotate Pages", "Rotate specific pages") { showRotateDialog() })
        body.addView(buildSection("Delete Pages", "Remove pages from PDF") { showDeleteDialog() })
        body.addView(buildSection("Page Numbers", "Add page numbering") { showPageNumberDialog() })
        body.addView(buildSection("Header & Footer", "Add header and footer text") { showHeaderFooterDialog() })
        body.addView(buildSection("Image to PDF", "Convert images to PDF") { startImageToPdfFlow() })
        body.addView(buildSection("Insert Image", "Embed an image into a PDF page") { startInsertImageFlow() })
        body.addView(buildSection("Reshape Page Size", "Resize page dimensions") { showReshapeDialog() })
        body.addView(buildSection("Encrypt PDF", "Password protect a PDF") { showEncryptDialog() })
        body.addView(buildSection("Decrypt PDF", "Remove password protection") { showDecryptDialog() })
        body.addView(buildSection("PDF to Images", "Convert PDF pages to images") { startPdfToImagesFlow() })
        body.addView(buildSection("Extract Text", "Get text content from PDF") { startExtractTextFlow() })
        scroll.addView(body)
        column.addView(scroll)
        root.addView(column)
        setContentView(root)
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(40), dp(12), dp(10))
            addView(TextView(this@ToolsActivity).apply {
                text = "Back"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
            addView(View(this@ToolsActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(TextView(this@ToolsActivity).apply {
                text = "PDF Tools"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
            })
            addView(View(this@ToolsActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
        }
    }

    private fun buildSection(title: String, desc: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(cardBg())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
            addView(TextView(this@ToolsActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
            })
            addView(TextView(this@ToolsActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.parseColor(txt2()))
                setPadding(0, dp(2), 0, dp(8))
            })
            addView(TextView(this@ToolsActivity).apply {
                text = "Select PDF"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply {
                    setColor(c_pri)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { onClick() }
            })
        }
    }

    // Stub implementations for all tool flows
    private fun startMergeFlow() { toast("Merge: Select multiple PDFs") }
    private fun showSplitDialog() { toast("Split: Select PDF and page ranges") }
    private fun showCompressDialog() { toast("Compress: Select PDF and quality") }
    private fun showWatermarkDialog() { toast("Watermark: Select PDF and text") }
    private fun showRotateDialog() { toast("Rotate: Select PDF and pages") }
    private fun showDeleteDialog() { toast("Delete: Select PDF and pages") }
    private fun showPageNumberDialog() { toast("Page numbers: Select PDF") }
    private fun showHeaderFooterDialog() { toast("Header/Footer: Select PDF") }
    private fun startImageToPdfFlow() { toast("Image to PDF: Select images") }
    private fun startInsertImageFlow() { toast("Insert image: Select PDF and image") }
    private fun showReshapeDialog() { toast("Reshape: Select PDF and new dimensions") }
    private fun showEncryptDialog() { toast("Encrypt: Select PDF and password") }
    private fun showDecryptDialog() { toast("Decrypt: Select PDF and password") }
    private fun startPdfToImagesFlow() { toast("PDF to images: Select PDF") }
    private fun startExtractTextFlow() { toast("Extract text: Select PDF") }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
