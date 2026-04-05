package com.propdf.editor.ui.scanner

import android.content.Context

import android.content.Intent

import android.graphics.BitmapFactory

import android.graphics.Color

import android.graphics.Typeface

import android.net.Uri

import android.os.Bundle

import android.view.Gravity

import android.view.View

import android.widget.*

import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope

import com.propdf.editor.data.repository.PdfOperationsManager

import com.propdf.editor.ui.MainActivity

import com.propdf.editor.utils.CategoryManager

import com.propdf.editor.utils.FileHelper

import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.launch

import java.io.File

import javax.inject.Inject

\@AndroidEntryPoint

class ScanSaveActivity : AppCompatActivity() {

\@Inject lateinit var pdfOps: PdfOperationsManager

private lateinit var etName: EditText

private lateinit var spinnerQuality: Spinner

private lateinit var spinnerCategory: Spinner

private lateinit var progressBar: ProgressBar

private lateinit var btnSave: TextView

private lateinit var pageStrip: LinearLayout

private val imagePaths: Array<String> by lazy {

intent.getStringArrayExtra(EXTRA_PATHS) ?: emptyArray()

}

override fun onCreate(savedInstanceState: Bundle?) {

super.onCreate(savedInstanceState)

if (imagePaths.isEmpty()) { finish(); return }

buildUI()

loadThumbnails()

}

//
-----------------------------------------------------------------------

// UI

//
-----------------------------------------------------------------------

private fun buildUI() {

val root = LinearLayout(this).apply {

orientation = LinearLayout.VERTICAL

setBackgroundColor(Color.parseColor(\"#F0F4FF\"))

}

// ---- HEADER ----

val header = LinearLayout(this).apply {

orientation = LinearLayout.HORIZONTAL

gravity = Gravity.CENTER_VERTICAL

setBackgroundColor(Color.parseColor(\"#1A73E8\"))

setPadding(dp(12), dp(10), dp(12), dp(10))

layoutParams = LinearLayout.LayoutParams(-1, dp(52))

}

val btnBack = TextView(this).apply {

text = \"<\"; textSize = 18f; setTextColor(Color.WHITE)

setPadding(dp(4), dp(4), dp(16), dp(4))

setOnClickListener { finish() }

}

val tvTitle = TextView(this).apply {

text = \"Save PDF\"

textSize = 18f; setTextColor(Color.WHITE)

typeface = Typeface.DEFAULT_BOLD

layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

}

header.addView(btnBack); header.addView(tvTitle)

root.addView(header)

// ---- PAGE THUMBNAIL STRIP ----

val hsv = HorizontalScrollView(this).apply {

isHorizontalScrollBarEnabled = false // rule #14 variant

layoutParams = LinearLayout.LayoutParams(-1, dp(110)).apply {

setMargins(0, dp(8), 0, 0)

}

}

pageStrip = LinearLayout(this).apply {

orientation = LinearLayout.HORIZONTAL

setPadding(dp(8), 0, dp(8), 0)

}

hsv.addView(pageStrip)

root.addView(hsv)

// ---- FORM ----

val form = LinearLayout(this).apply {

orientation = LinearLayout.VERTICAL

setPadding(dp(16), dp(16), dp(16), dp(16))

layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)

}

// File name

form.addView(label(\"File Name\"))

etName = EditText(this).apply {

setText(\"Scan_\${System.currentTimeMillis()}\")

textSize = 15f; setTextColor(Color.BLACK)

setBackgroundColor(Color.WHITE)

setPadding(dp(10), dp(10), dp(10), dp(10))

layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
setMargins(0,0,0,dp(12)) }

}

form.addView(etName)

// Quality

form.addView(label(\"Image Quality\"))

spinnerQuality = Spinner(this).apply {

adapter = ArrayAdapter(

this@ScanSaveActivity,

android.R.layout.simple_spinner_item,

listOf(\"High (Best)\", \"Medium\", \"Low (Smallest)\")

).also {
it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
}

layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
setMargins(0,0,0,dp(12)) }

}

form.addView(spinnerQuality)

// Category (supports subcategories via CategoryManager)

form.addView(label(\"Category\"))

val allCats = listOf(\"General\") +
CategoryManager.getAllCategories(this)

.filter { it.isNotBlank() }

spinnerCategory = Spinner(this).apply {

adapter = ArrayAdapter(

this@ScanSaveActivity,

android.R.layout.simple_spinner_item,

allCats.map { CategoryManager.displayName(it).ifBlank { it } }

).also {
it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
}

layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
setMargins(0,0,0,dp(16)) }

}

form.addView(spinnerCategory)

// Page count info

form.addView(TextView(this).apply {

text = \"\${imagePaths.size} page(s) to save\"

textSize = 12f; setTextColor(Color.parseColor(\"#888888\"))

layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
setMargins(0,0,0,dp(16)) }

})

// Progress

progressBar = ProgressBar(this).apply {

isIndeterminate = true; visibility = View.GONE

layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { gravity =
Gravity.CENTER }

}

form.addView(progressBar)

root.addView(form)

// ---- SAVE BUTTON ----

btnSave = TextView(this).apply {

text = \"SAVE AS PDF\"

textSize = 15f; setTextColor(Color.WHITE)

typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER

setBackgroundColor(Color.parseColor(\"#1A73E8\"))

setPadding(0, dp(16), 0, dp(16))

layoutParams = LinearLayout.LayoutParams(-1, -2)

setOnClickListener { savePdf() }

}

root.addView(btnSave)

setContentView(root)

}

private fun label(text: String) = TextView(this).apply {

this.text = text; textSize = 12f

setTextColor(Color.parseColor(\"#444444\")); typeface =
Typeface.DEFAULT_BOLD

layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
setMargins(0,0,0,dp(4)) }

}

//
-----------------------------------------------------------------------

// THUMBNAILS

//
-----------------------------------------------------------------------

private fun loadThumbnails() {

for (path in imagePaths) {

val iv = ImageView(this).apply {

layoutParams = LinearLayout.LayoutParams(dp(80), dp(100)).apply {

setMargins(dp(4), dp(4), dp(4), dp(4))

}

scaleType = ImageView.ScaleType.CENTER_CROP

}

try {

val bmp = BitmapFactory.decodeFile(path)

iv.setImageBitmap(bmp)

} catch (_: Exception) {

iv.setBackgroundColor(Color.parseColor(\"#DDDDDD\"))

}

pageStrip.addView(iv)

}

}

//
-----------------------------------------------------------------------

// SAVE

//
-----------------------------------------------------------------------

private fun savePdf() {

val name = etName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

?: \"Scan_\${System.currentTimeMillis()}\"

val quality = when (spinnerQuality.selectedItemPosition) {

1 -> 60 // Medium

2 -> 35 // Low

else -> 92 // High

}

val allCats = listOf(\"General\") +
CategoryManager.getAllCategories(this)

val category = allCats.getOrElse(spinnerCategory.selectedItemPosition) {
\"General\" }

btnSave.isEnabled = false

progressBar.visibility = View.VISIBLE

lifecycleScope.launch {

val files = imagePaths.map { File(it) }.filter { it.exists() }

val outFile = FileHelper.tempFile(this@ScanSaveActivity, name, \"pdf\")

pdfOps.imagesToPdf(files, outFile).onSuccess { pdf ->

val saved = FileHelper.saveToDownloads(this@ScanSaveActivity, pdf)

pdf.delete()

// Save to recent files DB with category

com.propdf.editor.data.local.RecentFilesDatabase.get(this@ScanSaveActivity)

.recentFilesDao()

.insert(com.propdf.editor.data.local.RecentFileEntity(

uri = saved.uri?.toString() ?: pdf.absolutePath,

displayName = \"\$name.pdf\",

fileSizeBytes = outFile.length(),

category = category

))

// Clean up temp images

imagePaths.forEach { try { File(it).delete() } catch (_: Exception) {}
}

// Back to home

startActivity(Intent(this@ScanSaveActivity,
MainActivity::class.java).apply {

flags = Intent.FLAG_ACTIVITY_CLEAR_TOP

})

finish()

}.onFailure {

btnSave.isEnabled = true

progressBar.visibility = View.GONE

Toast.makeText(this@ScanSaveActivity, \"Save failed: \${it.message}\",
Toast.LENGTH_LONG).show()

}

}

}

private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

companion object {

const val EXTRA_PATHS = \"extra_scan_paths\"

fun start(context: Context, paths: Array<String>) {

context.startActivity(

Intent(context, ScanSaveActivity::class.java)

.putExtra(EXTRA_PATHS, paths)

)

}

}

}

**5.3 New Files --- PDF Operations**

**PdfRedactionManager.kt**

Visual PDF redaction. Paints opaque black rectangles via iText
PdfCanvas. Keyword-batch redact finds pages via PDFBox. All iText
closures use try/finally (rule #1).

**Deployed to:** app/src/main/java/com/propdf/editor/data/repository/
