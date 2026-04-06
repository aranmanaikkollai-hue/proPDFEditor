package com.propdf.editor.ui.viewer

import android.content.ClipData

import android.content.ClipboardManager

import android.content.Context

import android.content.Intent

import android.graphics.Color

import android.graphics.Typeface

import android.net.Uri

import android.os.Bundle

import android.text.Spannable

import android.text.SpannableString

import android.text.style.BackgroundColorSpan

import android.view.Gravity

import android.view.inputmethod.InputMethodManager

import android.widget.EditText

import android.widget.LinearLayout

import android.widget.ScrollView

import android.widget.TextView

import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope

import com.propdf.editor.data.repository.ReflowPage

import com.propdf.editor.data.repository.TextReflowManager

import com.propdf.editor.utils.FileHelper

import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.launch

import javax.inject.Inject

@AndroidEntryPoint

class TextReflowActivity : AppCompatActivity() {

@Inject lateinit var reflowManager: TextReflowManager

// FIX: FileHelper is an \'object\' -- no @Inject. Use
FileHelper.method() directly.

private lateinit var scrollView: ScrollView

private lateinit var container: LinearLayout

private lateinit var tvStatus: TextView

private lateinit var etSearch: EditText

private lateinit var tvSearchResult: TextView

private val textViews = mutableListOf<TextView>()

private var pages: List<ReflowPage> = emptyList()

private var fontSizeSp = 16f

private var searchQuery = ""

//

// LIFECYCLE

//

override fun onCreate(savedInstanceState: Bundle?) {

super.onCreate(savedInstanceState)

buildUI()

val uriStr = intent.getStringExtra(EXTRA_PDF_URI) ?: run { finish();
return }

loadPdf(Uri.parse(uriStr))

}

//

// UI BUILD

//

private fun buildUI() {

val root = LinearLayout(this).apply {

orientation = LinearLayout.VERTICAL

setBackgroundColor(Color.parseColor("#FAFAFA"))

}

// Top bar

val topBar = LinearLayout(this).apply {

orientation = LinearLayout.HORIZONTAL

gravity = Gravity.CENTER_VERTICAL

setBackgroundColor(Color.parseColor("#1A73E8"))

setPadding(dp(12), dp(8), dp(12), dp(8))

layoutParams = LinearLayout.LayoutParams(-1, dp(52))

}

topBar.addView(makeBtn("<") { finish() })

topBar.addView(TextView(this).apply {

text = "Reading Mode"

textSize = 17f; setTextColor(Color.WHITE)

typeface = Typeface.DEFAULT_BOLD

layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

setPadding(dp(8), 0, 0, 0)

})

topBar.addView(makeBtn("A-") { adjustFont(-2f) })

topBar.addView(makeBtn("A+") { adjustFont(+2f) })

topBar.addView(makeBtn("Copy") { copyAllText() })

root.addView(topBar)

// Search bar

val searchBar = LinearLayout(this).apply {

orientation = LinearLayout.HORIZONTAL

gravity = Gravity.CENTER_VERTICAL

setBackgroundColor(Color.WHITE)

setPadding(dp(8), dp(4), dp(8), dp(4))

layoutParams = LinearLayout.LayoutParams(-1, dp(44))

}

etSearch = EditText(this).apply {

hint = "Search in text\..."

textSize = 14f; setTextColor(Color.BLACK)

setHintTextColor(Color.parseColor("#AAAAAA"))

layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

setSingleLine()

setOnEditorActionListener { _, _, _ ->
doSearch(etSearch.text.toString()); true }

}

tvSearchResult = TextView(this).apply {

textSize = 11f; setTextColor(Color.parseColor("#888888"))

setPadding(dp(4), 0, 0, 0)

}

searchBar.addView(etSearch)

searchBar.addView(makeBtn("Find") { doSearch(etSearch.text.toString())
})

searchBar.addView(makeBtn("X") { clearSearch() })

searchBar.addView(tvSearchResult)

root.addView(searchBar)

// Status line

tvStatus = TextView(this).apply {

text = "Loading\..."

textSize = 13f; setTextColor(Color.parseColor("#888888"))

setPadding(dp(16), dp(8), dp(16), dp(4))

}

root.addView(tvStatus)

// Scrollable text area -- rule #14: isVerticalScrollBarEnabled = false

scrollView = ScrollView(this).apply {

layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)

isVerticalScrollBarEnabled = false

}

container = LinearLayout(this).apply {

orientation = LinearLayout.VERTICAL

setPadding(dp(16), dp(8), dp(16), dp(32))

}

scrollView.addView(container)

root.addView(scrollView)

setContentView(root)

}

//

// LOAD PDF

//

private fun loadPdf(uri: Uri) {

lifecycleScope.launch {

tvStatus.text = "Extracting text\..."

// FIX: FileHelper is an object -- use it directly, no injection

val file = FileHelper.uriToFile(this@TextReflowActivity, uri)

?: run { tvStatus.text = "Cannot read file"; return@launch }

reflowManager.extractPages(file).onSuccess { extracted ->

pages = extracted

renderPages()

tvStatus.text = "${pages.size} page(s) loaded"

}.onFailure {

tvStatus.text = "Text extraction failed: ${it.message}"

}

}

}

//

// RENDER PAGES

//

private fun renderPages() {

container.removeAllViews()

textViews.clear()

if (pages.isEmpty()) {

container.addView(TextView(this).apply {

text = "No extractable text found.\\n\\nThis may be a scanned image
PDF.\\nUse OCR mode from the viewer instead."

textSize = fontSizeSp; setTextColor(Color.parseColor("#888888"))

gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)

})

return

}

for (page in pages) {

// Page header

container.addView(TextView(this).apply {

text = "--- Page ${page.pageNumber} ---"

textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))

typeface = Typeface.DEFAULT_BOLD

setPadding(0, dp(12), 0, dp(4))

})

// Text view -- rule #9: setTextIsSelectable(true) method, NOT =
assignment

val tv = TextView(this).apply {

text = page.text

textSize = fontSizeSp; setTextColor(Color.parseColor("#1A1A1A"))

setTextIsSelectable(true) // rule #9

lineSpacingMultiplier = 1.4f

setPadding(0, 0, 0, dp(8))

}

container.addView(tv)

textViews.add(tv)

}

if (searchQuery.isNotBlank()) doSearch(searchQuery)

}

//

// FONT SIZE

//

private fun adjustFont(delta: Float) {

fontSizeSp = (fontSizeSp + delta).coerceIn(10f, 32f)

textViews.forEach { it.textSize = fontSizeSp }

}

//

// SEARCH & HIGHLIGHT

//

private fun doSearch(query: String) {

searchQuery = query.trim()

hideKeyboard()

if (searchQuery.isBlank()) { clearSearch(); return }

var totalMatches = 0

var firstHitView: TextView? = null

textViews.forEachIndexed { idx, tv ->

val pageText = pages.getOrNull(idx)?.text ?: ""

val lowerText = pageText.lowercase()

val lowerQuery = searchQuery.lowercase()

if (!lowerText.contains(lowerQuery)) {

tv.text = pageText; return@forEachIndexed

}

val spannable = SpannableString(pageText)

var start = lowerText.indexOf(lowerQuery)

while (start >= 0) {

spannable.setSpan(

BackgroundColorSpan(Color.parseColor("#FFFF00")),

start, start + lowerQuery.length,

Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

)

totalMatches++

start = lowerText.indexOf(lowerQuery, start + 1)

}

tv.text = spannable

if (firstHitView == null) firstHitView = tv

}

tvSearchResult.text = if (totalMatches == 0) "Not found" else
"\$totalMatches result(s)"

firstHitView?.let { hit ->

scrollView.post { scrollView.smoothScrollTo(0, hit.top) }

}

}

private fun clearSearch() {

searchQuery = ""

etSearch.text?.clear()

tvSearchResult.text = ""

textViews.forEachIndexed { idx, tv -> tv.text =
pages.getOrNull(idx)?.text ?: "" }

}

//

// COPY ALL

//

private fun copyAllText() {

val all = pages.joinToString("\\n\\n") { "--- Page
${it.pageNumber} ---\\n${it.text}" }

val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as
ClipboardManager

clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", all))

Toast.makeText(this, "Text copied to clipboard",
Toast.LENGTH_SHORT).show()

}

//

// HELPERS

//

private fun makeBtn(label: String, onClick: () -> Unit): TextView {

return TextView(this).apply {

text = label; textSize = 13f; setTextColor(Color.WHITE)

setPadding(dp(8), dp(6), dp(8), dp(6))

setOnClickListener { onClick() }

}

}

private fun hideKeyboard() {

val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as
InputMethodManager

imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)

}

private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

companion object {

const val EXTRA_PDF_URI = "extra_pdf_uri"

fun start(context: Context, pdfUri: Uri) {

context.startActivity(

Intent(context, TextReflowActivity::class.java)

.putExtra(EXTRA_PDF_URI, pdfUri.toString())

)

}

}

}

**5.6 New Files --- Categories & Shortcuts**

