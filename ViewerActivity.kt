package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Combined UI logic for ProPDF Editor Viewer.
 * Contains both Search and Premium Annotation functionalities.
 *
 */
class ViewerActivity : AppCompatActivity() {

    // --- State Variables from Section A ---
    private var activeAnnotGroup: String = "markup"
    private lateinit var annotSubMenuRow: LinearLayout
    private lateinit var annotGroupNavBar: LinearLayout
    private lateinit var annotSettingsPill: LinearLayout
    private lateinit var annotWeightValue: TextView
    private lateinit var annotWeightBar: SeekBar
    private val annotSwatchViews = mutableListOf<View>()
    private lateinit var searchCountLabel: TextView
    private lateinit var searchInput: EditText

    // Base properties (assumed existing in core app)
    private var activeTool: String = "freehand"
    private var activeColor: Int = Color.parseColor("#007AFF")
    private var strokeWidth: Float = 5f
    private var searchResultIdx: Int = 0
    private var searchResults: List<Int> = emptyList()

    // Tool Mapping Data
    private val ANNOT_GROUPS = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "undo", "redo", "save")
    )

    private val TOOL_ICON = mapOf(
        "freehand" to android.R.drawable.ic_menu_edit,
        "highlight" to android.R.drawable.ic_menu_view,
        "eraser" to android.R.drawable.ic_menu_close_clear_cancel,
        "save" to android.R.drawable.ic_menu_save
        // ... (other icons mapped as per Section A)
    )

    // --- Functional Logic from Section B ---

    /**
     * Builds the Search Bar with dark input and gradient GO button.
     */
    private fun buildSearchBar(): LinearLayout {
        val cBg = Color.parseColor("#1A1A1A")
        val cInput = Color.parseColor("#0E0E0E")
        val cGoFrom = Color.parseColor("#ADC6FF")
        val cGoTo = Color.parseColor("#4B8EFF")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) }
        }

        searchInput = EditText(this).apply {
            hint = "Search in PDF..."
            setHintTextColor(Color.parseColor("#8B90A0"))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(cInput)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), 0, dp(44), 0)
        }
        inputWrap.addView(searchInput)

        val goBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46))
            text = "GO"
            setTextColor(Color.parseColor("#002E69"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                colors = intArrayOf(cGoFrom, cGoTo)
                orientation = GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { runSearch() }
        }

        inputRow.addView(inputWrap)
        inputRow.addView(goBtn)
        container.addView(inputRow)

        searchCountLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64B5F6"))
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(searchCountLabel)

        return container
    }

    /**
     * Builds the Premium Annotation Toolbar with Settings Pill and Group Nav.
     */
    private fun buildAnnotationToolbar(): LinearLayout {
        val cOuter = Color.parseColor("#131313")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cOuter)
            elevation = dp(8).toFloat()

            // Settings Pill
            val pillWrapper = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            }
            annotSettingsPill = buildAnnotSettingsPill()
            pillWrapper.addView(annotSettingsPill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            addView(pillWrapper)

            // Tool Sub-Menu
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            // Group Navigation Bar
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            refreshAnnotSubMenu("markup")
        }
    }

    // --- Helper UI Methods ---

    private fun buildAnnotSettingsPill(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = dp(24).toFloat()
            }
            // Add Weight/Color UI elements as defined in Section B...
        }
    }

    private fun buildAnnotGroupNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
            // Loop through groups and add nav items...
        }
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        annotSubMenuRow.removeAllViews()
        ANNOT_GROUPS[groupId]?.forEach { toolId ->
            // Build and add tool cell...
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun runSearch() {
        // Search implementation...
        updateSearchCounter()
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = if (searchResults.isEmpty()) "No results" else "${searchResultIdx + 1} of ${searchResults.size}"
    }
}
