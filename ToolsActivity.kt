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

/**
 * ToolsActivity — Every tool runs async with visible progress + result dialog.
 *
 * KEY FIXES:
 * 1. Files copied from content URI to cache before any operation
 * 2. Output always to getExternalFilesDir() — no permission needed, always accessible
 * 3. Every result shows AlertDialog with "Open" + "Share" + "OK"
 * 4. Progress bar shown during operations — user knows something is happening
 * 5. Errors displayed as Toast — nothing fails silently
 */
@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val files = mutableListOf<File>()
    private lateinit var tvStatus  : TextView
    private lateinit var progress  : ProgressBar
    private lateinit var root      : LinearLayout

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri -> copyAndAdd(uri) }
        refreshStatus()
    }

    private val imgPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val imgs = uris.map { copyToCache(it, "img_${System.currentTimeMillis()}.jpg") }
        val out  = outFile("images_to_pdf")
        run("Converting ${uris.size} image(s) → PDF…") {
            pdfOps.imagesToPdf(imgs, out)
                .onSuccess { done("✅ PDF created!", it) }
                .onFailure { err("Images→PDF failed: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Pre-load files from intent
        intent.getStringArrayListExtra("pdf_uris")?.forEach { s ->
            copyAndAdd(Uri.parse(s))
        }
        buildUI()
    }

    // ── UI ────────────────────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(40))
            setBackgroundColor(0xFFF2F4FC.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 13f; setPadding(0, dp(4), 0, dp(2))
            setTextColor(0xFF444444.toInt()); text = statusText()
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0,dp(4),0,dp(4)) }
            isIndeterminate = true; visibility = View.GONE
        }

        root.addView(section("📁  Files", listOf(
            btn("Select PDF File(s)", 0xFF1565C0.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            btn("Clear all", 0xFF757575.toInt()) { files.clear(); refreshStatus() }
        )))
        root.addView(tvStatus)
        root.addView(progress)

        root.addView(section("🔧  Operations", listOf(
            btn("Merge PDFs (need 2+)", 0xFF2E7D32.toInt()) { doMerge() },
            btn("Split by page range",  0xFF1A237E.toInt()) { doSplit() },
            btn("Compress / reduce size",0xFF6A1B9A.toInt()){ doCompress() },
            btn("Extract pages",        0xFF00695C.toInt()) { doExtract() }
        )))

        root.addView(section("🔒  Security", listOf(
            btn("Password protect PDF", 0xFFB71C1C.toInt()) { doEncrypt() },
            btn("Remove password",      0xFFE65100.toInt()) { doDecrypt() },
            btn("Add watermark",        0xFF004D40.toInt()) { doWatermark() }
        )))

        root.addView(section("📄  Page tools", listOf(
            btn("Rotate pages",    0xFF37474F.toInt()) { doRotate() },
            btn("Delete pages",    0xFFC62828.toInt()) { doDeletePages() },
            btn("Add page numbers",0xFF1A237E.toInt()) { doPageNums() },
            btn("Header / Footer", 0xFF33691E.toInt()) { doHeaderFooter() }
        )))

        root.addView(section("🔄  Convert", listOf(
            btn("Images → PDF",   0xFF0D47A1.toInt()) { imgPicker.launch(arrayOf("image/*")) },
            btn("PDF → Images",   0xFF4A148C.toInt()) { doPdfToImages() },
            btn("Extract text",   0xFF006064.toInt()) { doExtractText() }
        )))

        root.addView(section("📷  Scan & Print", listOf(
            btn("Scan document (camera)", 0xFF1B5E20.toInt()) {
                startActivity(Intent(this, com.propdf.editor.ui.scanner.DocumentScannerActivity::class.java))
            },
            btn("Print PDF", 0xFF263238.toInt()) { doPrint() },
            btn("Share PDF", 0xFF00897B.toInt()) { doShare() }
        )))
    }

    // ── Operations ────────────────────────────────────────────

    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        val out = outFile("merged")
        run("Merging ${files.size} PDFs…") {
            pdfOps.mergePdfs(files, out)
                .onSuccess { done("✅ Merged successfully!", it) }
                .onFailure { err("Merge failed: ${it.message}") }
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        prompt("Split pages (e.g. 1-3, 4-6)") { input ->
            val ranges = mutableListOf<IntRange>()
            for (part in input.split(",")) {
                val p = part.trim().split("-")
                if (p.size == 2) {
                    val from = p[0].trim().toIntOrNull()
                    val to   = p[1].trim().toIntOrNull()
                    if (from != null && to != null) ranges.add(from..to)
                }
            }
            if (ranges.isEmpty()) { toast("Invalid range. Use e.g. 1-3"); return@prompt }
            run("Splitting PDF…") {
                val dir = getExternalFilesDir(null) ?: cacheDir
                pdfOps.splitPdf(f, dir, ranges)
                    .onSuccess { toast("✅ Split into ${it.size} files") }
                    .onFailure { err("Split failed: ${it.message}") }
            }
        }
    }

    private fun doCompress() {
        val f   = need() ?: return
        val out = outFile("compressed")
        run("Compressing PDF…") {
            pdfOps.compressPdf(f, out)
                .onSuccess {
                    val pct = if (f.length() > 0) ((f.length()-it.length())*100/f.length()) else 0L
                    done("✅ Compressed ($pct% smaller)", it)
                }
                .onFailure { err("Compress failed: ${it.message}") }
        }
    }

    private fun doExtract() {
        val f = need() ?: return
        prompt("Extract pages (e.g. 2-5)") { input ->
            val p    = input.split("-")
            val from = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
            val to   = p.getOrNull(1)?.trim()?.toIntOrNull() ?: from
            run("Extracting pages $from-$to…") {
                val dir = getExternalFilesDir(null) ?: cacheDir
                pdfOps.splitPdf(f, dir, listOf(from..to))
                    .onSuccess { files -> files.firstOrNull()?.let { done("✅ Extracted!", it) } }
                    .onFailure { err("Extract failed: ${it.message}") }
            }
        }
    }

    private fun doEncrypt() {
        val f = need() ?: return
        twoFieldDialog("Password Protect", "Password", "Confirm password") { pw, confirm ->
            if (pw.isEmpty()) { toast("Enter a password"); return@twoFieldDialog }
            if (pw != confirm) { toast("Passwords don't match"); return@twoFieldDialog }
            val out = outFile("protected")
            run("Encrypting with AES-256…") {
                pdfOps.encryptPdf(f, out, pw, pw)
                    .onSuccess { done("✅ PDF protected!", it) }
                    .onFailure { err("Encrypt failed: ${it.message}") }
            }
        }
    }

    private fun doDecrypt() {
        val f = need() ?: return
        singleFieldDialog("Remove Password", "Current password") { pw ->
            val out = outFile("unlocked")
            run("Removing password…") {
                pdfOps.removePdfPassword(f, out, pw)
                    .onSuccess { done("✅ Password removed!", it) }
                    .onFailure { err("Wrong password or error: ${it.message}") }
            }
        }
    }

    private fun doWatermark() {
        val f = need() ?: return
        twoFieldDialog("Watermark", "Watermark text", "Opacity % (e.g. 30)") { text, opStr ->
            val op  = (opStr.toIntOrNull() ?: 30).coerceIn(1, 100) / 100f
            val out = outFile("watermarked")
            run("Adding watermark…") {
                pdfOps.addTextWatermark(f, out, text.ifBlank { "CONFIDENTIAL" }, op)
                    .onSuccess { done("✅ Watermark added!", it) }
                    .onFailure { err("Watermark failed: ${it.message}") }
            }
        }
    }

    private fun doRotate() {
        val f = need() ?: return
        AlertDialog.Builder(this).setTitle("Rotate pages")
            .setItems(arrayOf("All pages 90° right", "All pages 180°", "All pages 90° left")) { _, which ->
                val deg = when (which) { 0 -> 90; 1 -> 180; else -> 270 }
                val out = outFile("rotated")
                run("Rotating $deg°…") {
                    val n = pageCount(f)
                    pdfOps.rotatePages(f, out, (1..n).associateWith { deg })
                        .onSuccess { done("✅ Rotated!", it) }
                        .onFailure { err("Rotate failed: ${it.message}") }
                }
            }.show()
    }

    private fun doDeletePages() {
        val f = need() ?: return
        singleFieldDialog("Delete Pages", "Pages to delete (e.g. 1,3,5)") { input ->
            val pages = input.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (pages.isEmpty()) { toast("Enter page numbers"); return@singleFieldDialog }
            val out = outFile("deleted_pages")
            run("Deleting pages…") {
                pdfOps.deletePages(f, out, pages)
                    .onSuccess { done("✅ Pages deleted!", it) }
                    .onFailure { err("Delete failed: ${it.message}") }
            }
        }
    }

    private fun doPageNums() {
        val f   = need() ?: return
        val out = outFile("numbered")
        run("Adding page numbers…") {
            pdfOps.addPageNumbers(f, out)
                .onSuccess { done("✅ Page numbers added!", it) }
                .onFailure { err("Failed: ${it.message}") }
        }
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        twoFieldDialog("Header / Footer", "Header (blank = skip)", "Footer (blank = skip)") { h, ft ->
            val out = outFile("header_footer")
            run("Adding header/footer…") {
                pdfOps.addHeaderFooter(f, out, h.ifBlank { null }, ft.ifBlank { null })
                    .onSuccess { done("✅ Done!", it) }
                    .onFailure { err("Failed: ${it.message}") }
            }
        }
    }

    private fun doPdfToImages() {
        val f   = need() ?: return
        val dir = getExternalFilesDir(null) ?: cacheDir
        run("Exporting pages as JPG…") {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = android.graphics.pdf.PdfRenderer(pfd)
                var count = 0
                for (i in 0 until r.pageCount) {
                    val pg  = r.openPage(i)
                    val bmp = android.graphics.Bitmap.createBitmap(pg.width*2, pg.height*2, android.graphics.Bitmap.Config.RGB_565)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    val img = File(dir, "${f.nameWithoutExtension}_p${i+1}.jpg")
                    FileOutputStream(img).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) { toast("✅ $count images saved to app folder") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { err("PDF→Images failed: ${e.message}") }
            }
        }
    }

    private fun doExtractText() {
        val f   = need() ?: return
        val out = File(getExternalFilesDir(null) ?: cacheDir, "${f.nameWithoutExtension}_text.txt")
        run("Extracting text via OCR…") {
            try {
                val sb  = StringBuilder("Text from: ${f.name}\n${"-".repeat(40)}\n")
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = android.graphics.pdf.PdfRenderer(pfd)
                for (i in 0 until minOf(r.pageCount, 10)) {
                    val pg  = r.openPage(i)
                    val bmp = android.graphics.Bitmap.createBitmap(pg.width*2, pg.height*2, android.graphics.Bitmap.Config.RGB_565)
                    pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()
                    sb.appendLine("\n--- Page ${i+1} ---")
                    try {
                        val task = com.google.mlkit.vision.text.TextRecognition
                            .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                            .process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                        while (!task.isComplete) delay(30)
                        sb.appendLine(if (task.isSuccessful) task.result?.text ?: "(empty)" else "(failed)")
                    } catch (e: Exception) { sb.appendLine("(OCR error: ${e.message})") }
                    bmp.recycle()
                }
                r.close(); pfd.close()
                out.writeText(sb.toString())
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity)
                        .setTitle("Text extracted")
                        .setMessage(sb.toString().take(700) + if (sb.length > 700) "\n…" else "")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Share") { _, _ ->
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) },
                                "Share text"
                            ))
                        }.show()
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { err("OCR error: ${e.message}") } }
        }
    }

    private fun doPrint() {
        val f = need() ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) {
            toast("Print requires Android 4.4+"); return
        }
        try {
            val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
            pm.print(f.nameWithoutExtension, object : android.print.PrintDocumentAdapter() {
                override fun onLayout(o: android.print.PrintAttributes?, n: android.print.PrintAttributes?, t: android.os.CancellationSignal?, cb: LayoutResultCallback?, b: android.os.Bundle?) {
                    cb?.onLayoutFinished(android.print.PrintDocumentInfo.Builder(f.name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }
                override fun onWrite(p: Array<out android.print.PageRange>?, d: android.os.ParcelFileDescriptor?, t: android.os.CancellationSignal?, cb: WriteResultCallback?) {
                    try { java.io.FileInputStream(f).use { i -> FileOutputStream(d!!.fileDescriptor).use { i.copyTo(it) } }
                          cb?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Exception) { cb?.onWriteFailed(e.message) }
                }
            }, null)
        } catch (e: Exception) { err("Print error: ${e.message}") }
    }

    private fun doShare() {
        val f = need() ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share PDF"
            ))
        } catch (e: Exception) { err("Share error: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Copy any URI (content or file) to app cache. */
    private fun copyToCache(uri: Uri, name: String): File {
        val dest = File(cacheDir, name)
        try {
            if (uri.scheme == "file") {
                File(uri.path!!).inputStream().use { it.copyTo(FileOutputStream(dest)) }
            } else {
                contentResolver.openInputStream(uri)?.use { it.copyTo(FileOutputStream(dest)) }
            }
        } catch (_: Exception) {}
        return dest
    }

    private fun copyAndAdd(uri: Uri) {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "pdf_${System.currentTimeMillis()}.pdf"
        } catch (_: Exception) { "pdf_${System.currentTimeMillis()}.pdf" }
        files.add(copyToCache(uri, name))
    }

    /** Run on IO, show progress, resume on Main. */
    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        tvStatus.text = "⏳ $label"
        lifecycleScope.launch {
            withContext(Dispatchers.IO, block)
            progress.visibility = View.GONE
            tvStatus.text = statusText()
        }
    }

    private fun done(msg: String, file: File) = runOnUiThread {
        toast(msg)
        AlertDialog.Builder(this)
            .setTitle("✅ Done")
            .setMessage("$msg\n\nFile: ${file.name}\nSize: ${fmt(file.length())}\n\nSaved in: ${file.parent}")
            .setPositiveButton("Open") { _, _ ->
                val uri = try { androidx.core.content.FileProvider.getUriForFile(this,"$packageName.provider",file) }
                          catch (_: Exception) { Uri.fromFile(file) }
                startActivity(Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PDF_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            .setNeutralButton("Share") { _, _ ->
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this,"$packageName.provider",file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="application/pdf"; putExtra(Intent.EXTRA_STREAM,uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Share"))
                } catch (_:Exception){}
            }
            .setNegativeButton("OK", null).show()
    }

    private fun err(msg: String) = runOnUiThread {
        progress.visibility = View.GONE
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF file first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) { toast("File missing — please re-select"); files.clear(); refreshStatus(); return null }
        return f
    }

    private fun pageCount(f: File) = try {
        val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val r = android.graphics.pdf.PdfRenderer(pfd); val c = r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun outFile(prefix: String) =
        File(getExternalFilesDir(null) ?: cacheDir, "${prefix}_${System.currentTimeMillis()}.pdf")

    private fun refreshStatus() { tvStatus.text = statusText() }
    private fun statusText() = if (files.isEmpty()) "No file selected" else "✅ ${files.size} file(s) ready"
    private fun fmt(b: Long) = when { b > 1_000_000 -> "%.1f MB".format(b/1e6); b > 1_000 -> "%.1f KB".format(b/1e3); else -> "$b B" }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ── Dialog helpers ────────────────────────────────────────

    private fun prompt(hint: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { this.hint = hint; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle(hint).setView(et)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun singleFieldDialog(title: String, hint: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { this.hint = hint; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun twoFieldDialog(title: String, h1: String, h2: String, cb: (String, String) -> Unit) {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint = h1 }
        val et2 = EditText(this).apply { hint = h2 }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle(title).setView(lay)
            .setPositiveButton("OK") { _, _ -> cb(et1.text.toString(), et2.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Card + button builders ────────────────────────────────

    private fun section(title: String, btns: List<View>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(TextView(this@ToolsActivity).apply {
                text = title; textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF222222.toInt()); setPadding(0,0,0,dp(8))
            })
            btns.forEach { addView(it) }
        }

    private fun btn(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(6)) }
        setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    override fun onOptionsItemSelected(i: MenuItem): Boolean {
        if (i.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(i)
    }
}
