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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val files = mutableListOf<File>()
    private lateinit var tvStatus  : TextView
    private lateinit var progress  : ProgressBar

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri -> files.add(copyUri(uri)) }
        tvStatus.text = statusText()
        Toast.makeText(this, "${files.size} file(s) selected", Toast.LENGTH_SHORT).show()
    }

    private val imgPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val imgs = uris.map { copyUri(it) }
        val out  = outFile("images_to_pdf")
        run("Converting images to PDF…") {
            pdfOps.imagesToPdf(imgs, out)
                .onSuccess { showDone("✅ PDF created!", it) }
                .onFailure { showError("Failed: ${it.message}") }
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
        progress = ProgressBar(this,
            null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
            isIndeterminate = true
            visibility = View.GONE
        }

        // ── SELECT FILES ──────────────────────────────────────
        root.addView(card("📁  Files", listOf(
            btn("Select PDF File(s)", 0xFF1565C0.toInt()) {
                pdfPicker.launch(arrayOf("application/pdf"))
            },
            btn("Clear selection", 0xFF757575.toInt()) {
                files.clear(); tvStatus.text = statusText()
            }
        )))
        root.addView(tvStatus)
        root.addView(progress)

        // ── OPERATIONS ────────────────────────────────────────
        root.addView(card("🔧  PDF Operations", listOf(
            btn("Merge PDFs (need 2+)", 0xFF2E7D32.toInt()) { doMerge() },
            btn("Split by page range",  0xFF1A237E.toInt()) { doSplit() },
            btn("Compress / reduce size",0xFF6A1B9A.toInt()){ doCompress() }
        )))

        root.addView(card("🔒  Security", listOf(
            btn("Password protect",  0xFFB71C1C.toInt()) { doEncrypt() },
            btn("Remove password",   0xFFE65100.toInt()) { doDecrypt() },
            btn("Add watermark",     0xFF004D40.toInt()) { doWatermark() }
        )))

        root.addView(card("📄  Page Tools", listOf(
            btn("Rotate pages",     0xFF37474F.toInt()) { doRotate() },
            btn("Delete pages",     0xFFC62828.toInt()) { doDeletePages() },
            btn("Add page numbers", 0xFF1A237E.toInt()) { doPageNumbers() }
        )))

        root.addView(card("🔄  Convert", listOf(
            btn("Images → PDF", 0xFF0D47A1.toInt()) {
                imgPicker.launch(arrayOf("image/*"))
            },
            btn("PDF → Images (JPG)", 0xFF4A148C.toInt()) { doPdfToImages() }
        )))

        root.addView(card("📤  Share", listOf(
            btn("Share selected PDF", 0xFF00897B.toInt()) { doShare() }
        )))
    }

    // ── Operations ────────────────────────────────────────────

    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        val out = outFile("merged")
        run("Merging ${files.size} PDFs…") {
            pdfOps.mergePdfs(files, out)
                .onSuccess { showDone("✅ Merged!", it) }
                .onFailure { showError("Merge failed: ${it.message}") }
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        dialog("Split pages", "Page range (e.g. 1-3)") { input ->
            // Simple parse — split on "-"
            val parts = input.trim().split("-")
            val from  = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val to    = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (from == null || to == null) {
                toast("Enter range like: 1-3"); return@dialog
            }
            run("Splitting pages $from–$to…") {
                val dir = getExternalFilesDir(null) ?: cacheDir
                pdfOps.splitPdf(f, dir, listOf(from..to))
                    .onSuccess { files ->
                        val out = files.firstOrNull()
                        if (out != null) showDone("✅ Split done!", out)
                        else showError("Split produced no output")
                    }
                    .onFailure { showError("Split failed: ${it.message}") }
            }
        }
    }

    private fun doCompress() {
        val f   = need() ?: return
        val out = outFile("compressed")
        run("Compressing…") {
            pdfOps.compressPdf(f, out)
                .onSuccess {
                    val pct = if (f.length() > 0)
                        ((f.length() - it.length()) * 100L / f.length()) else 0L
                    showDone("✅ Compressed ($pct% smaller)", it)
                }
                .onFailure { showError("Compress failed: ${it.message}") }
        }
    }

    private fun doEncrypt() {
        val f = need() ?: return
        dialog("Password Protect", "Set password") { pw ->
            if (pw.isBlank()) { toast("Enter a password"); return@dialog }
            val out = outFile("protected")
            run("Encrypting…") {
                pdfOps.encryptPdf(f, out, pw, pw)
                    .onSuccess { showDone("✅ Protected!", it) }
                    .onFailure { showError("Encrypt failed: ${it.message}") }
            }
        }
    }

    private fun doDecrypt() {
        val f = need() ?: return
        dialog("Remove Password", "Current password") { pw ->
            val out = outFile("unlocked")
            run("Removing password…") {
                pdfOps.removePdfPassword(f, out, pw)
                    .onSuccess { showDone("✅ Unlocked!", it) }
                    .onFailure { showError("Wrong password: ${it.message}") }
            }
        }
    }

    private fun doWatermark() {
        val f = need() ?: return
        dialog("Watermark", "Watermark text") { text ->
            val out = outFile("watermarked")
            run("Adding watermark…") {
                pdfOps.addTextWatermark(f, out,
                    text.ifBlank { "CONFIDENTIAL" })
                    .onSuccess { showDone("✅ Watermark added!", it) }
                    .onFailure { showError("Watermark failed: ${it.message}") }
            }
        }
    }

    private fun doRotate() {
        val f = need() ?: return
        AlertDialog.Builder(this)
            .setTitle("Rotate all pages")
            .setItems(arrayOf("90° right","180°","90° left")) { _, which ->
                val deg = when (which) { 0 -> 90; 1 -> 180; else -> 270 }
                val out = outFile("rotated")
                run("Rotating…") {
                    val n = pageCount(f)
                    pdfOps.rotatePages(f, out, (1..n).associateWith { deg })
                        .onSuccess { showDone("✅ Rotated!", it) }
                        .onFailure { showError("Rotate failed: ${it.message}") }
                }
            }.show()
    }

    private fun doDeletePages() {
        val f = need() ?: return
        dialog("Delete Pages", "Page numbers (e.g. 1,3,5)") { input ->
            val pages = input.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (pages.isEmpty()) { toast("Enter page numbers"); return@dialog }
            val out = outFile("deleted")
            run("Deleting pages…") {
                pdfOps.deletePages(f, out, pages)
                    .onSuccess { showDone("✅ Pages deleted!", it) }
                    .onFailure { showError("Delete failed: ${it.message}") }
            }
        }
    }

    private fun doPageNumbers() {
        val f   = need() ?: return
        val out = outFile("numbered")
        run("Adding page numbers…") {
            pdfOps.addPageNumbers(f, out)
                .onSuccess { showDone("✅ Page numbers added!", it) }
                .onFailure { showError("Failed: ${it.message}") }
        }
    }

    private fun doPdfToImages() {
        val f   = need() ?: return
        val dir = getExternalFilesDir(null) ?: cacheDir
        run("Exporting pages as JPG…") {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    f, android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val r = android.graphics.pdf.PdfRenderer(pfd)
                var count = 0
                for (i in 0 until r.pageCount) {
                    val pg  = r.openPage(i)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        pg.width * 2, pg.height * 2,
                        android.graphics.Bitmap.Config.RGB_565
                    )
                    pg.render(bmp, null, null,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    val img = File(dir, "${f.nameWithoutExtension}_p${i+1}.jpg")
                    FileOutputStream(img).use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    bmp.recycle()
                    count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    toast("✅ $count images saved to app folder")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Failed: ${e.message}") }
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
        } catch (e: Exception) { showError("Share error: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        progress.visibility = View.VISIBLE
        tvStatus.text = "⏳ $label"
        lifecycleScope.launch {
            withContext(Dispatchers.IO, block)
            progress.visibility = View.GONE
            tvStatus.text = statusText()
        }
    }

    private fun showDone(msg: String, file: File) = runOnUiThread {
        AlertDialog.Builder(this)
            .setTitle("Done!")
            .setMessage("$msg\n\nFile: ${file.name}\nSize: ${fmt(file.length())}")
            .setPositiveButton("Open") { _, _ ->
                ViewerActivity.start(this,
                    Uri.fromFile(file).also {
                        try {
                            val uri = androidx.core.content.FileProvider
                                .getUriForFile(this, "$packageName.provider", file)
                            ViewerActivity.start(this, uri)
                            return@setPositiveButton
                        } catch (_: Exception) {}
                    }
                )
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showError(msg: String) = runOnUiThread {
        progress.visibility = View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) {
            toast("File missing — please re-select")
            files.clear(); tvStatus.text = statusText()
            return null
        }
        return f
    }

    private fun copyUri(uri: Uri): File {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && col >= 0) c.getString(col) else null
            }
        } catch (_: Exception) { null }
            ?: "file_${System.currentTimeMillis()}.pdf"

        val dest = File(cacheDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        } catch (_: Exception) {}
        return dest
    }

    private fun outFile(prefix: String) =
        File(getExternalFilesDir(null) ?: cacheDir,
            "${prefix}_${System.currentTimeMillis()}.pdf")

    private fun pageCount(f: File) = try {
        val pfd = android.os.ParcelFileDescriptor.open(
            f, android.os.ParcelFileDescriptor.MODE_READ_ONLY
        )
        val r = android.graphics.pdf.PdfRenderer(pfd)
        val c = r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun statusText() =
        if (files.isEmpty()) "No file selected"
        else "✅ ${files.size} file(s) ready: ${files.joinToString { it.name }}"

    private fun fmt(b: Long) = when {
        b > 1_000_000 -> "%.1f MB".format(b / 1e6)
        b > 1_000     -> "%.1f KB".format(b / 1e3)
        else          -> "$b B"
    }

    private fun toast(m: String) =
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dialog(title: String, hint: String, cb: (String) -> Unit) {
        val et = EditText(this).apply {
            this.hint = hint
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun card(title: String, buttons: List<View>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(10), 0, 0)
            }
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(this@ToolsActivity).apply {
                text = title; textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF222222.toInt())
                setPadding(0, 0, 0, dp(8))
            })
            buttons.forEach { addView(it) }
        }

    private fun btn(label: String, color: Int, action: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(6))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { action() }
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
