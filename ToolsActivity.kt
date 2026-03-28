package com.propdf.editor.ui.tools

import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
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
        askFilename("images_to_pdf") { name ->
            run("Converting...") {
                pdfOps.imagesToPdf(imgs, FileHelper.tempFile(this@ToolsActivity, name))
                    .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
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
            setPadding(dp(12), dp(8), dp(12), dp(48))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(root); setContentView(scroll)

        // File selector card
        root.addView(buildSelectorCard())

        tvStatus = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF555555.toInt())
            text = statusText(); setPadding(dp(4), dp(6), dp(4), dp(2))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(0,dp(4),0,dp(4)) }
            isIndeterminate = true; visibility = View.GONE
        }
        root.addView(tvStatus); root.addView(progress)

        // Categorized tool sections
        root.addView(buildCategory("Organize", listOf(
            ToolItem("Merge PDFs",      0xFF2E7D32.toInt(), "Combine 2+ PDFs into one") { doMerge() },
            ToolItem("Split PDF",       0xFF1565C0.toInt(), "Extract a page range") { doSplit() },
            ToolItem("Extract Pages",   0xFF6A1B9A.toInt(), "Save specific pages") { doExtract() },
            ToolItem("Delete Pages",    0xFFC62828.toInt(), "Remove page numbers") { doDeletePages() },
        )))
        root.addView(buildCategory("Optimize", listOf(
            ToolItem("Compress PDF",    0xFF00695C.toInt(), "Reduce file size") { doCompress() },
            ToolItem("Rotate Pages",    0xFF37474F.toInt(), "90/180/270 degrees") { doRotate() },
            ToolItem("Add Page Numbers",0xFF1A237E.toInt(), "e.g. Page 1 of 10") { doPageNumbers() },
            ToolItem("Header / Footer", 0xFF33691E.toInt(), "Add top/bottom text") { doHeaderFooter() },
        )))
        root.addView(buildCategory("Security", listOf(
            ToolItem("Password Protect",0xFFB71C1C.toInt(), "AES-256 encryption") { doEncrypt() },
            ToolItem("Remove Password", 0xFFE65100.toInt(), "Decrypt with password") { doDecrypt() },
            ToolItem("Add Watermark",   0xFF004D40.toInt(), "Diagonal text overlay") { doWatermark() },
        )))
        root.addView(buildCategory("Convert", listOf(
            ToolItem("Images to PDF",   0xFF0D47A1.toInt(), "JPG/PNG -> PDF") { imgPicker.launch(arrayOf("image/*")) },
            ToolItem("PDF to Images",   0xFF4A148C.toInt(), "Save each page as JPG") { doPdfToImages() },
        )))
        root.addView(buildCategory("Share", listOf(
            ToolItem("Share PDF",       0xFF00897B.toInt(), "Send via any app") { doShare() },
        )))
    }

    data class ToolItem(val label: String, val color: Int, val desc: String, val action: () -> Unit)

    private fun buildSelectorCard(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,dp(8),0,0) }
            radius = dp(12).toFloat(); cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(14))
        }
        inner.addView(TextView(this).apply {
            text = "Select PDF"; textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF111111.toInt()); setPadding(0,0,0,dp(10))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(Button(this).apply {
            text = "Choose File(s)"; layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,dp(6),0) }
            setTextColor(Color.WHITE); setBackgroundColor(0xFF1A73E8.toInt())
            setPadding(dp(8),dp(10),dp(8),dp(10))
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        })
        row.addView(Button(this).apply {
            text = "Clear"; layoutParams = LinearLayout.LayoutParams(-2,-2)
            setTextColor(0xFF1A73E8.toInt()); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { files.clear(); tvStatus.text = statusText() }
        })
        inner.addView(row); card.addView(inner); return card
    }

    private fun buildCategory(title: String, items: List<ToolItem>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(12),0,0) }
            radius = dp(12).toFloat(); cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(6))
        }
        inner.addView(TextView(this).apply {
            text = title; textSize = 12f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f; setPadding(0,0,0,dp(10))
        })
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(0)
                layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(2)) }
                setPadding(dp(6),dp(12),dp(6),dp(12))
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { item.action() }
                // Ripple feedback
                background = android.util.TypedValue().let { tv ->
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    getDrawable(tv.resourceId)
                }
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10),dp(10)).apply {
                    setMargins(0,0,dp(14),0); gravity = Gravity.CENTER_VERTICAL
                }
                setBackgroundColor(item.color)
                // Circle shape
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(item.color)
                }
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            }
            textCol.addView(TextView(this).apply {
                text = item.label; textSize = 14f
                setTextColor(0xFF111111.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            textCol.addView(TextView(this).apply {
                text = item.desc; textSize = 11f; setTextColor(0xFF888888.toInt())
                setPadding(0,dp(2),0,0)
            })
            val arrow = TextView(this).apply {
                text = ">"; textSize = 16f; setTextColor(0xFFCCCCCC.toInt())
                setPadding(dp(8),0,0,0)
            }
            row.addView(dot); row.addView(textCol); row.addView(arrow)
            inner.addView(row)
        }
        card.addView(inner); return card
    }

    // ---- Operations ----------------------------------------------------

    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        askFilename("merged") { name ->
            run("Merging ${files.size} PDFs...") {
                pdfOps.mergePdfs(files, FileHelper.tempFile(this@ToolsActivity, name))
                    .onSuccess { done(it, name) }.onFailure { err("Merge failed: ${it.message}") }
            }
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et  = EditText(this).apply { hint="Page range e.g. 2-5" }
        lay.addView(TextView(this).apply { text="Enter page range to extract:" }); lay.addView(et)
        AlertDialog.Builder(this).setTitle("Split PDF").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val parts = et.text.toString().trim().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (from == null || to == null || from > to) {
                    toast("Enter valid range like: 1-3"); return@setPositiveButton
                }
                askFilename("split_${from}_$to") { name ->
                    run("Splitting pages $from-$to...") {
                        pdfOps.splitPdf(f, cacheDir, listOf(from..to)).fold(
                            onSuccess = { files ->
                                files.firstOrNull()?.let { done(it, name) }
                                    ?: err("Split produced no output")
                            },
                            onFailure = { err("Split failed: ${it.message}") }
                        )
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doExtract() {
        val f  = need() ?: return
        val et = EditText(this).apply { hint="Range e.g. 1-3,5,7-9"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages")
            .setMessage("Enter page numbers or ranges separated by commas:")
            .setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val input  = et.text.toString().trim()
                val ranges = mutableListOf<IntRange>()
                input.split(",").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.contains("-")) {
                        val ab = trimmed.split("-")
                        val a  = ab.getOrNull(0)?.trim()?.toIntOrNull()
                        val b  = ab.getOrNull(1)?.trim()?.toIntOrNull()
                        if (a != null && b != null && a <= b) ranges.add(a..b)
                    } else {
                        trimmed.toIntOrNull()?.let { ranges.add(it..it) }
                    }
                }
                if (ranges.isEmpty()) { toast("Enter valid page numbers"); return@setPositiveButton }
                askFilename("extracted") { name ->
                    run("Extracting pages...") {
                        // Merge extracted ranges into one PDF
                        val tmpFiles = mutableListOf<File>()
                        for ((i, range) in ranges.withIndex()) {
                            val tmp = File(cacheDir, "extract_part_$i.pdf")
                            pdfOps.splitPdf(f, cacheDir, listOf(range)).fold(
                                onSuccess = { parts -> parts.firstOrNull()?.let { tmpFiles.add(it) } },
                                onFailure = {}
                            )
                        }
                        if (tmpFiles.isEmpty()) { err("No pages extracted"); return@run }
                        pdfOps.mergePdfs(tmpFiles, FileHelper.tempFile(this@ToolsActivity, name)).fold(
                            onSuccess = { done(it, name) },
                            onFailure = { err("Extract failed: ${it.message}") }
                        )
                        tmpFiles.forEach { it.delete() }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val f = need() ?: return
        AlertDialog.Builder(this).setTitle("Compression Level")
            .setItems(arrayOf("High quality (smaller reduction)","Medium (good reduction)","Maximum compression")) { _, which ->
                val level = when (which) { 0 -> 3; 1 -> 6; else -> 9 }
                askFilename("compressed") { name ->
                    run("Compressing...") {
                        pdfOps.compressPdf(f, FileHelper.tempFile(this@ToolsActivity, name), level).fold(
                            onSuccess = {
                                val pct = if (f.length() > 0) ((f.length()-it.length())*100L/f.length()) else 0L
                                done(it, name, if (pct > 0) "$pct% size reduction" else "PDF rewritten (already optimized)")
                            },
                            onFailure = { err("Compress failed: ${it.message}") }
                        )
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint="Confirm password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank()) { toast("Enter a password"); return@setPositiveButton }
                if (pw != et2.text.toString()) { toast("Passwords don't match"); return@setPositiveButton }
                askFilename("protected") { name ->
                    run("Encrypting AES-256...") {
                        pdfOps.encryptPdf(f, FileHelper.tempFile(this@ToolsActivity, name), pw, pw)
                            .onSuccess { done(it, name) }.onFailure { err("Encrypt failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val f  = need() ?: return
        val et = EditText(this).apply { hint="Current password"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Next") { _, _ ->
                askFilename("unlocked") { name ->
                    run("Removing password...") {
                        pdfOps.removePdfPassword(f, FileHelper.tempFile(this@ToolsActivity, name), et.text.toString())
                            .onSuccess { done(it, name) }.onFailure { err("Wrong password: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val f  = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etText = EditText(this).apply { setText("CONFIDENTIAL"); hint="Watermark text" }
        val etOpac = EditText(this).apply { setText("30"); hint="Opacity % (1-100)"; inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        val preview = ImageView(this).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(100)); setImageBitmap(makeWatermarkPreview("CONFIDENTIAL",0.3f)) }
        etText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { preview.setImageBitmap(makeWatermarkPreview(s.toString(),(etOpac.text.toString().toIntOrNull()?:30)/100f)) }
            override fun beforeTextChanged(a: CharSequence?,b: Int,c: Int,d: Int) {}
            override fun onTextChanged(a: CharSequence?,b: Int,c: Int,d: Int) {}
        })
        lay.addView(etText); lay.addView(etOpac); lay.addView(preview)
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val text = etText.text.toString().ifBlank { "CONFIDENTIAL" }
                val opac = (etOpac.text.toString().toIntOrNull()?:30).coerceIn(1,100)/100f
                askFilename("watermarked") { name ->
                    run("Adding watermark...") {
                        pdfOps.addTextWatermark(f, FileHelper.tempFile(this@ToolsActivity, name), text, opac)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun makeWatermarkPreview(text: String, opacity: Float): Bitmap {
        val bmp = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp); cvs.drawColor(Color.WHITE)
        val p   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.GRAY; alpha=(opacity*255).toInt(); textSize=50f
            typeface=android.graphics.Typeface.DEFAULT_BOLD
        }
        cvs.save(); cvs.rotate(-30f, 300f, 100f); cvs.drawText(text.take(18), 50f, 120f, p); cvs.restore()
        return bmp
    }

    private fun doRotate() {
        val f = need() ?: return; val n = pageCount(f)
        if (n == 0) { toast("Cannot read page count"); return }
        var deg = 90
        AlertDialog.Builder(this).setTitle("Rotate All Pages")
            .setSingleChoiceItems(arrayOf("90 right","180","90 left (270)"), 0) { _, which -> deg = when(which){0->90;1->180;else->270} }
            .setPositiveButton("Rotate") { _, _ ->
                askFilename("rotated_${deg}deg") { name ->
                    run("Rotating $deg...") {
                        pdfOps.rotatePages(f, FileHelper.tempFile(this@ToolsActivity, name), (1..n).associateWith { deg })
                            .onSuccess { done(it, name) }.onFailure { err("Rotate failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePages() {
        val f  = need() ?: return
        val et = EditText(this).apply { hint="Pages to delete e.g. 1,3,5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                askFilename("deleted_pages") { name ->
                    run("Deleting pages...") {
                        pdfOps.deletePages(f, FileHelper.tempFile(this@ToolsActivity, name), pages)
                            .onSuccess { done(it, name) }.onFailure { err("Delete failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPageNumbers() {
        val f = need() ?: return
        val et = EditText(this).apply { setText("Page %d of %d"); hint="Format (%d=page, second %d=total)"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Add Page Numbers").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val fmt = et.text.toString().ifBlank { "Page %d of %d" }
                askFilename("numbered") { name ->
                    run("Adding page numbers...") {
                        pdfOps.addPageNumbers(f, FileHelper.tempFile(this@ToolsActivity, name), fmt)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etH = EditText(this).apply { hint="Header text (blank = no header)" }
        val etF = EditText(this).apply { hint="Footer text (blank = no footer)" }
        lay.addView(TextView(this).apply { text="Header:"; setPadding(0,0,0,dp(4)) }); lay.addView(etH)
        lay.addView(TextView(this).apply { text="Footer:"; setPadding(0,dp(10),0,dp(4)) }); lay.addView(etF)
        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val h  = etH.text.toString().ifBlank { null }
                val ft = etF.text.toString().ifBlank { null }
                if (h == null && ft == null) { toast("Enter at least one"); return@setPositiveButton }
                askFilename("header_footer") { name ->
                    run("Adding header/footer...") {
                        pdfOps.addHeaderFooter(f, FileHelper.tempFile(this@ToolsActivity, name), h, ft)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val f = need() ?: return; val baseName = f.nameWithoutExtension
        run("Exporting pages as JPG...") {
            try {
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r   = PdfRenderer(pfd); var count = 0
                for (i in 0 until r.pageCount) {
                    val pg  = r.openPage(i)
                    val bmp = Bitmap.createBitmap(pg.width*2, pg.height*2, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT); pg.close()
                    val imgName = "${baseName}_page${i+1}.jpg"
                    val tmp     = File(cacheDir, imgName)
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    FileHelper.saveToDownloads(this@ToolsActivity, tmp)
                    count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity)
                        .setTitle("Done!")
                        .setMessage("$count JPG images saved to Downloads.\n${baseName}_page1.jpg, ...")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { err("Failed: ${e.message}") } }
        }
    }

    private fun doShare() {
        val f = need() ?: return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { err("Share error: ${e.message}") }
    }

    // ---- Helpers -------------------------------------------------------

    private fun askFilename(default: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { setText(default); selectAll(); setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Save as...").setMessage("Filename (without .pdf):")
            .setView(et).setPositiveButton("Save") { _, _ -> cb(et.text.toString().trim().ifBlank { default }) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(label: String, block: suspend CoroutineScope.() -> Unit) {
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE; tvStatus.text = label
        lifecycleScope.launch { withContext(Dispatchers.IO, block); progress.visibility = View.GONE; tvStatus.text = statusText() }
    }

    private fun done(tmpFile: File, desiredName: String, extraMsg: String = "") = runOnUiThread {
        lifecycleScope.launch {
            val named = File(cacheDir, "$desiredName.pdf")
            try { tmpFile.copyTo(named, overwrite = true) } catch (_: Exception) {}
            val saved = withContext(Dispatchers.IO) {
                try { FileHelper.saveToDownloads(this@ToolsActivity, named) }
                catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(named), named) }
            }
            val fileForOpen = saved.file ?: named
            AlertDialog.Builder(this@ToolsActivity).setTitle("Done!")
                .setMessage((if (extraMsg.isNotEmpty()) "$extraMsg\n\n" else "") +
                    "Saved: $desiredName.pdf\n${saved.displayPath}")
                .setPositiveButton("Open") { _, _ ->
                    val openUri = try {
                        androidx.core.content.FileProvider.getUriForFile(this@ToolsActivity, "$packageName.provider", fileForOpen)
                    } catch (_: Exception) { Uri.fromFile(fileForOpen) }
                    ViewerActivity.start(this@ToolsActivity, openUri)
                }
                .setNeutralButton("Share") { _, _ ->
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@ToolsActivity, "$packageName.provider", fileForOpen)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type="application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
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
        if (files.isEmpty()) { toast("Select a PDF first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) {
            toast("File missing - re-select"); files.clear(); tvStatus.text = statusText(); return null
        }
        return f
    }

    private fun copyUri(uri: Uri): File {
        val name = FileHelper.getFileName(this, uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
        val dest = File(cacheDir, name)
        try { contentResolver.openInputStream(uri)?.use { FileOutputStream(dest).use { o -> it.copyTo(o) } } }
        catch (_: Exception) {}
        return dest
    }

    private fun pageCount(f: File) = try {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r   = PdfRenderer(pfd); val c = r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun statusText() = if (files.isEmpty()) "No file selected" else "${files.size} file(s) ready"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
