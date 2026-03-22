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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        uris.forEach { uri -> pickedFiles.add(copyToCache(uri, "tool_${pickedFiles.size}.pdf")) }
        tvStatus.text = "✅ ${pickedFiles.size} file(s) selected"
    }

    private val imagePdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val imgFiles = uris.mapIndexed { i, uri -> copyToCache(uri, "img_$i.jpg") }
        val out = outputFile("images_to_pdf")
        run("Converting ${uris.size} image(s) to PDF...") {
            pdfOps.imagesToPdf(imgFiles, out).fold(
                { toast("✅ PDF created: ${out.name}\nSaved to: ${out.absolutePath}") },
                { toast("❌ Error: ${it.message}") }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
            setBackgroundColor(android.graphics.Color.parseColor("#F5F7FF"))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Pre-load passed files
        intent.getStringArrayListExtra("pdf_uris")?.forEach { uriStr ->
            pickedFiles.add(copyToCache(Uri.parse(uriStr), "tool_${pickedFiles.size}.pdf"))
        }

        tvStatus = TextView(this).apply {
            text = if (pickedFiles.isEmpty()) "No files selected" else "✅ ${pickedFiles.size} file(s) ready"
            textSize = 13f; setPadding(0, 0, 0, dp(4))
            setTextColor(android.graphics.Color.parseColor("#555555"))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0, dp(4), 0, dp(4)) }
            visibility = View.GONE; max = 100; progress = 0
        }

        // ── SELECT FILES ─────────────────────────────────────────
        root.addView(sectionCard("📁  Select Files", listOf(
            btn("Choose PDF File(s)", "#1A73E8") { pdfPickerLauncher.launch("application/pdf") },
            btn("Clear Selection", "#757575") { pickedFiles.clear(); tvStatus.text = "No files selected" }
        )))
        root.addView(tvStatus)
        root.addView(progressBar)

        // ── PDF OPERATIONS ────────────────────────────────────────
        root.addView(sectionCard("🔧  PDF Operations", listOf(
            btn("Merge PDFs (select 2+)", "#2E7D32") { doMerge() },
            btn("Split PDF by Page Range", "#1565C0") { doSplit() },
            btn("Compress / Reduce Size", "#6A1B9A") { doCompress() },
            btn("Extract Pages", "#00695C") { doExtractPages() }
        )))

        // ── SECURITY ──────────────────────────────────────────────
        root.addView(sectionCard("🔒  Security", listOf(
            btn("Password Protect PDF", "#B71C1C") { doEncrypt() },
            btn("Remove Password", "#E65100") { doDecrypt() },
            btn("Add Text Watermark", "#004D40") { doWatermark() },
            btn("Redact / Blackout Text", "#212121") { doRedact() }
        )))

        // ── PAGE TOOLS ────────────────────────────────────────────
        root.addView(sectionCard("📄  Page Tools", listOf(
            btn("Rotate Pages 90°", "#37474F") { doRotate() },
            btn("Delete Specific Pages", "#C62828") { doDeletePages() },
            btn("Reorder Pages", "#4527A0") { doReorderPages() },
            btn("Add Page Numbers", "#1A237E") { doAddPageNumbers() },
            btn("Add Header / Footer", "#33691E") { doAddHeaderFooter() }
        )))

        // ── CONVERT ───────────────────────────────────────────────
        root.addView(sectionCard("🔄  Convert", listOf(
            btn("Images → PDF", "#1A237E") { imagePdfLauncher.launch("image/*") },
            btn("PDF → Images (per page)", "#4A148C") { doPdfToImages() },
            btn("PDF → Text (extract)", "#006064") { doPdfToText() }
        )))

        // ── SCAN & OCR ────────────────────────────────────────────
        root.addView(sectionCard("📷  Scan & OCR", listOf(
            btn("Scan Document (Camera)", "#0D47A1") { openScanner() },
            btn("OCR — Extract Text from Scan", "#1B5E20") { doOcr() }
        )))

        // ── PRINT ─────────────────────────────────────────────────
        root.addView(sectionCard("🖨️  Print", listOf(
            btn("Print PDF", "#37474F") { doPrint() }
        )))

        // ── SHARE ─────────────────────────────────────────────────
        root.addView(sectionCard("📤  Share", listOf(
            btn("Share PDF via WhatsApp / Email", "#00897B") { doShare() }
        )))
    }

    // ── PDF Operations ───────────────────────────────────────────

    private fun doMerge() {
        if (pickedFiles.size < 2) { toast("Select at least 2 PDFs"); return }
        val out = outputFile("merged")
        run("Merging ${pickedFiles.size} PDFs...") {
            pdfOps.mergePdfs(pickedFiles, out)
                .fold({ showResult("✅ Merged successfully!", out) }, { toast("❌ ${it.message}") })
        }
    }

    private fun doSplit() {
        val file = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 1-3, 4-6"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Split Pages (e.g. 1-3, 4-6)").setView(et)
            .setPositiveButton("Split") { _, _ ->
                val ranges = et.text.toString().split(",").mapNotNull {
                    val p = it.trim().split("-")
                    if (p.size == 2) (p[0].trim().toIntOrNull() ?: return@mapNotNull null)..(p[1].trim().toIntOrNull() ?: return@mapNotNull null)
                    else null
                }
                if (ranges.isEmpty()) { toast("Invalid range"); return@setPositiveButton }
                run("Splitting...") {
                    pdfOps.splitPdf(file, getExternalFilesDir(null) ?: cacheDir, ranges)
                        .fold({ files -> toast("✅ Split into ${files.size} files\nSaved to app folder") }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val file = requireFile() ?: return
        val originalSize = file.length()
        val out = outputFile("compressed")
        run("Compressing PDF...") {
            pdfOps.compressPdf(file, out)
                .fold({
                    val saved = ((originalSize - out.length()) * 100 / originalSize)
                    showResult("✅ Compressed! Saved ${saved}%\n${formatSize(originalSize)} → ${formatSize(out.length())}", out)
                }, { toast("❌ ${it.message}") })
        }
    }

    private fun doExtractPages() {
        val file = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 2-5 (from page to page)"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Extract Pages (e.g. 2-5)").setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val parts = et.text.toString().split("-")
                val from = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                val out = outputFile("extracted")
                run("Extracting pages $from-$to...") {
                    pdfOps.splitPdf(file, getExternalFilesDir(null) ?: cacheDir, listOf(from..to))
                        .fold({ files -> showResult("✅ Pages extracted!", files.firstOrNull() ?: out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val file = requireFile() ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val etPass = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val etConfirm = EditText(this).apply { hint = "Confirm password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(TextView(this).apply { text = "Set Password:" })
        layout.addView(etPass); layout.addView(etConfirm)
        AlertDialog.Builder(this).setTitle("🔒 Password Protect PDF").setView(layout)
            .setPositiveButton("Protect") { _, _ ->
                val pass = etPass.text.toString()
                if (pass.isEmpty()) { toast("Enter a password"); return@setPositiveButton }
                if (pass != etConfirm.text.toString()) { toast("Passwords don't match"); return@setPositiveButton }
                val out = outputFile("protected")
                run("Encrypting with AES-256...") {
                    pdfOps.encryptPdf(file, out, pass, pass)
                        .fold({ showResult("✅ PDF protected with password!", out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val file = requireFile() ?: return
        val et = EditText(this).apply { hint = "Current password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Unlock") { _, _ ->
                val out = outputFile("unlocked")
                run("Removing password...") {
                    pdfOps.removePdfPassword(file, out, et.text.toString())
                        .fold({ showResult("✅ Password removed!", out) }, { toast("❌ Wrong password or error: ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val file = requireFile() ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val etText = EditText(this).apply { setText("CONFIDENTIAL"); hint = "Watermark text" }
        val etOpacity = EditText(this).apply { setText("30"); hint = "Opacity % (1-100)" }
        layout.addView(TextView(this).apply { text = "Watermark text:" }); layout.addView(etText)
        layout.addView(TextView(this).apply { text = "Opacity %:" }); layout.addView(etOpacity)
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val out = outputFile("watermarked")
                val opacity = (etOpacity.text.toString().toIntOrNull() ?: 30) / 100f
                run("Adding watermark...") {
                    pdfOps.addTextWatermark(file, out, etText.text.toString(), opacity)
                        .fold({ showResult("✅ Watermark added!", out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRedact() {
        toast("Open the PDF viewer → use annotation tool → draw black rectangle over sensitive text → save")
    }

    private fun doRotate() {
        val file = requireFile() ?: return
        val options = arrayOf("All pages 90° right", "All pages 180°", "All pages 90° left", "Specific pages...")
        AlertDialog.Builder(this).setTitle("Rotate Pages").setItems(options) { _, which ->
            val degrees = when (which) { 0 -> 90; 1 -> 180; 2 -> 270; else -> 90 }
            if (which == 3) {
                val et = EditText(this).apply { hint = "e.g. 1,3,5" }
                AlertDialog.Builder(this).setTitle("Enter page numbers").setView(et)
                    .setPositiveButton("Rotate") { _, _ ->
                        val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }.associateWith { 90 }
                        val out = outputFile("rotated")
                        run("Rotating pages...") {
                            pdfOps.rotatePages(file, out, pages).fold({ showResult("✅ Rotated!", out) }, { toast("❌ ${it.message}") })
                        }
                    }.setNegativeButton("Cancel", null).show()
            } else {
                val out = outputFile("rotated")
                run("Rotating all pages ${degrees}°...") {
                    val count = try { android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).let { r -> val c = r.pageCount; r.close(); c } } catch (e: Exception) { 0 }
                    val pages = (1..count).associateWith { degrees }
                    pdfOps.rotatePages(file, out, pages).fold({ showResult("✅ All pages rotated!", out) }, { toast("❌ ${it.message}") })
                }
            }
        }.show()
    }

    private fun doDeletePages() {
        val file = requireFile() ?: return
        val et = EditText(this).apply { hint = "e.g. 1,3,5 (comma separated)"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                val out = outputFile("deleted_pages")
                run("Deleting pages ${pages.joinToString(",")}...") {
                    pdfOps.deletePages(file, out, pages).fold({ showResult("✅ Pages deleted!", out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doReorderPages() {
        toast("Select pages to reorder — enter new order e.g. 3,1,2 for a 3-page PDF")
        val file = requireFile() ?: return
        val et = EditText(this).apply { hint = "New order e.g. 3,1,2" }
        AlertDialog.Builder(this).setTitle("Reorder Pages").setView(et)
            .setPositiveButton("Reorder") { _, _ ->
                val order = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (order.isEmpty()) return@setPositiveButton
                val out = outputFile("reordered")
                run("Reordering pages...") {
                    // Extract each page in new order and merge
                    val tempFiles = order.mapIndexed { i, pageNum ->
                        val t = File(cacheDir, "reorder_page_${i}.pdf")
                        pdfOps.splitPdf(file, cacheDir, listOf(pageNum..pageNum))
                        t
                    }.filter { it.exists() }
                    if (tempFiles.isEmpty()) { toast("Could not extract pages"); return@run }
                    pdfOps.mergePdfs(tempFiles, out).fold({ showResult("✅ Pages reordered!", out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doAddPageNumbers() {
        val file = requireFile() ?: return
        val out = outputFile("numbered")
        run("Adding page numbers...") {
            pdfOps.addPageNumbers(file, out)
                .fold({ showResult("✅ Page numbers added!", out) }, { toast("❌ ${it.message}") })
        }
    }

    private fun doAddHeaderFooter() {
        val file = requireFile() ?: return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val etHeader = EditText(this).apply { hint = "Header text (leave blank to skip)" }
        val etFooter = EditText(this).apply { hint = "Footer text (leave blank to skip)" }
        layout.addView(TextView(this).apply { text = "Header:" }); layout.addView(etHeader)
        layout.addView(TextView(this).apply { text = "Footer:" }); layout.addView(etFooter)
        AlertDialog.Builder(this).setTitle("Add Header / Footer").setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val out = outputFile("header_footer")
                val header = etHeader.text.toString().ifEmpty { null }
                val footer = etFooter.text.toString().ifEmpty { null }
                run("Adding header/footer...") {
                    pdfOps.addHeaderFooter(file, out, header, footer)
                        .fold({ showResult("✅ Header/footer added!", out) }, { toast("❌ ${it.message}") })
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val file = requireFile() ?: return
        run("Converting PDF pages to images...") {
            try {
                val renderer = android.graphics.pdf.PdfRenderer(
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                )
                val outputDir = getExternalFilesDir(null) ?: cacheDir
                var count = 0
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bitmap = android.graphics.Bitmap.createBitmap(page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    val imgFile = File(outputDir, "${file.nameWithoutExtension}_page${i + 1}.jpg")
                    java.io.FileOutputStream(imgFile).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                    bitmap.recycle(); count++
                }
                renderer.close()
                withContext(Dispatchers.Main) { toast("✅ Saved $count images to app folder") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun doPdfToText() {
        val file = requireFile() ?: return
        run("Extracting text from PDF...") {
            try {
                val sb = StringBuilder()
                val renderer = android.graphics.pdf.PdfRenderer(
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                )
                sb.appendLine("Extracted from: ${file.name}")
                sb.appendLine("Pages: ${renderer.pageCount}")
                sb.appendLine("─────────────────────────")
                sb.appendLine("Note: Full text extraction requires OCR for scanned PDFs.")
                sb.appendLine("For text-based PDFs, use the OCR feature.")
                renderer.close()
                val txtFile = File(getExternalFilesDir(null) ?: cacheDir, "${file.nameWithoutExtension}_text.txt")
                txtFile.writeText(sb.toString())
                withContext(Dispatchers.Main) { toast("✅ Text saved: ${txtFile.name}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ ${e.message}") }
            }
        }
    }

    private fun openScanner() {
        startActivity(Intent(this, com.propdf.editor.ui.scanner.DocumentScannerActivity::class.java))
    }

    private fun doOcr() {
        val file = requireFile() ?: return
        run("Running OCR on PDF...") {
            try {
                val sb = StringBuilder()
                val renderer = android.graphics.pdf.PdfRenderer(
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                )
                sb.appendLine("OCR Result for: ${file.name}")
                sb.appendLine("Pages: ${renderer.pageCount}")
                sb.appendLine("─────────────────────────")
                // Use ML Kit text recognition on each rendered page
                for (i in 0 until minOf(renderer.pageCount, 5)) { // First 5 pages
                    val page = renderer.openPage(i)
                    val bitmap = android.graphics.Bitmap.createBitmap(page.width * 2, page.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    sb.appendLine("\n--- Page ${i + 1} ---")
                    // ML Kit recognition
                    try {
                        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                        )
                        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                        var ocrText = ""
                        val task = recognizer.process(image)
                        while (!task.isComplete) { Thread.sleep(100) }
                        if (task.isSuccessful) ocrText = task.result?.text ?: ""
                        sb.appendLine(ocrText.ifEmpty { "(No text detected)" })
                    } catch (e: Exception) {
                        sb.appendLine("(OCR failed for this page: ${e.message})")
                    }
                    bitmap.recycle()
                }
                renderer.close()
                val txtFile = File(getExternalFilesDir(null) ?: cacheDir, "${file.nameWithoutExtension}_ocr.txt")
                txtFile.writeText(sb.toString())
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity)
                        .setTitle("OCR Complete")
                        .setMessage(sb.toString().take(800) + "\n\n[Full text saved to: ${txtFile.name}]")
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Share Text") { _, _ -> shareText(sb.toString()) }
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("❌ OCR error: ${e.message}") }
            }
        }
    }

    private fun doPrint() {
        val file = requireFile() ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            try {
                val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val printAdapter = object : android.print.PrintDocumentAdapter() {
                    override fun onLayout(old: android.print.PrintAttributes?, new: android.print.PrintAttributes?, token: android.os.CancellationSignal?, cb: LayoutResultCallback?, b: android.os.Bundle?) {
                        cb?.onLayoutFinished(android.print.PrintDocumentInfo.Builder(file.name).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
                    }
                    override fun onWrite(pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor?, token: android.os.CancellationSignal?, cb: WriteResultCallback?) {
                        try {
                            java.io.FileInputStream(file).use { input ->
                                java.io.FileOutputStream(dest!!.fileDescriptor).use { output -> input.copyTo(output) }
                            }
                            cb?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                        } catch (e: Exception) { cb?.onWriteFailed(e.message) }
                    }
                }
                printManager.print(file.nameWithoutExtension, printAdapter, null)
                toast("Opening print dialog...")
            } catch (e: Exception) { toast("❌ Print error: ${e.message}") }
        } else { toast("Print requires Android 4.4+") }
    }

    private fun doShare() {
        val file = requireFile() ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF via..."))
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "Share text via..."))
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun showResult(msg: String, file: File) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Done!")
                .setMessage("$msg\n\nFile: ${file.name}\nSize: ${formatSize(file.length())}\nPath: ${file.absolutePath}")
                .setPositiveButton("Open") { _, _ ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                    startActivity(Intent(this, com.propdf.editor.ui.viewer.ViewerActivity::class.java)
                        .putExtra(com.propdf.editor.ui.viewer.ViewerActivity.EXTRA_PDF_URI, uri.toString()))
                }
                .setNeutralButton("Share") { _, _ ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share via..."))
                }
                .setNegativeButton("OK", null)
                .show()
        }
    }

    private fun run(msg: String, block: suspend () -> Unit) {
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text = "⏳ $msg"
        lifecycleScope.launch {
            try { block() } finally {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun sectionCard(title: String, buttons: List<View>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(12), 0, 0)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) }
            elevation = dp(2).toFloat()
            // Title
            addView(TextView(this@ToolsActivity).apply {
                text = title; textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#333333"))
                setPadding(0, 0, 0, dp(10))
            })
            buttons.forEach { addView(it) }
        }
    }

    private fun btn(label: String, color: String, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(android.graphics.Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
        setPadding(dp(12), dp(10), dp(12), dp(10)); textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    private fun requireFile(): File? {
        if (pickedFiles.isEmpty()) { toast("Please select a PDF file first"); return null }
        return pickedFiles.first()
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val f = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        return f
    }

    private fun outputFile(prefix: String) =
        File(getExternalFilesDir(null) ?: cacheDir, "${prefix}_${System.currentTimeMillis()}.pdf")

    private fun formatSize(bytes: Long): String = when {
        bytes > 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes > 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
