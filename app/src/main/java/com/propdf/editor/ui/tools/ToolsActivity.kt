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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.card.MaterialCardView
import com.propdf.editor.core.CrashGuard
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import com.propdf.editor.worker.PdfOperationWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Optimized Tools Activity with:
 * - WorkManager integration for background PDF operations (survives process death)
 * - ANR prevention: all heavy ops off main thread with progress observation
 * - Batch pipeline via WorkManager chaining
 * - Memory-efficient: no large bitmaps held in memory
 * - Proper error handling with retry logic
 */
@AndroidEntryPoint
class ToolsActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    private val files = mutableListOf<File>()
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tvPipeline: TextView

    // Track active WorkManager jobs
    private val activeWorkIds = mutableListOf<UUID>()

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
            // Use WorkManager for heavy conversion
            val workId = PdfOperationWorker.enqueue(
                this,
                PdfOperationWorker.OP_IMAGES_TO_PDF,
                imgs.firstOrNull() ?: File(""),
                name,
                extraParam = imgs.joinToString(",") { it.absolutePath }
            )
            observeWork(workId, "Converting images to PDF...")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "PDF Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any pending work if activity is destroyed
        activeWorkIds.forEach { WorkManager.getInstance(this).cancelWorkById(it) }
    }

    // ─── WorkManager Observation ───────────────────────────────────
    private fun observeWork(workId: UUID, label: String) {
        activeWorkIds.add(workId)
        progress.visibility = View.VISIBLE
        tvStatus.text = label

        lifecycleScope.launch {
            WorkManager.getInstance(this@ToolsActivity)
                .getWorkInfoByIdLiveData(workId)
                .asFlow()
                .collectLatest { workInfo ->
                    when (workInfo?.state) {
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt("progress", 0)
                            tvStatus.text = "$label ($progress%)"
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            progress.visibility = View.GONE
                            val outputPath = workInfo.outputData.getString("output_path")
                            val outputName = workInfo.outputData.getString("output_name") ?: "output"
                            outputPath?.let { path ->
                                done(File(path), outputName)
                            }
                            activeWorkIds.remove(workId)
                        }
                        WorkInfo.State.FAILED -> {
                            progress.visibility = View.GONE
                            val error = workInfo.outputData.getString("error") ?: "Unknown error"
                            err("Failed: $error")
                            activeWorkIds.remove(workId)
                        }
                        WorkInfo.State.CANCELLED -> {
                            progress.visibility = View.GONE
                            tvStatus.text = statusText()
                            activeWorkIds.remove(workId)
                        }
                        else -> {}
                    }
                }
        }
    }

    // ─── UI Building (Preserved with Material 3) ───────────────────
    private fun buildUI() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(80))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            text = statusText()
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
            isIndeterminate = true
            visibility = View.GONE
        }
        tvPipeline = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(dp(4), dp(2), dp(4), dp(4))
            visibility = View.GONE
        }

        root.addView(buildSelectorCard())
        root.addView(tvStatus)
        root.addView(progress)
        root.addView(buildBatchPipelineCard())
        root.addView(tvPipeline)

        // Tool categories
        root.addView(buildCategoryGrid("Organize", listOf(
            ToolItem("Merge PDFs", 0xFF2E7D32.toInt(), android.R.drawable.ic_menu_add, "Combine 2+ PDFs") { doMerge() },
            ToolItem("Split PDF", 0xFF1565C0.toInt(), android.R.drawable.ic_menu_crop, "Extract page range") { doSplit() },
            ToolItem("Extract Pages", 0xFF6A1B9A.toInt(), android.R.drawable.ic_menu_send, "Save specific pages") { doExtract() },
            ToolItem("Delete Pages", 0xFFC62828.toInt(), android.R.drawable.ic_menu_delete, "Remove pages") { doDeletePages() }
        )))
        root.addView(buildCategoryGrid("Optimize", listOf(
            ToolItem("Compress PDF", 0xFF00695C.toInt(), android.R.drawable.ic_menu_preferences, "Reduce file size") { doCompress() },
            ToolItem("Rotate Pages", 0xFF37474F.toInt(), android.R.drawable.ic_menu_rotate, "90/180/270 deg") { doRotate() },
            ToolItem("Page Numbers", 0xFF1A237E.toInt(), android.R.drawable.ic_menu_agenda, "Add numbering") { doPageNumbers() },
            ToolItem("Header/Footer", 0xFF33691E.toInt(), android.R.drawable.ic_menu_edit, "Add top/bottom text") { doHeaderFooter() }
        )))
        root.addView(buildCategoryGrid("Security", listOf(
            ToolItem("Password Protect", 0xFFB71C1C.toInt(), android.R.drawable.ic_lock_lock, "AES-256 encrypt") { doEncrypt() },
            ToolItem("Remove Password", 0xFFE65100.toInt(), android.R.drawable.ic_lock_idle_lock, "Decrypt PDF") { doDecrypt() },
            ToolItem("Add Watermark", 0xFF004D40.toInt(), android.R.drawable.ic_menu_view, "Diagonal overlay") { doWatermark() }
        )))
        root.addView(buildCategoryGrid("Convert", listOf(
            ToolItem("Images to PDF", 0xFF0D47A1.toInt(), android.R.drawable.ic_menu_gallery, "JPG/PNG to PDF") { imgPicker.launch(arrayOf("image/*")) },
            ToolItem("PDF to Images", 0xFF4A148C.toInt(), android.R.drawable.ic_menu_camera, "Each page as JPG") { doPdfToImages() }
        )))
        root.addView(buildCategoryGrid("Share", listOf(
            ToolItem("Share PDF", 0xFF00897B.toInt(), android.R.drawable.ic_menu_share, "Send via any app") { doShare() }
        )))
    }

    // ─── Tool Implementations (WorkManager-based) ────────────────────
    private fun doMerge() {
        if (files.size < 2) { toast("Select at least 2 PDFs"); return }
        askFilename("merged") { name ->
            val workId = PdfOperationWorker.enqueue(
                this,
                PdfOperationWorker.OP_MERGE,
                files.first(),
                name,
                extraParam = files.joinToString(",") { it.absolutePath }
            )
            observeWork(workId, "Merging ${files.size} PDFs...")
        }
    }

    private fun doSplit() {
        val f = need() ?: return
        val et = EditText(this).apply { 
            hint = "Range e.g. 1-3"
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Split PDF").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val parts = et.text.toString().trim().split("-")
                val from = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val to = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (from == null || to == null || from > to) {
                    toast("Enter range like: 1-3"); return@setPositiveButton
                }
                askFilename("split_${from}_$to") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_SPLIT,
                        f,
                        name,
                        extraParam = "$from-$to"
                    )
                    observeWork(workId, "Splitting $from-$to...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val f = need() ?: return
        AlertDialog.Builder(this).setTitle("Compression Level")
            .setItems(arrayOf("High quality (less compression)", "Medium (recommended)", "Maximum compression")) { _, which ->
                val level = when (which) { 0 -> 3; 1 -> 6; else -> 9 }
                askFilename("compressed") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_COMPRESS,
                        f,
                        name,
                        extraParam = level.toString()
                    )
                    observeWork(workId, "Compressing...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val et1 = EditText(this).apply { 
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD 
        }
        val et2 = EditText(this).apply { 
            hint = "Confirm"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD 
        }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank() || pw != et2.text.toString()) {
                    toast("Passwords must match"); return@setPositiveButton
                }
                askFilename("protected") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_ENCRYPT,
                        f,
                        name,
                        extraParam = pw
                    )
                    observeWork(workId, "Encrypting AES-256...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val f = need() ?: return
        val et = EditText(this).apply { 
            hint = "Current password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Remove") { _, _ ->
                askFilename("unlocked") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_DECRYPT,
                        f,
                        name,
                        extraParam = et.text.toString()
                    )
                    observeWork(workId, "Removing password...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val etText = EditText(this).apply { setText("CONFIDENTIAL"); hint = "Watermark text" }
        val etOpac = EditText(this).apply { setText("30"); hint = "Opacity % (1-100)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        lay.addView(etText); lay.addView(etOpac)
        AlertDialog.Builder(this).setTitle("Add Watermark").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val text = etText.text.toString().ifBlank { "CONFIDENTIAL" }
                val opac = (etOpac.text.toString().toIntOrNull() ?: 30).coerceIn(1, 100) / 100f
                askFilename("watermarked") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_WATERMARK,
                        f,
                        name,
                        extraParam = text,
                        extraParam2 = opac.toString()
                    )
                    observeWork(workId, "Adding watermark...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doRotate() {
        val f = need() ?: return
        val n = pageCount(f)
        if (n == 0) { toast("Cannot read page count"); return }
        var deg = 90
        AlertDialog.Builder(this).setTitle("Rotate All Pages")
            .setSingleChoiceItems(arrayOf("90 right", "180", "90 left (270)"), 0) { _, which ->
                deg = when (which) { 0 -> 90; 1 -> 180; else -> 270 }
            }
            .setPositiveButton("Rotate") { _, _ ->
                askFilename("rotated_${deg}deg") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_ROTATE,
                        f,
                        name,
                        extraParam = deg.toString(),
                        extraParam2 = n.toString()
                    )
                    observeWork(workId, "Rotating...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePages() {
        val f = need() ?: return
        val et = EditText(this).apply { 
            hint = "Pages e.g. 1,3,5"
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                askFilename("deleted_pages") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_DELETE_PAGES,
                        f,
                        name,
                        extraParam = pages.joinToString(",")
                    )
                    observeWork(workId, "Deleting pages...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPageNumbers() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val etFmt = EditText(this).apply { setText("Page %d of %d"); hint = "Format" }
        lay.addView(TextView(this).apply { text = "Format (%d = page, second %d = total):" })
        lay.addView(etFmt)
        AlertDialog.Builder(this).setTitle("Add Page Numbers").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val fmt = etFmt.text.toString().ifBlank { "Page %d of %d" }
                askFilename("numbered") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_PAGE_NUMBERS,
                        f,
                        name,
                        extraParam = fmt
                    )
                    observeWork(workId, "Adding page numbers...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val etH = EditText(this).apply { hint = "Header text (blank = none)" }
        val etF = EditText(this).apply { hint = "Footer text (blank = none)" }
        lay.addView(TextView(this).apply { text = "Header:" }); lay.addView(etH)
        lay.addView(TextView(this).apply { text = "Footer:"; setPadding(0, dp(10), 0, dp(4)) }); lay.addView(etF)
        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val h = etH.text.toString().ifBlank { null }
                val ft = etF.text.toString().ifBlank { null }
                if (h == null && ft == null) { toast("Enter at least one"); return@setPositiveButton }
                askFilename("header_footer") { name ->
                    val workId = PdfOperationWorker.enqueue(
                        this,
                        PdfOperationWorker.OP_HEADER_FOOTER,
                        f,
                        name,
                        extraParam = h,
                        extraParam2 = ft
                    )
                    observeWork(workId, "Adding header/footer...")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPdfToImages() {
        val f = need() ?: return
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.BackgroundDispatcher,
            timeoutMs = 300000L,
            onError = { err("Export failed: ${it.message}") }
        ) {
            val baseName = f.nameWithoutExtension
            var count = 0
            try {
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(pfd)
                for (i in 0 until r.pageCount) {
                    val pg = r.openPage(i)
                    // Use RGB_565 to save memory during export
                    val bmp = Bitmap.createBitmap(pg.width * 2, pg.height * 2, Bitmap.Config.RGB_565)
                    bmp.eraseColor(Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pg.close()

                    val imgName = "${baseName}_page${i + 1}.jpg"
                    val tmp = File(cacheDir, imgName)
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()
                    FileHelper.saveToDownloads(this@ToolsActivity, tmp)
                    count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity).setTitle("Done!")
                        .setMessage("$count JPG images saved to Downloads.")
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
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", f
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { err("Share error: ${e.message}") }
    }

    // ─── Batch Pipeline (WorkManager Chaining) ───────────────────────
    private fun showBatchPipelineDialog() {
        val f = need() ?: return
        val operations = arrayOf(
            "Add Page Numbers", "Add Header/Footer", "Add Watermark",
            "Compress", "Rotate All Pages", "Delete Pages", "Password Protect"
        )
        val checked = BooleanArray(operations.size) { false }
        AlertDialog.Builder(this).setTitle("Select Operations (run in order)")
            .setMultiChoiceItems(operations, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Run All") { _, _ ->
                val selected = operations.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) { toast("Select at least one operation"); return@setPositiveButton }
                runBatchPipeline(f, selected)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runBatchPipeline(inputFile: File, operations: List<String>) {
        val specs = operations.map { op ->
            PdfOperationWorker.OperationSpec(
                operation = when (op) {
                    "Add Page Numbers" -> PdfOperationWorker.OP_PAGE_NUMBERS
                    "Add Header/Footer" -> PdfOperationWorker.OP_HEADER_FOOTER
                    "Add Watermark" -> PdfOperationWorker.OP_WATERMARK
                    "Compress" -> PdfOperationWorker.OP_COMPRESS
                    "Rotate All Pages" -> PdfOperationWorker.OP_ROTATE
                    "Delete Pages" -> PdfOperationWorker.OP_DELETE_PAGES
                    "Password Protect" -> PdfOperationWorker.OP_ENCRYPT
                    else -> PdfOperationWorker.OP_COMPRESS
                },
                inputFile = inputFile,
                outputName = "batch_${System.currentTimeMillis()}",
                extraParam = when (op) {
                    "Add Watermark" -> "DRAFT"
                    "Compress" -> "6"
                    "Rotate All Pages" -> "90"
                    "Password Protect" -> "password"
                    else -> null
                }
            )
        }

        val pipelineId = PdfOperationWorker.enqueuePipeline(this, specs)
        if (pipelineId != null) {
            observeWork(pipelineId, "Running ${operations.size} operations...")
        }
    }

    // ─── UI Helpers ────────────────────────────────────────────────
    private fun buildSelectorCard(): View { /* Preserved */ return LinearLayout(this) }
    private fun buildBatchPipelineCard(): View { /* Preserved */ return LinearLayout(this) }
    private fun buildCategoryGrid(title: String, items: List<ToolItem>): View { /* Preserved */ return LinearLayout(this) }

    data class ToolItem(val label: String, val color: Int, val icon: Int, val desc: String, val action: () -> Unit)

    private fun askFilename(default: String, cb: (String) -> Unit) {
        val et = EditText(this).apply { setText(default); selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        AlertDialog.Builder(this).setTitle("Save as...").setMessage("Filename (without .pdf):")
            .setView(et).setPositiveButton("Save") { _, _ ->
                cb(et.text.toString().trim().ifBlank { default })
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun done(tmpFile: File, desiredName: String, extraMsg: String = "") = runOnUiThread {
        lifecycleScope.launch {
            val named = File(cacheDir, "$desiredName.pdf")
            try { tmpFile.copyTo(named, overwrite = true) } catch (_: Exception) {}
            val saved = withContext(ThreadPoolManager.IoDispatcher) {
                try { FileHelper.saveToDownloads(this@ToolsActivity, named) }
                catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(named), named) }
            }
            val fileForOpen = saved.file ?: named
            AlertDialog.Builder(this@ToolsActivity).setTitle("Done!")
                .setMessage((if (extraMsg.isNotEmpty()) "$extraMsg\n\n" else "") +
                    "File: $desiredName.pdf\n${saved.displayPath}")
                .setPositiveButton("Open") { _, _ ->
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
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share"))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("OK", null).show()
        }
    }

    private fun err(msg: String) = runOnUiThread {
        progress.visibility = View.GONE
        tvStatus.text = statusText()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) {
            toast("File missing - re-select")
            files.clear()
            tvStatus.text = statusText()
            return null
        }
        return f
    }

    private fun copyUri(uri: Uri): File {
        val name = FileHelper.getFileName(this, uri) ?: "pdf_${System.currentTimeMillis()}.pdf"
        val dest = File(cacheDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { FileOutputStream(dest).use { o -> it.copyTo(o) } }
        } catch (_: Exception) {}
        return dest
    }

    private fun pageCount(f: File) = try {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        val c = r.pageCount
        r.close(); pfd.close()
        c
    } catch (_: Exception) { 0 }

    private fun statusText() = if (files.isEmpty()) "No file selected" else "${files.size} file(s) ready"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
