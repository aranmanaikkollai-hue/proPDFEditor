package com.propdf.editor.ui.tools

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
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
 * ToolsActivity — All PDF operations with visible feedback.
 *
 * Key fixes:
 *  - All operations wrapped in lifecycleScope.launch + Dispatchers.IO
 *  - Progress bar shown during operations
 *  - Every result shows Toast + AlertDialog with Open/Share options
 *  - Output saved to getExternalFilesDir() — always accessible
 *  - Input files copied from content URI to cache before processing
 */
@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val selectedFiles = mutableListOf<File>()
    private lateinit var tvStatus    : TextView
    private lateinit var progressBar : ProgressBar
    private lateinit var scrollRoot  : LinearLayout

    // ── File pickers ──────────────────────────────────────────

    private val pickPdfs = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri -> selectedFiles.add(copyUriToCache(uri, "tool_${selectedFiles.size}.pdf")) }
        updateStatus()
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val imgs = uris.mapIndexed { i, uri -> copyUriToCache(uri, "img_$i.jpg") }
        val out  = outputFile("images_to_pdf")
        doOp("Converting ${uris.size} image(s) to PDF…") {
            pdfOps.imagesToPdf(imgs, out)
                .onSuccess { showResult("✅ PDF created!", it) }
                .onFailure { toast("❌ ${it.message}") }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Pre-load files passed from MainActivity
        intent.getStringArrayListExtra("pdf_uris")?.forEach { uriStr ->
            selectedFiles.add(copyUriToCache(Uri.parse(uriStr), "tool_${selectedFiles.size}.pdf"))
        }

        buildUI()
    }

    // ── UI construction ───────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this)
        scrollRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(32))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(scrollRoot)
        setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(4), 0, dp(4))
            text = statusText()
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0, dp(4), 0, dp(4)) }
            visibility = View.GONE
            isIndeterminate = true
        }

        // ── Select files ──────────────────────────────────────
        scrollRoot.addView(card("📁  Files", listOf(
            btn("Select PDF File(s)", 0xFF1A73E8.toInt()) { pickPdfs.launch("application/pdf") },
            btn("Clear", 0xFF757575.toInt()) { selectedFiles.clear(); updateStatus() }
        )))
        scrollRoot.addView(tvStatus)
        scrollRoot.addView(progressBar)

        // ── PDF operations ────────────────────────────────────
        scrollRoot.addView(card("🔧  PDF Operations", listOf(
            btn("Merge PDFs (select 2+)", 0xFF2E7D32.toInt())  { doMerge() },
            btn("Split by Page Range",    0xFF1565C0.toInt())  { doSplit() },
            btn("Compress",               0xFF6A1B9A.toInt())  { doCompress() },
            btn("Extract Pages",          0xFF00695C.toInt())  { doExtract() }
        )))

        // ── Security ──────────────────────────────────────────
        scrollRoot.addView(card("🔒  Security", listOf(
            btn("Password Protect",  0xFFB71C1C.toInt()) { doEncrypt() },
            btn("Remove Password",   0xFFE65100.toInt()) { doDecrypt() },
            btn("Add Watermark",     0xFF004D40.toInt()) { doWatermark() }
        )))

        // ── Page tools ────────────────────────────────────────
        scrollRoot.addView(card("📄  Page Tools", listOf(
            btn("Rotate Pages",      0xFF37474F.toInt()) { doRotate() },
            btn("Delete Pages",      0xFFC62828.toInt()) { doDeletePages() },
            btn("Add Page Numbers",  0xFF1A237E.toInt()) { doPageNumbers() },
            btn("Add Header/Footer", 0xFF33691E.toInt()) { doHeaderFooter() }
        )))

        // ── Convert ───────────────────────────────────────────
        scrollRoot.addView(card("🔄  Convert", listOf(
            btn("Images → PDF",        0xFF1A237E.toInt()) { pickImages.launch("image/*") },
            btn("PDF → Images",        0xFF4A148C.toInt()) { doPdfToImages() },
            btn("Extract Text (OCR)",  0xFF006064.toInt()) { doOcr() }
        )))

        // ── Scan & print ──────────────────────────────────────
        scrollRoot.addView(card("📷  Scan & Print", listOf(
            btn("Scan Document", 0xFF0D47A1.toInt()) { openScanner() },
            btn("Print PDF",     0xFF37474F.toInt()) { doPrint() },
            btn("Share PDF",     0xFF00897B.toInt()) { doShare() }
        )))
    }

    // ── Operations ────────────────────────────────────────────

    private fun doMerge() {
        if (selectedFiles.size < 2) { toast("Select at least 2 PDFs"); return }
        val out = outputFile("merged")
        doOp("Merging ${selectedFiles.size} PDFs…") {
            pdfOps.mergePdfs(selectedFiles, out)
                .onSuccess { showResult("✅ Merged (${fmtSize(it.length())})", it) }
                .onFailure { toast("❌ Merge failed: ${it.message}") }
        }
    }

    private fun doSplit() {
        val f = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 1-3, 4-6"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Split (e.g. 1-3, 4-6)").setView(et)
            .setPositiveButton("Split") { _, _ ->
                val ranges = et.text.toString().split(",").mapNotNull { part ->
                    val p = part.trim().split("-")
                    if (p.size == 2) (p[0].trim().toIntOrNull() ?: return@mapNotNull null)..(p[1].trim().toIntOrNull() ?: return@mapNotNull null)
                    else null
                }
                if (ranges.isEmpty()) { toast("Invalid range"); return@setPositiveButton }
                val out = getExternalFilesDir(null) ?: cacheDir
                doOp("Splitting…") {
                    pdfOps.splitPdf(f, out, ranges)
                        .onSuccess { files -> toast("✅ Split into ${files.size} files") }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val f   = requireFile() ?: return
        val out = outputFile("compressed")
        doOp("Compressing…") {
            pdfOps.compressPdf(f, out)
                .onSuccess {
                    val saved = if (f.length() > 0) ((f.length() - it.length()) * 100 / f.length()) else 0L
                    showResult("✅ Compressed ($saved% smaller)", it)
                }
                .onFailure { toast("❌ ${it.message}") }
        }
    }

    private fun doExtract() {
        val f  = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 2-5"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Extract Pages (e.g. 2-5)").setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val parts = et.text.toString().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                doOp("Extracting pages $from-$to…") {
                    val dir = getExternalFilesDir(null) ?: cacheDir
                    pdfOps.splitPdf(f, dir, listOf(from..to))
                        .onSuccess { files -> files.firstOrNull()?.let { showResult("✅ Extracted!", it) } }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val f   = requireFile() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val et1 = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint = "Confirm password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(TextView(this).apply { text = "Set Password:" }); lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("🔒 Password Protect").setView(lay)
            .setPositiveButton("Protect") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isEmpty()) { toast("Enter a password"); return@setPositiveButton }
                if (pw != et2.text.toString()) { toast("Passwords don't match"); return@setPositiveButton }
                val out = outputFile("protected")
                doOp("Encrypting…") {
                    pdfOps.encryptPdf(f, out, pw, pw)
                        .onSuccess { showResult("✅ Protected!", it) }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val f  = requireFile() ?: return
        val et = EditText(this).apply { hint = "Current password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Unlock") { _, _ ->
                val out = outputFile("unlocked")
                doOp("Removing password…") {
                    pdfOps.removePdfPassword(f, out, et.text.toString())
                        .onSuccess { showResult("✅ Unlocked!", it) }
                        .onFailure { toast("❌ Wrong password: ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val f   = requireFile() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val etT = EditText(this).apply { setText("CONFIDENTIAL") }
        val etO = EditText(this).apply { setText("30"); hint = "Opacity %" }
        lay.addView(TextView(this).apply { text = "Watermark text:" }); lay.addView(etT)
        lay.addView(TextView(this).apply { text = "Opacity (1-100):" }); lay.addView(etO)
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val out  = outputFile("watermarked")
                val opac = (etO.text.toString().toIntOrNull() ?: 30).coerceIn(1, 100) / 100f
                doOp("Adding watermark…") {
                    pdfOps.addTextWatermark(f, out, etT.text.toString(), opac)
                        .onSuccess { showResult("✅ Watermark added!", it) }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRotate() {
        val f = requireFile() ?: return
        val options = arrayOf("All pages 90°", "All pages 180°", "All pages 270°")
        AlertDialog.Builder(this).setTitle("Rotate").setItems(options) { _, which ->
            val deg = when (which) { 0 -> 90; 1 -> 180; else -> 270 }
            val out = outputFile("rotated")
            doOp("Rotating…") {
                val count = try {
                    val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    val r   = android.graphics.pdf.PdfRenderer(pfd)
                    val c   = r.pageCount; r.close(); pfd.close(); c
                } catch (_: Exception) { 0 }
                val pages = (1..count).associateWith { deg }
                pdfOps.rotatePages(f, out, pages)
                    .onSuccess { showResult("✅ Rotated!", it) }
                    .onFailure { toast("❌ ${it.message}") }
            }
        }.show()
    }

    private fun doDeletePages() {
        val f  = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 1,3,5"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                val out = outputFile("deleted")
                doOp("Deleting pages…") {
                    pdfOps.deletePages(f, out, pages)
                        .onSuccess { showResult("✅ Done!", it) }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPageNumbers() {
        val f   = requireFile() ?: return
        val out = outputFile("numbered")
        doOp("Adding page numbers…") {
            pdfOps.addPageNumbers(f, out)
                .onSuccess { showResult("✅ Page numbers added!", it) }
                .onFailure { toast("❌ ${it.message}") }
        }
    }

    private fun doHeaderFooter() {
        val f   = requireFile() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val etH = EditText(this).apply { hint = "Header (blank = skip)" }
        val etF = EditText(this).apply { hint = "Footer (blank = skip)" }
        lay.addView(TextView(this).apply { text = "Header:" }); lay.addView(etH)
        lay.addView(TextView(this).apply { text = "Footer:" }); lay.addView(etF)
        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val out = outputFile("header_footer")
                doOp("Adding…") {
                    pdfOps.addHeaderFooter(f, out, etH.text.toString().ifBlank { null }, etF.text.toString().ifBlank { null })
                        .onSuccess { showResult("✅ Done!", it) }
                        .onFailure { toast("❌ ${it.message}") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val f   = requireFile() ?: return
        val dir = getExternalFilesDir(null) ?: cacheDir
        doOp("Exporting pages as images…") {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = android.graphics.pdf.PdfRenderer(pfd)
                var count = 0
                for (i in 0 until r.pageCount) {
                    val page = r.openPage(i)
                    val bmp  = android.graphics.Bitmap.createBitmap(
                        page.width * 2, page.height * 2, android.graphics.Bitmap.Config.RGB_565
                    )
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    val img = File(dir, "${f.nameWithoutExtension}_p${i + 1}.jpg")
                    java.io.FileOutputStream(img).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) { toast("✅ $count images saved to app folder") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun doOcr() {
        val f   = requireFile() ?: return
        val out = File(getExternalFilesDir(null) ?: cacheDir, "${f.nameWithoutExtension}_ocr.txt")
        doOp("Running OCR…") {
            try {
                val sb  = StringBuilder("OCR — ${f.name}\n${"-".repeat(40)}\n")
                val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = android.graphics.pdf.PdfRenderer(pfd)
                for (i in 0 until minOf(r.pageCount, 10)) {
                    val page = r.openPage(i)
                    val bmp  = android.graphics.Bitmap.createBitmap(
                        page.width * 2, page.height * 2, android.graphics.Bitmap.Config.RGB_565
                    )
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    sb.appendLine("\n--- Page ${i + 1} ---")
                    try {
                        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                        )
                        val task = recognizer.process(
                            com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
                        )
                        while (!task.isComplete) Thread.sleep(50)
                        sb.appendLine(if (task.isSuccessful) (task.result?.text ?: "(empty)") else "(OCR failed)")
                    } catch (e: Exception) {
                        sb.appendLine("(ML Kit error: ${e.message})")
                    }
                    bmp.recycle()
                }
                r.close(); pfd.close()
                out.writeText(sb.toString())
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity)
                        .setTitle("OCR Result")
                        .setMessage(sb.toString().take(600) + "\n\n[Saved: ${out.name}]")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Share") { _, _ ->
                            startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) },
                                "Share text"
                            ))
                        }.show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ OCR error: ${e.message}") }
            }
        }
    }

    private fun openScanner() =
        startActivity(Intent(this, com.propdf.editor.ui.scanner.DocumentScannerActivity::class.java))

    private fun doPrint() {
        val f = requireFile() ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) {
            toast("Print requires Android 4.4+"); return
        }
        try {
            val pm = getSystemService(PRINT_SERVICE) as PrintManager
            pm.print(f.nameWithoutExtension, object : android.print.PrintDocumentAdapter() {
                override fun onLayout(o: android.print.PrintAttributes?, n: android.print.PrintAttributes?, t: android.os.CancellationSignal?, cb: LayoutResultCallback?, b: android.os.Bundle?) {
                    cb?.onLayoutFinished(android.print.PrintDocumentInfo.Builder(f.name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                }
                override fun onWrite(p: Array<out android.print.PageRange>?, d: android.os.ParcelFileDescriptor?, t: android.os.CancellationSignal?, cb: WriteResultCallback?) {
                    try {
                        java.io.FileInputStream(f).use { i -> java.io.FileOutputStream(d!!.fileDescriptor).use { i.copyTo(it) } }
                        cb?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Exception) { cb?.onWriteFailed(e.message) }
                }
            }, null)
        } catch (e: Exception) { toast("Print error: ${e.message}") }
    }

    private fun doShare() {
        val f = requireFile() ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"
            ))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Launch a coroutine that shows progress bar and runs block on IO. */
    private fun doOp(label: String, block: suspend CoroutineScope.() -> Unit) {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text = "⏳ $label"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { block() }
            progressBar.visibility = View.GONE
            tvStatus.text = statusText()
        }
    }

    private fun showResult(msg: String, file: File) {
        runOnUiThread {
            toast(msg)
            AlertDialog.Builder(this)
                .setTitle("Done!")
                .setMessage("$msg\n\nFile: ${file.name}\nSize: ${fmtSize(file.length())}\n\nSaved in app folder.")
                .setPositiveButton("Open") { _, _ ->
                    val uri = try {
                        androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                    } catch (_: Exception) { Uri.fromFile(file) }
                    startActivity(Intent(this, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_PDF_URI, uri.toString()))
                }
                .setNeutralButton("Share") { _, _ ->
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share"
                        ))
                    } catch (e: Exception) { toast("Share error: ${e.message}") }
                }
                .setNegativeButton("OK", null).show()
        }
    }

    private fun requireFile(): File? {
        if (selectedFiles.isEmpty()) { toast("Please select a PDF file first"); return null }
        return selectedFiles.first().also {
            if (!it.exists()) { toast("File not found — please re-select"); selectedFiles.clear(); updateStatus(); return null }
        }
    }

    /** Copy any URI to cache. Supports content:// and file://. */
    private fun copyUriToCache(uri: Uri, name: String): File {
        val f = File(cacheDir, name)
        try {
            if (uri.scheme == "file") {
                File(uri.path ?: "").inputStream().use { it.copyTo(FileOutputStream(f)) }
            } else {
                contentResolver.openInputStream(uri)?.use { it.copyTo(FileOutputStream(f)) }
            }
        } catch (_: Exception) {}
        return f
    }

    private fun outputFile(prefix: String) =
        File(getExternalFilesDir(null) ?: cacheDir, "${prefix}_${System.currentTimeMillis()}.pdf")

    private fun updateStatus() { tvStatus.text = statusText() }
    private fun statusText()   = if (selectedFiles.isEmpty()) "No file selected" else "✅ ${selectedFiles.size} file(s) ready"
    private fun fmtSize(b: Long) = when { b > 1_000_000 -> "%.1f MB".format(b/1e6); b > 1_000 -> "%.1f KB".format(b/1e3); else -> "$b B" }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
                setTextColor(0xFF333333.toInt())
                setPadding(0, 0, 0, dp(8))
            })
            buttons.forEach { addView(it) }
        }

    private fun btn(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(color)
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
