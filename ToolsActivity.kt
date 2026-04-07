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

    // Batch pipeline: list of pending operations
    private val pipeline = mutableListOf<String>()
    private lateinit var tvPipeline : TextView

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
            setPadding(dp(12), dp(8), dp(12), dp(80))
            setBackgroundColor(0xFFF5F7FF.toInt())
        }
        scroll.addView(root); setContentView(scroll)

        tvStatus = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF555555.toInt()); text = statusText()
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(0,dp(2),0,dp(2)) }
            isIndeterminate = true; visibility = View.GONE
        }
        tvPipeline = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#1A73E8"))
            setPadding(dp(4), dp(2), dp(4), dp(4)); visibility = View.GONE
        }

        // File selector
        root.addView(buildSelectorCard())
        root.addView(tvStatus); root.addView(progress)

        // Batch pipeline card
        root.addView(buildBatchPipelineCard())
        root.addView(tvPipeline)

        // Tool categories using bento grid
        root.addView(buildCategoryGrid("Organize", listOf(
            ToolItem("Merge PDFs",       0xFF2E7D32.toInt(), android.R.drawable.ic_menu_add,           "Combine 2+ PDFs") { doMerge() },
            ToolItem("Split PDF",        0xFF1565C0.toInt(), android.R.drawable.ic_menu_crop,          "Extract page range") { doSplit() },
            ToolItem("Extract Pages",    0xFF6A1B9A.toInt(), android.R.drawable.ic_menu_send,          "Save specific pages") { doExtract() },
            ToolItem("Delete Pages",     0xFFC62828.toInt(), android.R.drawable.ic_menu_delete,        "Remove pages") { doDeletePages() },
        )))
        root.addView(buildCategoryGrid("Optimize", listOf(
            ToolItem("Compress PDF",     0xFF00695C.toInt(), android.R.drawable.ic_menu_preferences,   "Reduce file size") { doCompress() },
            ToolItem("Rotate Pages",     0xFF37474F.toInt(), android.R.drawable.ic_menu_rotate,        "90/180/270 deg") { doRotate() },
            ToolItem("Page Numbers",     0xFF1A237E.toInt(), android.R.drawable.ic_menu_agenda,        "Add numbering") { doPageNumbers() },
            ToolItem("Header/Footer",    0xFF33691E.toInt(), android.R.drawable.ic_menu_edit,          "Add top/bottom text") { doHeaderFooter() },
        )))
        root.addView(buildCategoryGrid("Security", listOf(
            ToolItem("Password Protect", 0xFFB71C1C.toInt(), android.R.drawable.ic_lock_lock,         "AES-256 encrypt") { doEncrypt() },
            ToolItem("Remove Password",  0xFFE65100.toInt(), android.R.drawable.ic_lock_idle_lock,    "Decrypt PDF") { doDecrypt() },
            ToolItem("Add Watermark",    0xFF004D40.toInt(), android.R.drawable.ic_menu_view,         "Diagonal overlay") { doWatermark() },
        )))
        root.addView(buildCategoryGrid("Convert", listOf(
            ToolItem("Images to PDF",    0xFF0D47A1.toInt(), android.R.drawable.ic_menu_gallery,      "JPG/PNG to PDF") { imgPicker.launch(arrayOf("image/*")) },
            ToolItem("PDF to Images",    0xFF4A148C.toInt(), android.R.drawable.ic_menu_camera,       "Each page as JPG") { doPdfToImages() },
        )))
        root.addView(buildCategoryGrid("Share", listOf(
            ToolItem("Share PDF",        0xFF00897B.toInt(), android.R.drawable.ic_menu_share,        "Send via any app") { doShare() },
        )))
    }

    data class ToolItem(val label: String, val color: Int, val icon: Int, val desc: String, val action: () -> Unit)

    private fun buildSelectorCard(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,0)}
            radius=dp(12).toFloat(); cardElevation=dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(14),dp(14),dp(14)) }
        inner.addView(TextView(this).apply { text="Select PDF File"; textSize=14f; android.graphics.Typeface.DEFAULT_BOLD.let{typeface=it}; setTextColor(0xFF111111.toInt()); setPadding(0,0,0,dp(10)) })
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text="Choose File(s)"; layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(0,0,dp(6),0)}
            setTextColor(Color.WHITE); setBackgroundColor(0xFF1A73E8.toInt()); setPadding(dp(8),dp(10),dp(8),dp(10))
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        })
        row.addView(Button(this).apply {
            text="Clear"; layoutParams=LinearLayout.LayoutParams(-2,-2)
            setTextColor(0xFF1A73E8.toInt()); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { files.clear(); tvStatus.text=statusText() }
        })
        inner.addView(row); card.addView(inner); return card
    }

    private fun buildBatchPipelineCard(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,0)}
            radius=dp(12).toFloat(); cardElevation=dp(2).toFloat()
            setCardBackgroundColor(Color.parseColor("#E8F5E9"))
        }
        val inner = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)) }
        inner.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            addView(TextView(this@ToolsActivity).apply {
                text="Batch Pipeline (multi-op)"; textSize=13f
                android.graphics.Typeface.DEFAULT_BOLD.let{typeface=it}
                setTextColor(Color.parseColor("#2E7D32")); layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            })
            addView(Button(this@ToolsActivity).apply {
                text="Queue & Run"; textSize=11f
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32"))
                setOnClickListener { showBatchPipelineDialog() }
            })
        })
        inner.addView(TextView(this).apply {
            text="Chain multiple operations on one file without re-opening each time"
            textSize=11f; setTextColor(Color.parseColor("#555555")); setPadding(0,dp(4),0,0)
        })
        card.addView(inner); return card
    }

    private fun buildCategoryGrid(title: String, items: List<ToolItem>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(12),0,0)}
            radius=dp(12).toFloat(); cardElevation=dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(6)) }
        inner.addView(TextView(this).apply {
            text=title.uppercase(); textSize=11f
            setTextColor(Color.parseColor("#888888"))
            android.graphics.Typeface.DEFAULT_BOLD.let{typeface=it}
            letterSpacing=0.1f; setPadding(0,0,0,dp(8))
        })
        // Grid rows of 2
        var row: LinearLayout? = null
        items.forEachIndexed { i, item ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(8))}
                }
                inner.addView(row)
            }
            val cell = LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
                layoutParams=LinearLayout.LayoutParams(0,dp(80),1f).apply{setMargins(dp(4),0,dp(4),0)}
                setBackgroundColor(Color.parseColor("#F8F9FF"))
                setPadding(dp(8),dp(10),dp(8),dp(10))
                setOnClickListener { item.action() }
            }
            cell.addView(ImageView(this).apply {
                setImageResource(item.icon); setColorFilter(item.color)
                layoutParams=LinearLayout.LayoutParams(dp(28),dp(28))
            })
            cell.addView(TextView(this).apply {
                text=item.label; textSize=11f; gravity=Gravity.CENTER
                android.graphics.Typeface.DEFAULT_BOLD.let{typeface=it}
                setTextColor(Color.parseColor("#222222")); setPadding(0,dp(4),0,dp(2))
            })
            cell.addView(TextView(this).apply {
                text=item.desc; textSize=9f; gravity=Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
            })
            row?.addView(cell)
            // Add empty cell if odd number
            if (i == items.size - 1 && items.size % 2 != 0) {
                row?.addView(View(this).apply {
                    layoutParams=LinearLayout.LayoutParams(0,dp(80),1f).apply{setMargins(dp(4),0,dp(4),0)}
                })
            }
        }
        card.addView(inner); return card
    }

    // ---- Batch Pipeline ------------------------------------------------

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
        progress.isIndeterminate = true; progress.visibility = View.VISIBLE
        tvPipeline.visibility = View.VISIBLE
        var pipelineStates = mutableMapOf<String, Any?>()  // store user inputs per op

        // Collect all inputs first, then run
        collectPipelineInputs(operations, 0, pipelineStates) { inputs ->
            run("Running ${inputs.size} operation(s)...") {
                var currentFile = inputFile
                var stepNum = 0
                for ((op, param) in inputs) {
                    stepNum++
                    withContext(Dispatchers.Main) { tvPipeline.text = "Step $stepNum/${inputs.size}: $op..." }
                    val tmp = FileHelper.tempFile(this@ToolsActivity, "batch_step_$stepNum")
                    val result = when (op) {
                        "Add Page Numbers" -> pdfOps.addPageNumbers(currentFile, tmp)
                        "Add Watermark"    -> pdfOps.addTextWatermark(currentFile, tmp, param as? String ?: "DRAFT")
                        "Compress"         -> pdfOps.compressPdf(currentFile, tmp, 9)
                        "Rotate All Pages" -> {
                            val n = pageCount(currentFile)
                            pdfOps.rotatePages(currentFile, tmp, (1..n).associateWith { 90 })
                        }
                        "Password Protect" -> pdfOps.encryptPdf(currentFile, tmp, param as? String, param as? String ?: "pass")
                        "Add Header/Footer"-> {
                            val hf = param as? Pair<*, *>
                            pdfOps.addHeaderFooter(currentFile, tmp, hf?.first as? String, hf?.second as? String)
                        }
                        "Delete Pages"     -> {
                            val pages = (param as? String)?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                            if (pages.isNotEmpty()) pdfOps.deletePages(currentFile, tmp, pages) else Result.success(currentFile)
                        }
                        else -> Result.success(currentFile)
                    }
                    result.fold(
                        onSuccess = { currentFile = it },
                        onFailure = { withContext(Dispatchers.Main) { err("Step $stepNum ($op) failed: ${it.message}") }; return@run }
                    )
                }
                // Save final result
                val finalName = "${inputFile.nameWithoutExtension}_batch"
                withContext(Dispatchers.Main) {
                    tvPipeline.text = "All ${inputs.size} operations complete!"
                    done(currentFile, finalName)
                }
            }
        }
    }

    private fun collectPipelineInputs(
        operations: List<String>, idx: Int,
        accumulated: MutableMap<String, Any?>,
        onDone: (Map<String, Any?>) -> Unit
    ) {
        if (idx >= operations.size) { onDone(accumulated); return }
        val op = operations[idx]
        when (op) {
            "Add Watermark" -> {
                val et = EditText(this).apply { setText("DRAFT"); setPadding(dp(20),dp(8),dp(20),dp(8)) }
                AlertDialog.Builder(this).setTitle("Watermark Text").setView(et)
                    .setPositiveButton("Next") { _, _ ->
                        accumulated[op] = et.text.toString()
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.setNegativeButton("Skip") { _, _ ->
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.show()
            }
            "Password Protect" -> {
                val et = EditText(this).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(dp(20),dp(8),dp(20),dp(8)) }
                AlertDialog.Builder(this).setTitle("Password for Protection").setView(et)
                    .setPositiveButton("Next") { _, _ ->
                        accumulated[op] = et.text.toString().ifBlank { "password" }
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.setNegativeButton("Skip") { _, _ ->
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.show()
            }
            "Add Header/Footer" -> {
                val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
                val etH = EditText(this).apply { hint="Header (blank = none)" }
                val etF = EditText(this).apply { hint="Footer (blank = none)" }
                lay.addView(TextView(this).apply { text="Header:" }); lay.addView(etH)
                lay.addView(TextView(this).apply { text="Footer:"; setPadding(0,dp(8),0,0) }); lay.addView(etF)
                AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
                    .setPositiveButton("Next") { _, _ ->
                        accumulated[op] = Pair(etH.text.toString().ifBlank{null}, etF.text.toString().ifBlank{null})
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.setNegativeButton("Skip") { _, _ ->
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.show()
            }
            "Delete Pages" -> {
                val et = EditText(this).apply { hint="Pages e.g. 1,3,5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
                AlertDialog.Builder(this).setTitle("Pages to Delete").setView(et)
                    .setPositiveButton("Next") { _, _ ->
                        accumulated[op] = et.text.toString()
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.setNegativeButton("Skip") { _, _ ->
                        collectPipelineInputs(operations, idx + 1, accumulated, onDone)
                    }.show()
            }
            else -> {
                accumulated[op] = null
                collectPipelineInputs(operations, idx + 1, accumulated, onDone)
            }
        }
    }

    // ---- Individual operations ----------------------------------------

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
        val et = EditText(this).apply { hint="Range e.g. 1-3"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Split PDF").setView(et)
            .setPositiveButton("Next") { _, _ ->
                val parts = et.text.toString().trim().split("-")
                val from  = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val to    = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (from == null || to == null || from > to) { toast("Enter range like: 1-3"); return@setPositiveButton }
                askFilename("split_${from}_$to") { name ->
                    run("Splitting $from-$to...") {
                        pdfOps.splitPdf(f, cacheDir, listOf(from..to)).fold(
                            onSuccess = { files -> files.firstOrNull()?.let { done(it, name) } ?: err("No output") },
                            onFailure = { err("Failed: ${it.message}") }
                        )
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doExtract() {
        val f = need() ?: return
        val et = EditText(this).apply { hint="Pages e.g. 1-3,5,7-9"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Extract Pages")
            .setMessage("Enter page numbers or ranges (comma-separated):")
            .setView(et)
            .setPositiveButton("Extract") { _, _ ->
                val ranges = mutableListOf<IntRange>()
                et.text.toString().split(",").forEach { part ->
                    val t = part.trim()
                    if (t.contains("-")) {
                        val ab = t.split("-")
                        val a  = ab.getOrNull(0)?.trim()?.toIntOrNull()
                        val b  = ab.getOrNull(1)?.trim()?.toIntOrNull()
                        if (a != null && b != null && a <= b) ranges.add(a..b)
                    } else t.toIntOrNull()?.let { ranges.add(it..it) }
                }
                if (ranges.isEmpty()) { toast("Enter valid pages"); return@setPositiveButton }
                askFilename("extracted") { name ->
                    run("Extracting pages...") {
                        val tmpFiles = mutableListOf<File>()
                        ranges.forEachIndexed { i, range ->
                            val tmp = File(cacheDir, "ext_${System.currentTimeMillis()}_$i.pdf")
                            pdfOps.splitPdf(f, cacheDir, listOf(range)).fold(
                                onSuccess = { parts -> parts.firstOrNull()?.copyTo(tmp, overwrite=true)?.let { tmpFiles.add(tmp) } },
                                onFailure = {}
                            )
                        }
                        if (tmpFiles.isEmpty()) { err("No pages extracted"); return@run }
                        pdfOps.mergePdfs(tmpFiles, FileHelper.tempFile(this@ToolsActivity, name)).fold(
                            onSuccess = { done(it, name) }, onFailure = { err("Failed: ${it.message}") }
                        )
                        tmpFiles.forEach { it.delete() }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doCompress() {
        val f = need() ?: return
        AlertDialog.Builder(this).setTitle("Compression Level")
            .setItems(arrayOf("High quality (less compression)","Medium (recommended)","Maximum compression")) { _, which ->
                val level = when (which) { 0->3; 1->6; else->9 }
                askFilename("compressed") { name ->
                    run("Compressing...") {
                        pdfOps.compressPdf(f, FileHelper.tempFile(this@ToolsActivity, name), level).fold(
                            onSuccess = {
                                val pct = if(f.length()>0) ((f.length()-it.length())*100L/f.length()) else 0L
                                done(it, name, if(pct>0) "$pct% size reduction" else "PDF rewritten (already optimized)")
                            },
                            onFailure = { err("Failed: ${it.message}") }
                        )
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doEncrypt() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val et1 = EditText(this).apply { hint="Password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val et2 = EditText(this).apply { hint="Confirm"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        lay.addView(et1); lay.addView(et2)
        AlertDialog.Builder(this).setTitle("Password Protect").setView(lay)
            .setPositiveButton("Next") { _, _ ->
                val pw = et1.text.toString()
                if (pw.isBlank() || pw != et2.text.toString()) { toast("Passwords must match"); return@setPositiveButton }
                askFilename("protected") { name ->
                    run("Encrypting AES-256...") {
                        pdfOps.encryptPdf(f, FileHelper.tempFile(this@ToolsActivity, name), pw, pw)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDecrypt() {
        val f = need() ?: return
        val et = EditText(this).apply { hint="Current password"; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Remove Password").setView(et)
            .setPositiveButton("Remove") { _, _ ->
                askFilename("unlocked") { name ->
                    run("Removing password...") {
                        pdfOps.removePdfPassword(f, FileHelper.tempFile(this@ToolsActivity, name), et.text.toString())
                            .onSuccess { done(it, name) }.onFailure { err("Wrong password: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doWatermark() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        val etText = EditText(this).apply { setText("CONFIDENTIAL"); hint="Watermark text" }
        val etOpac = EditText(this).apply { setText("30"); hint="Opacity % (1-100)"; inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        lay.addView(etText); lay.addView(etOpac)
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

    private fun doRotate() {
        val f = need() ?: return; val n = pageCount(f)
        if (n == 0) { toast("Cannot read page count"); return }
        var deg = 90
        AlertDialog.Builder(this).setTitle("Rotate All Pages")
            .setSingleChoiceItems(arrayOf("90 right","180","90 left (270)"), 0) { _, which -> deg = when(which){0->90;1->180;else->270} }
            .setPositiveButton("Rotate") { _, _ ->
                askFilename("rotated_${deg}deg") { name ->
                    run("Rotating...") {
                        pdfOps.rotatePages(f, FileHelper.tempFile(this@ToolsActivity, name), (1..n).associateWith { deg })
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doDeletePages() {
        val f = need() ?: return
        val et = EditText(this).apply { hint="Pages e.g. 1,3,5"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        AlertDialog.Builder(this).setTitle("Delete Pages").setView(et)
            .setPositiveButton("Delete") { _, _ ->
                val pages = et.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (pages.isEmpty()) { toast("Enter page numbers"); return@setPositiveButton }
                askFilename("deleted_pages") { name ->
                    run("Deleting pages...") {
                        pdfOps.deletePages(f, FileHelper.tempFile(this@ToolsActivity, name), pages)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doPageNumbers() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val etFmt = EditText(this).apply { setText("Page %d of %d"); hint="Format" }
        lay.addView(TextView(this).apply { text="Format (%d = page, second %d = total):" }); lay.addView(etFmt)
        lay.addView(TextView(this).apply { text="Placement:"; setPadding(0,dp(8),0,dp(4)) })
        val rgPlacement = RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        val rbBottom = RadioButton(this).apply { text="Bottom"; isChecked=true }; val rbTop = RadioButton(this).apply { text="Top" }
        rgPlacement.addView(rbBottom); rgPlacement.addView(rbTop)
        lay.addView(rgPlacement)
        lay.addView(TextView(this).apply { text="Alignment:"; setPadding(0,dp(8),0,dp(4)) })
        val rgAlign = RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        val rbLeft = RadioButton(this).apply { text="Left" }; val rbCenter = RadioButton(this).apply { text="Center"; isChecked=true }; val rbRight = RadioButton(this).apply { text="Right" }
        rgAlign.addView(rbLeft); rgAlign.addView(rbCenter); rgAlign.addView(rbRight)
        lay.addView(rgAlign)
        AlertDialog.Builder(this).setTitle("Add Page Numbers").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val fmt = etFmt.text.toString().ifBlank { "Page %d of %d" }
                val placement = if (rbTop.isChecked) "top" else "bottom"
                val alignment = when { rbLeft.isChecked -> "left"; rbRight.isChecked -> "right"; else -> "center" }
                askFilename("numbered") { name ->
                    run("Adding page numbers...") {
                        pdfOps.addPageNumbers(f, FileHelper.tempFile(this@ToolsActivity, name), fmt, placement, alignment)
                            .onSuccess { done(it, name) }.onFailure { err("Failed: ${it.message}") }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doHeaderFooter() {
        val f = need() ?: return
        val lay = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val etH = EditText(this).apply { hint="Header text (blank = none)" }
        val etF = EditText(this).apply { hint="Footer text (blank = none)" }
        lay.addView(TextView(this).apply { text="Header:" }); lay.addView(etH)
        lay.addView(TextView(this).apply { text="Footer:"; setPadding(0,dp(10),0,dp(4)) }); lay.addView(etF)
        lay.addView(TextView(this).apply { text="Header alignment:"; setPadding(0,dp(8),0,dp(4)) })
        val rgH = RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        val rhL=RadioButton(this).apply{text="Left"}; val rhC=RadioButton(this).apply{text="Center";isChecked=true}; val rhR=RadioButton(this).apply{text="Right"}
        rgH.addView(rhL); rgH.addView(rhC); rgH.addView(rhR); lay.addView(rgH)
        lay.addView(TextView(this).apply { text="Footer alignment:"; setPadding(0,dp(8),0,dp(4)) })
        val rgF = RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        val rfL=RadioButton(this).apply{text="Left"}; val rfC=RadioButton(this).apply{text="Center";isChecked=true}; val rfR=RadioButton(this).apply{text="Right"}
        rgF.addView(rfL); rgF.addView(rfC); rgF.addView(rfR); lay.addView(rgF)
        AlertDialog.Builder(this).setTitle("Header / Footer").setView(lay)
            .setPositiveButton("Add") { _, _ ->
                val h  = etH.text.toString().ifBlank { null }
                val ft = etF.text.toString().ifBlank { null }
                if (h == null && ft == null) { toast("Enter at least one"); return@setPositiveButton }
                val hAlign = when { rhL.isChecked->"left"; rhR.isChecked->"right"; else->"center" }
                val fAlign = when { rfL.isChecked->"left"; rfR.isChecked->"right"; else->"center" }
                askFilename("header_footer") { name ->
                    run("Adding header/footer...") {
                        pdfOps.addHeaderFooter(f, FileHelper.tempFile(this@ToolsActivity, name), h, ft, 10f, hAlign, fAlign)
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
                    val tmp = File(cacheDir, imgName)
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle(); FileHelper.saveToDownloads(this@ToolsActivity, tmp); count++
                }
                r.close(); pfd.close()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@ToolsActivity).setTitle("Done!")
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
        lifecycleScope.launch {
            withContext(Dispatchers.IO, block)
            progress.visibility = View.GONE; tvStatus.text = statusText()
        }
    }

    private fun done(tmpFile: File, desiredName: String, extraMsg: String = "") = runOnUiThread {
        lifecycleScope.launch {
            val named = File(cacheDir, "$desiredName.pdf")
            try { tmpFile.copyTo(named, overwrite=true) } catch (_: Exception) {}
            val saved = withContext(Dispatchers.IO) {
                try { FileHelper.saveToDownloads(this@ToolsActivity, named) }
                catch (_: Exception) { FileHelper.SaveResult("app storage", Uri.fromFile(named), named) }
            }
            val fileForOpen = saved.file ?: named
            AlertDialog.Builder(this@ToolsActivity).setTitle("Done!")
                .setMessage((if(extraMsg.isNotEmpty()) "$extraMsg\n\n" else "") +
                    "File: $desiredName.pdf\n${saved.displayPath}")
                .setPositiveButton("Open") { _, _ ->
                    val openUri = try { androidx.core.content.FileProvider.getUriForFile(this@ToolsActivity,"$packageName.provider",fileForOpen) }
                    catch (_: Exception) { Uri.fromFile(fileForOpen) }
                    ViewerActivity.start(this@ToolsActivity, openUri)
                }
                .setNeutralButton("Share") { _, _ ->
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@ToolsActivity,"$packageName.provider",fileForOpen)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share"))
                    } catch(_: Exception){}
                }
                .setNegativeButton("OK", null).show()
        }
    }

    private fun err(msg: String) = runOnUiThread {
        progress.visibility=View.GONE; tvStatus.text=statusText()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun need(): File? {
        if (files.isEmpty()) { toast("Select a PDF first"); return null }
        val f = files.first()
        if (!f.exists() || f.length() == 0L) { toast("File missing - re-select"); files.clear(); tvStatus.text=statusText(); return null }
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
        val r   = PdfRenderer(pfd); val c=r.pageCount; r.close(); pfd.close(); c
    } catch (_: Exception) { 0 }

    private fun statusText() = if (files.isEmpty()) "No file selected" else "${files.size} file(s) ready"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v*resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
