package com.propdf.editor.ui.tools

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val pickedFiles = mutableListOf<File>()
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val f = File(cacheDir, "tool_${System.currentTimeMillis()}_${pickedFiles.size}.pdf")
            contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(f).use { i.copyTo(it) } }
            pickedFiles.add(f)
        }
        tvStatus.text = "${pickedFiles.size} file(s) selected"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Handle files passed from MainActivity
        intent.getStringArrayListExtra("pdf_uris")?.forEach { uriStr ->
            val f = File(cacheDir, "tool_${System.currentTimeMillis()}_${pickedFiles.size}.pdf")
            contentResolver.openInputStream(Uri.parse(uriStr))?.use { i -> FileOutputStream(f).use { i.copyTo(it) } }
            pickedFiles.add(f)
        }

        tvStatus = TextView(this).apply {
            text = if (pickedFiles.isEmpty()) "No files selected" else "${pickedFiles.size} file(s) selected"
            textSize = 14f; setPadding(0, 0, 0, dp(8))
            setTextColor(android.graphics.Color.parseColor("#555555"))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            visibility = View.GONE; max = 100
        }

        root.addView(sectionTitle("📁  Select Files"))
        root.addView(toolButton("Select PDF File(s)", "#1A73E8") { pdfPickerLauncher.launch("application/pdf") })
        root.addView(toolButton("Clear Selection", "#757575") { pickedFiles.clear(); tvStatus.text = "No files selected" })
        root.addView(tvStatus)
        root.addView(progressBar)

        root.addView(sectionTitle("🔧  PDF Operations"))
        root.addView(toolButton("Merge PDFs (select 2+)", "#2E7D32") { doMerge() })
        root.addView(toolButton("Split PDF by Page", "#1565C0") { doSplit() })
        root.addView(toolButton("Compress PDF", "#6A1B9A") { doCompress() })

        root.addView(sectionTitle("🔒  Security"))
        root.addView(toolButton("Password Protect PDF", "#B71C1C") { doEncrypt() })
        root.addView(toolButton("Remove Password", "#E65100") { doDecrypt() })
        root.addView(toolButton("Add Watermark", "#004D40") { doWatermark() })

        root.addView(sectionTitle("📄  Page Tools"))
        root.addView(toolButton("Rotate Pages (90°)", "#37474F") { doRotate() })
        root.addView(toolButton("Delete Pages", "#C62828") { doDeletePages() })
        root.addView(toolButton("Images → PDF", "#1A237E") { doImagesToPdf() })
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun toolButton(label: String, color: String, action: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(android.graphics.Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
        setPadding(dp(16), dp(14), dp(16), dp(14)); textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun requireOneFile(): File? {
        if (pickedFiles.isEmpty()) { toast("Please select a PDF file first"); return null }
        return pickedFiles.first()
    }

    private fun doMerge() {
        if (pickedFiles.size < 2) { toast("Select at least 2 PDFs to merge"); return }
        val out = outputFile("merged")
        run("Merging ${pickedFiles.size} PDFs...") {
            pdfOps.mergePdfs(pickedFiles, out).fold({ toast("✅ Merged: ${out.name}") }, { toast("❌ ${it.message}") })
        }
    }

    private fun doSplit() {
        val file = requireOneFile() ?: return
        val input = EditText(this).apply { hint = "e.g. 1-3, 4-6"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Split pages (e.g. 1-3, 4-6)")
            .setView(input)
            .setPositiveButton("Split") { _, _ ->
                val ranges = input.text.toString().split(",").mapNotNull {
                    val parts = it.trim().split("-")
                    if (parts.size == 2) (parts[0].trim().toIntOrNull() ?: return@mapNotNull null)..(parts[1].trim().toIntOrNull() ?: return@mapNotNull null)
                    else null
                }
                if (ranges.isEmpty()) { toast("Invalid range"); return@setPositiveButton }
                run("Splitting PDF...") {
                    pdfOps.splitPdf(file, cacheDir, ranges).fold({ files -> toast("✅ Split into ${files.size} files") }, { toast("❌ ${it.message}") })
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val file = requireOneFile() ?: return
        val out = outputFile("compressed")
        run("Compressing...") {
            pdfOps.compressPdf(file, out).fold({ toast("✅ Compressed: ${out.name}") }, { toast("❌ ${it.message}") })
        }
    }

    private fun doEncrypt() {
        val file = requireOneFile() ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etPass = EditText(this).apply { hint = "Enter password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(TextView(this).apply { text = "Password:" })
        layout.addView(etPass)
        AlertDialog.Builder(this).setTitle("🔒 Password Protect PDF")
            .setView(layout)
            .setPositiveButton("Protect") { _, _ ->
                val pass = etPass.text.toString()
                if (pass.isEmpty()) { toast("Enter a password"); return@setPositiveButton }
                val out = outputFile("protected")
                run("Encrypting...") {
                    pdfOps.encryptPdf(file, out, pass, pass).fold({ toast("✅ Protected: ${out.name}") }, { toast("❌ ${it.message}") })
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val file = requireOneFile() ?: return
        val et = EditText(this).apply { hint = "Enter current password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Remove") { _, _ ->
                val out = outputFile("unlocked")
                run("Removing password...") {
                    pdfOps.removePdfPassword(file, out, et.text.toString()).fold({ toast("✅ Unlocked: ${out.name}") }, { toast("❌ ${it.message}") })
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val file = requireOneFile() ?: return
        val et = EditText(this).apply { hint = "Watermark text (e.g. CONFIDENTIAL)"; setText("CONFIDENTIAL") }
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val out = outputFile("watermarked")
                run("Adding watermark...") {
                    pdfOps.addTextWatermark(file, out, et.text.toString()).fold({ toast("✅ Done: ${out.name}") }, { toast("❌ ${it.message}") })
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doRotate() {
        val file = requireOneFile() ?: return
        val out = outputFile("rotated")
        run("Rotating all pages 90°...") {
            val total = try {
                android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).also { it.close() }.pageCount
            } catch (e: Exception) { 0 }
            val pages = (1..total).associateWith { 90 }
            pdfOps.rotatePages(file, out, pages).fold({ toast("✅ Rotated: ${out.name}") }, { toast("❌ ${it.message}") })
        }
    }

    private fun doDeletePages() {
        val file = requireOneFile() ?: return
        val et = EditText(this).apply { hint = "Page numbers to delete (e.g. 1,3,5)"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                val out = outputFile("deleted_pages")
                run("Deleting pages...") {
                    pdfOps.deletePages(file, out, pages).fold({ toast("✅ Done: ${out.name}") }, { toast("❌ ${it.message}") })
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doImagesToPdf() {
        toast("Select images — open file picker and pick images")
        val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val imgFiles = uris.mapIndexed { i, uri ->
                File(cacheDir, "img_$i.jpg").also { f ->
                    contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(f).use { inp.copyTo(it) } }
                }
            }
            val out = outputFile("images_to_pdf")
            run("Converting images to PDF...") {
                pdfOps.imagesToPdf(imgFiles, out).fold({ toast("✅ PDF created: ${out.name}") }, { toast("❌ ${it.message}") })
            }
        }
        picker.launch("image/*")
    }

    private fun run(msg: String, block: suspend () -> Unit) {
        progressBar.visibility = View.VISIBLE
        tvStatus.text = msg
        lifecycleScope.launch {
            try { block() } finally {
                progressBar.visibility = View.GONE
                tvStatus.text = "Done!"
            }
        }
    }

    private fun outputFile(prefix: String) = File(getExternalFilesDir(null), "${prefix}_${System.currentTimeMillis()}.pdf")
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
