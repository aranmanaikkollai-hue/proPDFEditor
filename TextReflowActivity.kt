// FILE: TextReflowActivity.kt
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/ui/viewer/TextReflowActivity.kt
//
// FEATURE: Text reflow reading mode
//   - Renders extracted PDF text as a scrollable LinearLayout of TextViews
//   - Font-size control (A- / A+)
//   - Inline search with highlight
//   - Copy-all button
//   - Tamil / any Unicode text renders correctly via system font
//
// RULES OBEYED:
//   - setTextIsSelectable(true) method call, NOT val assignment (rule #9)
//   - isVerticalScrollBarEnabled = false (rule #14)
//   - No FrameLayout.LayoutParams(w,h,weight) (rule #10)
//   - scrollbars NOT set via scrollbars property (rule #14)
//   - Pure ASCII (rule #32)

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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
    @Inject lateinit var fileHelper: FileHelper

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvSearchResult: TextView

    private val textViews = mutableListOf<TextView>()
    private var pages: List<ReflowPage> = emptyList()
    private var fontSizeSp = 16f
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        val uriStr = intent.getStringExtra(EXTRA_PDF_URI) ?: run { finish(); return }
        val uri    = Uri.parse(uriStr)
        loadPdf(uri)
    }

    // -----------------------------------------------------------------------
    // UI BUILD
    // -----------------------------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
        }

        // ---- TOP BAR ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
        }
        val btnBack = makeBtn("<") { finish() }
        val tvTitle = TextView(this).apply {
            text = "Reading Mode"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(8), 0, 0, 0)
        }
        val btnFontDown = makeBtn("A-") { adjustFont(-2f) }
        val btnFontUp   = makeBtn("A+") { adjustFont(+2f) }
        val btnCopy     = makeBtn("Copy") { copyAllText() }
        topBar.addView(btnBack); topBar.addView(tvTitle)
        topBar.addView(btnFontDown); topBar.addView(btnFontUp); topBar.addView(btnCopy)
        root.addView(topBar)

        // ---- SEARCH BAR ----
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        }
        etSearch = EditText(this).apply {
            hint     = "Search in text..."
            textSize = 14f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setSingleLine()
            setImeActionLabel("Search", android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
            setOnEditorActionListener { _, _, _ -> doSearch(etSearch.text.toString()); true }
        }
        val btnSearch = makeBtn("Find") { doSearch(etSearch.text.toString()) }
        val btnClear  = makeBtn("X") { clearSearch() }
        tvSearchResult = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(4), 0, 0, 0)
        }
        searchBar.addView(etSearch); searchBar.addView(btnSearch); searchBar.addView(btnClear)
        searchBar.addView(tvSearchResult)
        root.addView(searchBar)

        // ---- STATUS ----
        tvStatus = TextView(this).apply {
            text = "Loading..."
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        root.addView(tvStatus)

        // ---- SCROLLABLE TEXT ----
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false  // rule #14
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scrollView.addView(container)
        root.addView(scrollView)

        setContentView(root)
    }

    // -----------------------------------------------------------------------
    // LOAD PDF
    // -----------------------------------------------------------------------

    private fun loadPdf(uri: Uri) {
        lifecycleScope.launch {
            tvStatus.text = "Extracting text..."
            val file = FileHelper.uriToFile(this@TextReflowActivity, uri)
                ?: run { tvStatus.text = "Cannot read file"; return@launch }

            reflowManager.extractPages(file).onSuccess { extracted ->
                pages = extracted
                renderPages()
                tvStatus.text = "${pages.size} page(s)"
            }.onFailure {
                tvStatus.text = "Text extraction failed: ${it.message}"
            }
        }
    }

    // -----------------------------------------------------------------------
    // RENDER
    // -----------------------------------------------------------------------

    private fun renderPages() {
        container.removeAllViews()
        textViews.clear()
        if (pages.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No extractable text found in this PDF.\n\nThis may be a scanned document -- use OCR mode instead."
                textSize = fontSizeSp
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }

        for (page in pages) {
            // Page number header
            container.addView(TextView(this).apply {
                text = "--- Page ${page.pageNumber} ---"
                textSize = 11f
                setTextColor(Color.parseColor("#AAAAAA"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(4))
            })

            // Text view for page content
            val tv = TextView(this).apply {
                text = page.text
                textSize = fontSizeSp
                setTextColor(Color.parseColor("#1A1A1A"))
                typeface = Typeface.DEFAULT
                setTextIsSelectable(true)     // rule #9: method call, NOT = assignment
                setPadding(0, 0, 0, dp(8))
                lineSpacingMultiplier = 1.4f
            }
            container.addView(tv)
            textViews.add(tv)
        }

        // Re-apply search highlight if active
        if (searchQuery.isNotBlank()) doSearch(searchQuery)
    }

    // -----------------------------------------------------------------------
    // FONT SIZE
    // -----------------------------------------------------------------------

    private fun adjustFont(delta: Float) {
        fontSizeSp = (fontSizeSp + delta).coerceIn(10f, 32f)
        textViews.forEach { it.textSize = fontSizeSp }
    }

    // -----------------------------------------------------------------------
    // SEARCH
    // -----------------------------------------------------------------------

    private fun doSearch(query: String) {
        searchQuery = query.trim()
        hideKeyboard()
        if (searchQuery.isBlank()) { clearSearch(); return }

        var matchCount = 0
        textViews.forEachIndexed { idx, tv ->
            val pageText = pages.getOrNull(idx)?.text ?: ""
            val lowerText  = pageText.lowercase()
            val lowerQuery = searchQuery.lowercase()

            if (lowerText.contains(lowerQuery)) {
                val spannable = SpannableString(pageText)
                var start = lowerText.indexOf(lowerQuery)
                while (start >= 0) {
                    spannable.setSpan(
                        BackgroundColorSpan(Color.parseColor("#FFFF00")),
                        start, start + searchQuery.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    matchCount++
                    start = lowerText.indexOf(lowerQuery, start + 1)
                }
                tv.text = spannable

                // Scroll to first match on first page found
                if (matchCount == searchQuery.length) {
                    scrollView.post { scrollView.smoothScrollTo(0, tv.top) }
                }
            } else {
                tv.text = pageText
            }
        }

        tvSearchResult.text = if (matchCount == 0) "Not found" else "$matchCount result(s)"
    }

    private fun clearSearch() {
        searchQuery = ""
        etSearch.text?.clear()
        tvSearchResult.text = ""
        textViews.forEachIndexed { idx, tv ->
            tv.text = pages.getOrNull(idx)?.text ?: ""
        }
    }

    // -----------------------------------------------------------------------
    // COPY ALL
    // -----------------------------------------------------------------------

    private fun copyAllText() {
        val all = pages.joinToString("\n\n") { "--- Page ${it.pageNumber} ---\n${it.text}" }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", all))
        Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    private fun makeBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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
