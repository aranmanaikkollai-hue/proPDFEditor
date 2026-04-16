package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.ui.tools.ToolsActivity
import kotlinx.coroutines.launch

class ViewerActivity : AppCompatActivity() {

    private var currentPage: Int = 0
    private var totalPages: Int = 0
    private var searchResultIdx: Int = 0
    private var searchResults: List<Int> = emptyList()
    private var pdfUri: Uri? = null
    private var pdfPassword: String? = null

    private var activeAnnotGroup: String = "markup"
    private lateinit var annotSubMenuRow: LinearLayout
    private lateinit var annotGroupNavBar: LinearLayout
    private lateinit var searchCountLabel: TextView
    private lateinit var searchInput: EditText

    private var activeTool: String = "freehand"
    private var activeColor: Int = Color.parseColor("#007AFF")
    private var strokeWidth: Float = 5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val uriStr = intent.getStringExtra(EXTRA_URI)
        pdfUri = if (uriStr != null) Uri.parse(uriStr) else null
        pdfPassword = intent.getStringExtra(EXTRA_PASSWORD)

        // ROOT LAYOUT
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // 1. PDF CONTENT VIEW (The Center)
        val pdfViewContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.DKGRAY) 
        }
        val pdfParams = RelativeLayout.LayoutParams(-1, -1)
        root.addView(pdfViewContainer, pdfParams)

        // 2. SEARCH BAR (The Top)
        val searchBar = buildSearchBar()
        val searchParams = RelativeLayout.LayoutParams(-1, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        root.addView(searchBar, searchParams)

        // 3. ANNOTATION TOOLBAR (The Bottom)
        val annotToolbar = buildAnnotationToolbar()
        val annotParams = RelativeLayout.LayoutParams(-1, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        root.addView(annotToolbar, annotParams)

        setContentView(root)
    }

    private fun buildSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(4).toFloat()

            val inputRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            searchInput = EditText(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
                hint = "Search PDF..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0E0E0E"))
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(15), 0, dp(15), 0)
            }
            
            val searchBtn = TextView(this@ViewerActivity).apply {
                text = "FIND"
                setTextColor(Color.CYAN)
                setPadding(dp(15), 0, dp(10), 0)
                setOnClickListener { runSearch() }
            }

            inputRow.addView(searchInput)
            inputRow.addView(searchBtn)
            addView(inputRow)

            searchCountLabel = TextView(this@ViewerActivity).apply {
                setTextColor(Color.LTGRAY)
                textSize = 12f
                text = "Matches: 0"
                setPadding(0, dp(5), 0, 0)
            }
            addView(searchCountLabel)
        }
    }

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            elevation = dp(8).toFloat()

            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            addView(annotSubMenuRow)

            annotGroupNavBar = LinearLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(60))
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                gravity = Gravity.CENTER
            }
            addView(annotGroupNavBar)
        }
    }

    private fun runSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        searchCountLabel.text = "Searching..."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "extra_pdf_uri"
        const val EXTRA_PASSWORD = "extra_pdf_password"

        fun start(context: Context, uri: Uri, password: String? = null) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                if (password != null) putExtra(EXTRA_PASSWORD, password)
            }
            context.startActivity(intent)
        }
    }
}
