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
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val files = mutableListOf<File>()
    private lateinit var tvStatus : TextView
    private lateinit var progress : ProgressBar

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { files.add(copyUri(it)) }
        tvStatus.text = statusText()
        if (files.isNotEmpty()) toast("${files.size} file(s) selected")
    }

    private val imgPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val imgs = uris.map { copyUri(it) }
        val tmp  = FileHelper.tempFile(this, "images_to_pdf")
        run("Converting images to PDF…") {
            pdfOps.imagesToPdf(imgs, tmp)
                .onSuccess { done("✅ PDF created!", it) }
                .onFailure { err("Failed: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(40))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            text = statusText()
            setPadding(0, dp(4), 0, dp(4))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
            isIndeterminate = true
            visibility = View.GONE
        }

        root.addView(card("📁  Files", listOf(
            btn("Select PDF File(s)", 0xFF1565C0.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            btn("Clear selection",    0xFF757575.toInt()) { files.clear(); tvStatus.text = statusText() }
        )))
        root.addView(tvStatus)
        root.addView(progress)

        root.addView(card("🔧  Operations", listOf(
            btn("Merge PDFs (2+ needed)", 0xFF2E7D32.toInt()) { doMerge() },
            btn("Split by page range",    0xFF1A237E.toInt()) { doSplit() },
            btn("Compress / reduce size", 0xFF6A1B9A.toInt()) { doCompress() },
            btn("Extract pages",          0xFF00695C.toInt()) { doExtract() }
        )))

        root.addView(card("🔒  Security", listOf(
            btn("Password protect",  0xFFB71C1C.toInt()) { doEncrypt() },
            btn("Remove password",   0xFFE65100.toInt()) { doDecrypt() },
            btn("Add watermark",     0xFF004D40.toInt()) { doWatermark() }
        )))

        root.addView(card("📄  Page Tools", listOf(
            btn("Rotate pages",     0xFF37474F.toInt()) { doRotate() },
            btn("Delete pages",     0xFFC62828.toInt()) { doDeletePages() },
            btn("Add page numbers", 0xFF1A237E.toInt()) { doPageNumbers() },
            btn("Header / Footer",  0xFF33691E.toInt()) { doHeaderFooter() }
        )))

        root.addView(card("🔄  Convert", listOf(
            btn("Images → PDF",      0xFF0D47A1.toInt()) { imgPicker.launch(arrayOf("image/*")) },
            btn("PDF → Images (JPG)", 0xFF4A148C.toInt()) { doPdfToImages() }
        )))

        root.addView(card("📤  Share", listOf(
            btn("Share selected PDF", 0xFF00897B.toInt()) { doShare() }
        )))
    }

    // ── Operations ────────────────────────────────────────────

    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        val tmp = FileHelper.tempFile(this, "merged")
        run("Merging ${files.size} PDFs…") {
            pdfOps.mergePdfs(files, tmp)
                .onSuccess { done("✅ Merged!", it) }
                .onFailure { err("Merge failed: ${it.message}") }
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        dialog("Split pages (e.g. 1-3)") { input ->
            val parts = input.trim().split("-")
            val from  = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val to    = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (from == null || to == null) { toast("Enter range like: 1-3"); return@dialog }
            val tmp = FileHelper.tempFile(this, "split")
            run("Splitting pages $from–$to…") {
                val dir = cacheDir
                pdfOps.splitPdf(f, dir, listOf(from..to))
                    .onSuccess { files -> files.firstOrNull()?.let { done("✅ Split done!", it) }
                        ?: run { err("Split produced no output") } }
                    .onFailure { err("Split failed: ${it.message}") }
            }
        }
    }

    private fun doCompress() {
        val f   = need() ?: return
        val tmp = FileHelper.tempFile(this, "compressed")
        run("Compressing…") {
            pdfOps.compressPdf(f, tmp)
                .onSuccess {
                    val pct = if (f.length() > 0) ((f.length() - it.length()) * 100L / f.length()) else 0L
                    done("✅ Compressed ($pct% smaller)", it)
                }
                .onFailure { err("Compress failed: ${it.message}") }
        }
    }

    private fun doExtract() {
        val f = need() ?: return
        dialog("Extract pages (e.g. 2-5)") { input ->
            val parts = input.trim().split("-")
            val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
            val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
            run("Extracting pages $from–$to…") {
                pdfOps.splitPdf(f, cacheDir, listOf(from..to))
                    .onSuccess { files -> files.firstOrNull()?.let { done("✅ Extracted!", it) } }
                    .onFailure { err("Extract failed: ${it.message}") }
            }
        }
    }

    private fun doEncrypt() {
        val f = need() ?: return
        dialog("Password to set") { pw ->
            if (pw.isBlank()) { toast("Enter a password"); return@dialog }
            val tmp = FileHelper.tempFile(this, "protected")
            run("Encrypting with AES-256…") {
                pdfOps.encryptPdf(f, tmp, pw, pw)
                    .onSuccess { done("✅ Password protected!", it) }
                    .onFailure { err("Encrypt failed: ${it.message}") }
            }
        }
    }

    private fun doDecrypt() {
        val f = need() ?: return
        dialog("Current password") { pw ->
            val tmp = FileHelper.tempFile(this, "unlocked")
            run("Removing password…") {
                pdfOps.removePdfPassword(f, tmp, pw)
                    .onSuccess { done("✅ Password removed!", it) }
                    .onFailure { err("Wrong password: ${it.message}") }
            }
        }
    }

    private fun doWatermark() {
        val f = need() ?: return
        dialog("Watermark text (e.g. CONFIDENTIAL)") { text ->
            val tmp = FileHelper.tempFile(this, "watermarked")
            run("Adding watermark…") {
                pdfOps.addTextWatermark(f, tmp, text.ifBlank { "CONFIDENTIAL" })
                    .onSuccess { done("✅ Watermark added!", it) }
                    .onFailure { err("Watermark failed: ${it.message}") }
            }
        }
    }

    private fun doRotate() {
        val f = need() ?: return
        AlertDialog.Builder(this).setTitle("Rotate all pages")
            .setItems(arrayOf("90° right", "180°", "90° left")) { _, which ->
                val deg = when (which) { 0 -> 90; 1 -> 180; else -> 270 }
                val tmp = FileHelper.tempFile(this, "rotated")
                run("Rotating $deg°…") {
                    val n = pageCount(f)
                    pdfOps.rotatePages(f, tmp, (1..n).associateWith { deg })
                        .onSuccess { done("✅ Rotated!", it) }
                        .onFailure { err("Rotate failed: ${it.message}") }
                }
            }.show()
    }

    private fun doDeletePages() {
        val f = need() ?: return
        dialog("Pages to delete (e.g. 1,3,5)") { input ->
            val pages = input.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (pages.isEmpty()) { toast("Enter page numbers"); return@dialog }
            val tmp = FileHelper.tempFile(this, "deleted")
            run("Deleting pages…") {
                pdfOps.deletePages(f, tmp, pages)
                    .onSuccess { done("✅ Pages deleted!", it) }
                    .onFailure { err("Delete failed: ${it.message}") }
            }
        }
    }

    private fun doPageNumbers() {
        val f   = need() ?: return
        val tmp = FileHelper.tempFile(this, "numbered")
        run("Adding page numbers…") {
            pdfOps.addPageNumbers(f, tmp)
                .onSuccess { done("✅ Page numbers added!", it) }
                .onFailure { err("Failed: ${it.message}") }
        }
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val etH = EditText(this).apply { hint = "Header text (blank = skip)" }
        val etF = EditText(this).apply { hint = "Footer text (blank = skip)" }
        lay.addView(TextView(this).apply { text = "Header:" }); lay.addView(etH)
        lay.addView(TextView(this).apply { text = "Footer:" }); lay.addView(etF)
        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val h = etH.text.toString().ifBlank { null }
                val ft = etF.text.toString().ifBlank { null }
                if (h == null && ft == null) { toast("Enter header or footer text"); return@setPositiveButton }
                val tmp = FileHelper.tempFile(this, "headerfooter")
                run("Adding header/footer…") {
                    pdfOps.addHeaderFooter(f, tmp, h, ft)
                        .onSuccess { done("✅ Done!", it) }
                        .onFailure { err("Failed: ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val f   = need() ?: return
        run("Exporting pages as JPG…") {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    f, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val r   = android.graphics.pdf.PdfRenderer(pfd)
                var count = 0
                for (i in 0 until r.pageCount) {
                    val pg  = r.openPage(i)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        pg.width * 2, pg.height * 2, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    pg.render(bmp, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    // Save to temp, then copy to Downloads
                    val tmp = File(cacheDir, "${f.nameWithoutExtension}_p${i + 1}.jpg")
                    FileOutputStream(tmp).use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    bmp.recycle()
                    FileHelper.saveToDownloads(this@ToolsActivity, tmp)
                    count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    toast("✅ $count images saved to Downloads")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { err("Failed: ${e.message}") }
            }
        }
    }

    private fun doShare() {
        val f = need() ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", f
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"
            ))
        } catch (e: Exception) { err("Share error: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────

    /**
     * Run operation: show progress, execute on IO, copy result to Downloads, show result dialog.
     */
    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        progress.visibility = View.VISIBLE
        tvStatus.text = "⏳ $label"
        lifecycleScope.launch {
            withContext(Dispatchers.IO, block)
            progress.visibility = View.GONE
            tvStatus.text = statusText()
        }
    }

    /**
     * Show result dialog with file info and save to Downloads.
     * File will appear in Files app → Downloads.
     */
    private fun done(msg: String, tempFile: File) = runOnUiThread {
        lifecycleScope.launch {
            // Save to visible Downloads folder
            val saved = withContext(Dispatchers.IO) {
                try { FileHelper.saveToDownloads(this@ToolsActivity, tempFile) }
                catch (_: Exception) {
                    FileHelper.SaveResult("app cache", Uri.fromFile(tempFile), tempFile)
                }
            }

            AlertDialog.Builder(this@ToolsActivity)
                .setTitle("✅ Done!")
                .setMessage(
                    "$msg\n\n" +
                    "📁 Saved to:\n${saved.displayPath}\n\n" +
                    "Open your Files app → Downloads to find it."
                )
                .setPositiveButton("Open") { _, _ ->
                    val f = saved.file ?: tempFile
                    ViewerActivity.start(this@ToolsActivity,
                        try {
                            androidx.core.content.FileProvider.getUriForFile(
                                this@ToolsActivity, "$packageName.provider", f)
                        } catch (_: Exception) { Uri.fromFile(f) }
                    )
                }
                .setNeutralButton("Share") { _, _ ->
                    val f = saved.file ?: tempFile
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@ToolsActivity, "$packageName.provider", f)
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share"
                        ))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("OK", null)
                .show()
        }
    }

    private fun err(msg: String) = runOnUiThread {
        progress.visibility = View.GONE
        tvStatus.text = statusText()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF file first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) {
            toast("File missing — please re-select")
            files.clear(); tvStatus.text = statusText(); return null
        }
        return f
    }

    private fun copyUri(uri: Uri): File {
        val name = FileHelper.getFileName(this, uri)
            ?: "pdf_${System.currentTimeMillis()}.pdf"
        val dest = File(cacheDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        } catch (_: Exception) {}
        return dest
    }

    private fun pageCount(f: File) = try {
        val pfd = android.os.ParcelFileDescriptor.open(
            f, android.os.ParcelFileDescriptor.MODE_READ_ONLY
        )
        val r = android.graphics.pdf.PdfRenderer(pfd)
        val c = r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun statusText() =
        if (files.isEmpty()) "No file selected"
        else "✅ ${files.size} file(s) ready"

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dialog(hint: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { this.hint = hint; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        AlertDialog.Builder(this).setTitle(hint).setView(et)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun card(title: String, buttons: List<View>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(this@ToolsActivity).apply {
                text = title; textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF222222.toInt()); setPadding(0, 0, 0, dp(8))
            })
            buttons.forEach { addView(it) }
        }

    private fun btn(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
        setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
