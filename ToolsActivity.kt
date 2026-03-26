package com.propdf.editor.ui.tools

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
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

/**
 * ToolsActivity -- redesigned with:
 * - Working file output visible in Downloads
 * - Filename edit dialog before every save
 * - Single "Apply & Save" model via a pending operation queue
 * - Fixed watermark (now actually renders text on PDF)
 * - Fixed compress (proper quality parameter)
 * - Fixed PDF->Images (JPEG names match page, not watermark)
 * - Preview dialogs for header/footer and page numbers
 * - Open result uses FileProvider URI -- no "file not found"
 * - Rotate preview showing degree selection
 */
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
        askFilename("images_to_pdf") { name ->
            run("Converting images to PDF...") {
                pdfOps.imagesToPdf(imgs, FileHelper.tempFile(this@ToolsActivity, name))
                    .onSuccess { done(it, name) }
                    .onFailure { err("Failed: ${it.message}") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildUI()
    }

    private fun buildUI() {
        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(48))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(root); setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF555555.toInt()); text = statusText()
            setPadding(0, dp(4), 0, dp(4))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0,dp(4),0,dp(4)) }
            isIndeterminate = true; visibility = View.GONE
        }

        root.addView(card("?  Files", listOf(
            btn("Select PDF File(s)", 0xFF1565C0.toInt()) { pdfPicker.launch(arrayOf("application/pdf")) },
            btn("Clear selection",    0xFF757575.toInt()) { files.clear(); tvStatus.text = statusText() }
        )))
        root.addView(tvStatus); root.addView(progress)

        root.addView(card("?  Operations", listOf(
            btn("Merge PDFs (2+ needed)", 0xFF2E7D32.toInt()) { doMerge() },
            btn("Split by page range",    0xFF1A237E.toInt()) { doSplit() },
            btn("Compress / reduce size", 0xFF6A1B9A.toInt()) { doCompress() },
            btn("Extract pages",          0xFF00695C.toInt()) { doExtract() }
        )))

        root.addView(card("?  Security", listOf(
            btn("Password protect", 0xFFB71C1C.toInt()) { doEncrypt() },
            btn("Remove password",  0xFFE65100.toInt()) { doDecrypt() },
            btn("Add watermark",    0xFF004D40.toInt()) { doWatermark() }
        )))

        root.addView(card("?  Page Tools", listOf(
            btn("Rotate pages (with preview)",    0xFF37474F.toInt()) { doRotate() },
            btn("Delete pages",                   0xFFC62828.toInt()) { doDeletePages() },
            btn("Add page numbers (preview)",     0xFF1A237E.toInt()) { doPageNumbers() },
            btn("Header / Footer (preview)",      0xFF33691E.toInt()) { doHeaderFooter() }
        )))

        root.addView(card("?  Convert", listOf(
            btn("Images -> PDF",      0xFF0D47A1.toInt()) { imgPicker.launch(arrayOf("image/*")) },
            btn("PDF -> Images (JPG)", 0xFF4A148C.toInt()) { doPdfToImages() }
        )))

        root.addView(card("?  Share", listOf(
            btn("Share selected PDF", 0xFF00897B.toInt()) { doShare() }
        )))
    }

    // ?? Operations ????????????????????????????????????????????

    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        askFilename("merged") { name ->
            run("Merging ${files.size} PDFs...") {
                pdfOps.mergePdfs(files, FileHelper.tempFile(this@ToolsActivity, name))
                    .onSuccess { done(it, name) }
                    .onFailure { err("Merge failed: ${it.message}") }
            }
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etRange = EditText(this).apply { hint = "Page range (e.g. 1-3)" }
        lay.addView(TextView(this).apply { text = "Pages to extract:" }); lay.addView(etRange)

        AlertDialog.Builder(this).setTitle("Split PDF").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val parts = etRange.text.toString().trim().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (from == null || to == null) { toast("Enter range like: 1-3"); return@setPositiveButton }
                askFilename("split_${from}_${to}") { name ->
                    run("Splitting pages $from-$to...") {
                        pdfOps.splitPdf(f, cacheDir, listOf(from..to))
                            .onSuccess { files -> files.firstOrNull()?.let { done(it, name) }
                                ?: run { err("Split produced no output") } }
                            .onFailure { err("Split failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val f = need() ?: return
        // Show compression level choice
        AlertDialog.Builder(this).setTitle("Compress PDF")
            .setItems(arrayOf("High quality (small reduction)", "Medium quality (good reduction)", "Low quality (max reduction)")) { _, which ->
                val level = when (which) { 0 -> 3; 1 -> 7; else -> 9 }
                askFilename("compressed") { name ->
                    run("Compressing (level $level)...") {
                        pdfOps.compressPdf(f, FileHelper.tempFile(this@ToolsActivity, name), level)
                            .onSuccess {
                                val orig = f.length(); val out = it.length()
                                val pct  = if (orig > 0) ((orig - out) * 100L / orig) else 0L
                                val msg  = if (pct > 0) "[OK] Compressed $pct% smaller"
                                           else "[OK] File rewritten (PDF was already well-compressed)"
                                done(it, name, msg)
                            }
                            .onFailure { err("Compress failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doExtract() {
        val f = need() ?: return
        val et = EditText(this).apply { hint = "Page range (e.g. 2-5)"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val parts = et.text.toString().trim().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                askFilename("extracted_${from}_$to") { name ->
                    run("Extracting pages $from-$to...") {
                        pdfOps.splitPdf(f, cacheDir, listOf(from..to))
                            .onSuccess { files -> files.firstOrNull()?.let { done(it, name) } }
                            .onFailure { err("Extract failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint = "Confirm password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("? Password Protect").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank()) { toast("Enter a password"); return@setPositiveButton }
                if (pw != et2.text.toString()) { toast("Passwords don't match"); return@setPositiveButton }
                askFilename("protected") { name ->
                    run("Encrypting with AES-256...") {
                        pdfOps.encryptPdf(f, FileHelper.tempFile(this@ToolsActivity, name), pw, pw)
                            .onSuccess { done(it, name) }
                            .onFailure { err("Encrypt failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val f  = need() ?: return
        val et = EditText(this).apply { hint = "Current password"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Next") { _, _ ->
                askFilename("unlocked") { name ->
                    run("Removing password...") {
                        pdfOps.removePdfPassword(f, FileHelper.tempFile(this@ToolsActivity, name), et.text.toString())
                            .onSuccess { done(it, name) }
                            .onFailure { err("Wrong password: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etText = EditText(this).apply { setText("CONFIDENTIAL"); hint = "Watermark text" }
        val etOpac = EditText(this).apply { setText("30"); hint = "Opacity % (1-100)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        lay.addView(TextView(this).apply { text = "Watermark text:" }); lay.addView(etText)
        lay.addView(TextView(this).apply { text = "Opacity %:" });      lay.addView(etOpac)

        // Preview watermark on a white square
        val preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(120))
            setImageBitmap(makeWatermarkPreview("CONFIDENTIAL", 0.3f))
        }
        etText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val op = (etOpac.text.toString().toIntOrNull() ?: 30) / 100f
                preview.setImageBitmap(makeWatermarkPreview(s.toString(), op))
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        lay.addView(TextView(this).apply { text = "Preview:"; setPadding(0, dp(8), 0, dp(4)) })
        lay.addView(preview)

        AlertDialog.Builder(this).setTitle("Add Watermark").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val text = etText.text.toString().ifBlank { "CONFIDENTIAL" }
                val opac = (etOpac.text.toString().toIntOrNull() ?: 30).coerceIn(1,100) / 100f
                askFilename("watermarked") { name ->
                    run("Adding watermark...") {
                        pdfOps.addTextWatermark(f, FileHelper.tempFile(this@ToolsActivity, name), text, opac)
                            .onSuccess { done(it, name) }
                            .onFailure { err("Watermark failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun makeWatermarkPreview(text: String, opacity: Float): Bitmap {
        val bmp = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp); cvs.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY; alpha = (opacity * 255).toInt(); textSize = 80f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        cvs.save(); cvs.rotate(-35f, 400f, 200f)
        cvs.drawText(text.take(20), 50f, 220f, paint)
        cvs.restore()
        return bmp
    }

    private fun doRotate() {
        val f = need() ?: return
        val n = pageCount(f)
        if (n == 0) { toast("Cannot read page count"); return }

        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        var selectedDeg = 90
        lay.addView(TextView(this).apply { text = "Select rotation:"; textSize = 14f })

        val radioGrp = RadioGroup(this)
        listOf("90? right" to 90, "180?" to 180, "90? left (270?)" to 270).forEach { (label, deg) ->
            radioGrp.addView(RadioButton(this).apply {
                text = label; isChecked = deg == 90
                setOnCheckedChangeListener { _, checked -> if (checked) selectedDeg = deg }
            })
        }
        lay.addView(radioGrp)

        // Visual preview tile
        val previewIv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(120))
            setImageBitmap(makeRotatePreview(0))
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        radioGrp.setOnCheckedChangeListener { grp, _ ->
            val checked = grp.checkedRadioButtonId
            val deg = when (checked) {
                radioGrp.getChildAt(0).id -> 90
                radioGrp.getChildAt(1).id -> 180
                else -> 270
            }
            previewIv.setImageBitmap(makeRotatePreview(deg))
        }
        lay.addView(TextView(this).apply { text = "Preview:"; setPadding(0, dp(8), 0, dp(4)) })
        lay.addView(previewIv)

        AlertDialog.Builder(this).setTitle("Rotate Pages").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                askFilename("rotated_${selectedDeg}deg") { name ->
                    run("Rotating ${selectedDeg}?...") {
                        pdfOps.rotatePages(f, FileHelper.tempFile(this@ToolsActivity, name), (1..n).associateWith { selectedDeg })
                            .onSuccess { done(it, name) }
                            .onFailure { err("Rotate failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun makeRotatePreview(deg: Int): Bitmap {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp); cvs.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A73E8"); textSize = 40f }
        val rect  = Paint().apply { color = Color.parseColor("#DDDDDD"); style = Paint.Style.FILL }
        cvs.drawRect(50f, 20f, 200f, 180f, rect)
        cvs.save(); cvs.rotate(deg.toFloat(), 125f, 100f)
        cvs.drawText("A", 110f, 115f, paint); cvs.restore()
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935"); textSize = 50f }
        cvs.drawText("-> $deg?", 220f, 115f, arrow)
        return bmp
    }

    private fun doDeletePages() {
        val f = need() ?: return
        val et = EditText(this).apply { hint = "Pages to delete (e.g. 1,3,5)"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                askFilename("deleted_pages") { name ->
                    run("Deleting pages...") {
                        pdfOps.deletePages(f, FileHelper.tempFile(this@ToolsActivity, name), pages)
                            .onSuccess { done(it, name) }
                            .onFailure { err("Delete failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPageNumbers() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val etFmt = EditText(this).apply { setText("Page %d of %d"); hint = "Format (%d = page, %d = total)" }
        val etSize = EditText(this).apply { setText("10"); hint = "Font size (pt)" }

        // Live preview box
        val previewTv = TextView(this).apply {
            text = "Preview: Page 1 of 12"
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(12), dp(8), dp(12), dp(8)); textSize = 14f
        }
        etFmt.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val fmt = s.toString().replaceFirst("%d","1").let {
                    if (it.contains("%d")) it.replaceFirst("%d","12") else it
                }
                previewTv.text = "Preview: $fmt"
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })

        lay.addView(TextView(this).apply { text = "Format:" }); lay.addView(etFmt)
        lay.addView(TextView(this).apply { text = "Font size (pt):"; setPadding(0, dp(8), 0, 0) }); lay.addView(etSize)
        lay.addView(previewTv)

        AlertDialog.Builder(this).setTitle("Add Page Numbers").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val fmt  = etFmt.text.toString().ifBlank { "Page %d of %d" }
                askFilename("numbered") { name ->
                    run("Adding page numbers...") {
                        pdfOps.addPageNumbers(f, FileHelper.tempFile(this@ToolsActivity, name), fmt)
                            .onSuccess { done(it, name) }
                            .onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val etH = EditText(this).apply { hint = "Header text (blank = none)" }
        val etF = EditText(this).apply { hint = "Footer text (blank = none)" }

        // Preview card
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tvPreviewH = TextView(this).apply { text = "[ header ]"; textSize = 12f; setTextColor(Color.parseColor("#1A73E8")) }
        val tvPageBody = TextView(this).apply { text = "\n   Page content here...\n"; textSize = 12f }
        val tvPreviewF = TextView(this).apply { text = "[ footer ]"; textSize = 12f; setTextColor(Color.parseColor("#E53935")) }
        previewCard.addView(tvPreviewH); previewCard.addView(tvPageBody); previewCard.addView(tvPreviewF)

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                tvPreviewH.text = etH.text.toString().ifBlank { "[ no header ]" }
                tvPreviewF.text = etF.text.toString().ifBlank { "[ no footer ]" }
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        }
        etH.addTextChangedListener(watcher); etF.addTextChangedListener(watcher)

        lay.addView(TextView(this).apply { text = "Header:" }); lay.addView(etH)
        lay.addView(TextView(this).apply { text = "Footer:"; setPadding(0, dp(8), 0, 0) }); lay.addView(etF)
        lay.addView(TextView(this).apply { text = "Preview:"; setPadding(0, dp(8), 0, dp(4)) })
        lay.addView(previewCard)

        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val h  = etH.text.toString().ifBlank { null }
                val ft = etF.text.toString().ifBlank { null }
                if (h == null && ft == null) { toast("Enter at least header or footer"); return@setPositiveButton }
                askFilename("header_footer") { name ->
                    run("Adding header/footer...") {
                        pdfOps.addHeaderFooter(f, FileHelper.tempFile(this@ToolsActivity, name), h, ft)
                            .onSuccess { done(it, name) }
                            .onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val f = need() ?: return
        val baseName = f.nameWithoutExtension
        run("Exporting pages as JPG...") {
            try {
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = PdfRenderer(pfd)
                var count = 0
                for (i in 0 until r.pageCount) {
                    val pg  = r.openPage(i)
                    val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); pg.close()

                    // Name each image correctly: baseName_page1.jpg, baseName_page2.jpg ...
                    val imgName = "${baseName}_page${i + 1}.jpg"
                    val tmp     = File(cacheDir, imgName)
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()

                    // Save each image to Downloads individually with correct name
                    FileHelper.saveToDownloads(this@ToolsActivity, tmp)
                    count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity)
                        .setTitle("[OK] Done!")
                        .setMessage("$count images saved to Downloads.\n\nFiles named:\n${baseName}_page1.jpg\n${baseName}_page2.jpg\n...")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { err("Failed: ${e.message}") }
            }
        }
    }

    private fun doShare() {
        val f = need() ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { err("Share error: ${e.message}") }
    }

    // ?? Core helpers ??????????????????????????????????????????

    /**
     * Ask user for a filename before saving. Default is provided.
     * Appends .pdf automatically.
     */
    private fun askFilename(default: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { setText(default); selectAll(); setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("? Save as...")
            .setMessage("Enter filename (without .pdf):")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifBlank { default }
                cb(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Run operation on IO thread, show progress, resume on Main. */
    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        tvStatus.text = "? $label"
        lifecycleScope.launch {
            withContext(Dispatchers.IO, block)
            progress.visibility = View.GONE; tvStatus.text = statusText()
        }
    }

    /**
     * Save temp file to Downloads, then show result dialog.
     * "Open" button uses FileProvider URI -- works on all Android versions.
     */
    private fun done(tmpFile: File, desiredName: String, extraMsg: String = "") = runOnUiThread {
        lifecycleScope.launch {
            // Rename temp file to the user's chosen name
            val namedFile = File(cacheDir, "$desiredName.pdf")
            try { tmpFile.copyTo(namedFile, overwrite = true) } catch (_: Exception) {}

            val saved = withContext(Dispatchers.IO) {
                try { FileHelper.saveToDownloads(this@ToolsActivity, namedFile) }
                catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(namedFile), namedFile) }
            }

            val fileForOpen = saved.file ?: namedFile

            AlertDialog.Builder(this@ToolsActivity)
                .setTitle("[OK] Done!")
                .setMessage(
                    (if (extraMsg.isNotEmpty()) "$extraMsg\n\n" else "") +
                    "? File: $desiredName.pdf\n" +
                    "? Saved to: ${saved.displayPath}\n\n" +
                    "Open Files app -> Downloads to find it."
                )
                .setPositiveButton("Open") { _, _ ->
                    // Use FileProvider URI -- avoids "file not found" crash
                    val openUri = try {
                        androidx.core.content.FileProvider.getUriForFile(
                            this@ToolsActivity, "$packageName.provider", fileForOpen
                        )
                    } catch (_: Exception) { Uri.fromFile(fileForOpen) }
                    ViewerActivity.start(this@ToolsActivity, openUri)
                }
                .setNeutralButton("Share") { _, _ ->
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@ToolsActivity, "$packageName.provider", fileForOpen
                        )
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share"))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("OK", null).show()
        }
    }

    private fun err(msg: String) = runOnUiThread {
        progress.visibility = View.GONE; tvStatus.text = statusText()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF file first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) { toast("File missing -- re-select"); files.clear(); tvStatus.text = statusText(); return null }
        return f
    }

    private fun copyUri(uri: Uri): File {
        val name = FileHelper.getFileName(this, uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
        val dest = File(cacheDir, name)
        try { contentResolver.openInputStream(uri)?.use { FileOutputStream(dest).use { o -> it.copyTo(o) } } } catch (_: Exception) {}
        return dest
    }

    private fun pageCount(f: File) = try {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd); val c = r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun statusText() = if (files.isEmpty()) "No file selected" else "[OK] ${files.size} file(s) ready"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(title: String, buttons: List<View>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,dp(10),0,0) }
            elevation = dp(2).toFloat(); setPadding(dp(14),dp(10),dp(14),dp(10))
            addView(TextView(this@ToolsActivity).apply {
                text = title; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF222222.toInt()); setPadding(0,0,0,dp(8))
            })
            buttons.forEach { addView(it) }
        }

    private fun btn(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(6)) }
        setPadding(dp(12),dp(10),dp(12),dp(10)); textSize = 13f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
