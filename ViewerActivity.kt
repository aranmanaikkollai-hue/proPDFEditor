package com.propdf.editor.ui.viewer

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Combined UI logic for ProPDF Editor Viewer.
 * Contains both Search and Premium Annotation functionalities built programmatically.
 */
class ViewerActivity : AppCompatActivity() {

    // --- Core PDF State ---
    private var currentPage: Int = 0
    private var totalPages: Int = 0
    private var searchResultIdx: Int = 0
    private var searchResults: List<Int> = emptyList()

    [span_2](start_span)// --- SECTION A: STATE VARIABLES (From Patch) ---[span_2](end_span)
    private var activeAnnotGroup: String = "markup"
    private lateinit var annotSubMenuRow: LinearLayout
    private lateinit var annotGroupNavBar: LinearLayout
    private lateinit var annotSettingsPill: LinearLayout
    private lateinit var annotWeightValue: TextView
    private lateinit var annotWeightBar: SeekBar
    private val annotSwatchViews = mutableListOf<View>()
    private lateinit var searchCountLabel: TextView
    private lateinit var searchInput: EditText

    // Base tool properties
    private var activeTool: String = "freehand"
    private var activeColor: Int = Color.parseColor("#007AFF")
    private var strokeWidth: Float = 5f

    [span_3](start_span)// Tool Mapping Data[span_3](end_span)
    private val ANNOT_GROUPS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "undo", "redo", "save")
    )

    private val TOOL_LABEL: Map<String, String> = mapOf(
        "freehand" to "Pen", "highlight" to "High.", "underline" to "Under.",
        "strikeout" to "Strike", "eraser" to "Eraser", "rect" to "Box",
        "circle" to "Circle", "arrow" to "Arrow", "text" to "Text",
        "stamp" to "Stamp", "image" to "Image", "move_text" to "MoveT",
        "move_shape" to "MoveS", "undo" to "Undo", "redo" to "Redo", "save" to "Save"
    )

    private val TOOL_ICON: Map<String, Int> = mapOf(
        "freehand" to android.R.drawable.ic_menu_edit,
        "save" to android.R.drawable.ic_menu_save,
        "eraser" to android.R.drawable.ic_menu_close_clear_cancel
        [span_4](start_span)// Note: Map other icons as needed using android.R.drawable constants[span_4](end_span)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Root Layout Construction
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
    }

    [span_5](start_span)// --- SECTION B: FUNCTIONAL LOGIC (From Patch) ---[span_5](end_span)

    /**
     * [span_6](start_span)Builds the Search Bar - Includes dark input and gradient GO button.[span_6](end_span)
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
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
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
            setOnClickListener {
                hideKeyboard()
                runSearch()
            }
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
     * [span_7](start_span)Premium Annotation Toolbar - 3 Layers: Settings, Tools, and Groups.[span_7](end_span)
     */
    private fun buildAnnotationToolbar(): LinearLayout {
        val cOuter = Color.parseColor("#131313")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cOuter)
            elevation = dp(8).toFloat()

            [span_8](start_span)// Layer 1: Settings Pill[span_8](end_span)
            val pillWrapper = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            }
            annotSettingsPill = buildAnnotSettingsPill()
            pillWrapper.addView(annotSettingsPill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
            addView(pillWrapper)

            [span_9](start_span)// Layer 2: Tool Sub-Menu[span_9](end_span)
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            [span_10](start_span)// Layer 3: Group Navigation[span_10](end_span)
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            refreshAnnotSubMenu("markup")
        }
    }

    private fun buildAnnotSettingsPill(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = dp(24).toFloat()
            }
            [span_11](start_span)// Implementation of Weight and Color pickers...[span_11](end_span)
        }
    }

    private fun buildAnnotGroupNav(): LinearLayout {
        val cBar = Color.parseColor("#1A1A1A")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBar)
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
            [span_12](start_span)// Loop for groups (Markup, Shapes, etc.) as defined in Patch[span_12](end_span)
        }
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        if (!::annotSubMenuRow.isInitialized) return
        annotSubMenuRow.removeAllViews()
        ANNOT_GROUPS[groupId]?.forEach { toolId ->
            [span_13](start_span)// Build tool cell logic...[span_13](end_span)
        }
    }

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        [span_14](start_span)searchCountLabel.text = if (searchResults.isEmpty()) "No results" else "${searchResultIdx + 1} of ${searchResults.size}"[span_14](end_span)
    }

    // --- Helper Logic ---

    private fun runSearch() {
        // Your existing search logic...
        [span_15](start_span)updateSearchCounter()[span_15](end_span)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    [span_16](start_span)private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()[span_16](end_span)

    // Activity Starters fixed (Replacing incorrect .start() calls)
    private fun openTools() {
        val intent = android.content.Intent(this, ToolsActivity::class.java)
        startActivity(intent) // Correct reference instead of .start()
    }
}
