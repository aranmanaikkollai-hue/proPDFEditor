
    package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ViewerActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var searchCountLabel: TextView
    private lateinit var annotSubMenuRow: LinearLayout
    private lateinit var annotGroupNavBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Root Layout (The Window)
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // 2. PDF Container (The Center)
        val pdfContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.BLACK)
        }
        root.addView(pdfContainer, RelativeLayout.LayoutParams(-1, -1))

        // 3. Search Bar (The Top)
        val searchBar = buildSearchBar()
        val topParams = RelativeLayout.LayoutParams(-1, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        root.addView(searchBar, topParams)

        // 4. Annotation Toolbar (The Bottom)
        val bottomBar = buildAnnotationToolbar()
        val bottomParams = RelativeLayout.LayoutParams(-1, -2).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        root.addView(bottomBar, bottomParams)

        setContentView(root)
    }

    private fun buildSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))

            searchInput = EditText(this@ViewerActivity).apply {
                hint = "Search PDF..."
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0E0E0E"))
                    cornerRadius = dp(8).toFloat()
                }
            }
            addView(searchInput)

            searchCountLabel = TextView(this@ViewerActivity).apply {
                setTextColor(Color.CYAN)
                text = "0 matches"
            }
            addView(searchCountLabel)
        }
    }

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply { 
                orientation = LinearLayout.HORIZONTAL 
            }
            addView(annotSubMenuRow)
            
            annotGroupNavBar = LinearLayout(this@ViewerActivity).apply { 
                orientation = LinearLayout.HORIZONTAL 
            }
            addView(annotGroupNavBar)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun start(context: Context, uri: Uri, password: String? = null) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra("extra_pdf_uri", uri.toString())
                if (password != null) putExtra("extra_pdf_password", password)
            }
            context.startActivity(intent)
        }
    }
}
