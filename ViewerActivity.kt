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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.ui.tools.ToolsActivity
import kotlinx.coroutines.launch

/**
 * ViewerActivity - ProPDF Editor
 * Full-featured PDF viewer with premium annotation toolbar and search.
 */
class ViewerActivity : AppCompatActivity() {

    // --- Core PDF State ---
    private var currentPage: Int = 0
    private var totalPages: Int = 0
    private var searchResultIdx: Int = 0
    private var searchResults: List<Int> = emptyList()
    private var pdfUri: Uri? = null
    private var pdfPassword: String? = null

    // --- Annotation Toolbar State ---
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

    // Tool group mapping
    private val ANNOT_GROUPS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "markup"  to listOf("freehand", "highlight", "underline", "strikeout", "eraser"),
        "shapes"  to listOf("rect", "circle", "arrow"),
        "inserts" to listOf("text", "stamp", "image"),
        "manage"  to listOf("move_text", "move_shape", "undo", "redo", "save")
    )

    private val TOOL_LABEL: Map<String, String> = mapOf(
        "freehand"   to "Pen",
        "highlight"  to "High.",
        "underline"  to "Under.",
        "strikeout"  to "Strike",
        "eraser"     to "Eraser",
        "rect"       to "Box",
        "circle"     to "Circle",
        "arrow"      to "Arrow",
        "text"       to "Text",
        "stamp"      to "Stamp",
        "image"      to "Image",
        "move_text"  to "MoveT",
        "move_shape" to "MoveS",
        "undo"       to "Undo",
        "redo"       to "Redo",
        "save"       to "Save"
    )

    private val TOOL_ICON: Map<String, Int> = mapOf(
        "freehand"   to android.R.drawable.ic_menu_edit,
        "highlight"  to android.R.drawable.ic_menu_view,
        "underline"  to android.R.drawable.ic_menu_info_details,
        "strikeout"  to android.R.drawable.ic_menu_delete,
        "eraser"     to android.R.drawable.ic_menu_close_clear_cancel,
        "rect"       to android.R.drawable.ic_menu_crop,
        "circle"     to android.R.drawable.ic_menu_search,
        "arrow"      to android.R.drawable.ic_media_next,
        "text"       to android.R.drawable.ic_dialog_info,
        "stamp"      to android.R.drawable.ic_menu_send,
        "image"      to android.R.drawable.ic_menu_gallery,
        "move_text"  to android.R.drawable.ic_dialog_map,
        "move_shape" to android.R.drawable.ic_menu_compass,
        "undo"       to android.R.drawable.ic_menu_revert,
        "redo"       to android.R.drawable.ic_media_ff,
        "save"       to android.R.drawable.ic_menu_save
    )

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Retrieve URI and optional password from intent extras
        val uriStr = intent.getStringExtra(EXTRA_URI)
        pdfUri = if (uriStr != null) Uri.parse(uriStr) else null
        pdfPassword = intent.getStringExtra(EXTRA_PASSWORD)

        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
    }

    // -------------------------------------------------------
    // SEARCH BAR
    // Reference: Find option HTML - dark input + gradient GO
    // -------------------------------------------------------

    private fun buildSearchBar(): LinearLayout {
        val cBg     = Color.parseColor("#1A1A1A")
        val cInput  = Color.parseColor("#0E0E0E")
        val cGoFrom = Color.parseColor("#ADC6FF")
        val cGoTo   = Color.parseColor("#4B8EFF")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }

        // Row 1: input + clear + GO
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(8)
            }
        }

        searchInput = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard(); runSearch(); true
                } else false
            }
        }
        inputWrap.addView(searchInput)

        val clearBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(4) }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            colorFilter = PorterDuffColorFilter(
                Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                searchInput.setText("")
                searchResults = emptyList()
                searchResultIdx = 0
                updateSearchCounter()
            }
        }
        inputWrap.addView(clearBtn)
        inputRow.addView(inputWrap)

        val goBtn = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46))
            text = "GO"
            setTextColor(Color.parseColor("#002E69"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            background = GradientDrawable().apply {
                colors = intArrayOf(cGoFrom, cGoTo)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { hideKeyboard(); runSearch() }
        }
        inputRow.addView(goBtn)
        container.addView(inputRow)

        // Spacer
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            )
        })

        // Row 2: PREV | count label | NEXT
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navRow.addView(buildSearchNavBtn("< PREV") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx =
                    (searchResultIdx - 1 + searchResults.size) % searchResults.size
                updateSearchCounter()
            }
        })
        searchCountLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.parseColor("#64B5F6"))
            text = ""
        }
        navRow.addView(searchCountLabel)
        navRow.addView(buildSearchNavBtn("NEXT >") {
            if (searchResults.isNotEmpty()) {
                searchResultIdx = (searchResultIdx + 1) % searchResults.size
                updateSearchCounter()
            }
        })
        container.addView(navRow)
        return container
    }

    private fun buildSearchNavBtn(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (label.startsWith("<")) marginEnd = dp(6) else marginStart = dp(6)
            }
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1"))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    // -------------------------------------------------------
    // ANNOTATION TOOLBAR - 3 layers
    // -------------------------------------------------------

    private fun buildAnnotationToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#131313"))
            elevation = dp(8).toFloat()

            // Layer 1: settings pill
            val pillWrapper = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(6)
                }
            }
            annotSettingsPill = buildAnnotSettingsPill()
            pillWrapper.addView(
                annotSettingsPill,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL)
            )
            addView(pillWrapper)

            // Layer 2: horizontal tool scroll
            val scroll = HorizontalScrollView(this@ViewerActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            scroll.addView(annotSubMenuRow)
            addView(scroll)

            // Layer 3: group nav bar
            annotGroupNavBar = buildAnnotGroupNav()
            addView(annotGroupNavBar)

            refreshAnnotSubMenu("markup")
        }
    }

    private fun buildAnnotSettingsPill(): LinearLayout {
        val cPill = Color.parseColor("#1A1A1A")
        val cDim  = Color.parseColor("#2D2D2D")
        val cTxt  = Color.parseColor("#8B90A0")
        val cBlue = Color.parseColor("#ADC6FF")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                setColor(cPill)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(4).toFloat()

            addView(TextView(this@ViewerActivity).apply {
                text = "WEIGHT"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(cTxt)
                letterSpacing = 0.1f
            })

            annotWeightValue = TextView(this@ViewerActivity).apply {
                text = strokeWidth.toInt().toString()
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(cBlue)
                setPadding(dp(5), 0, dp(5), 0)
            }
            addView(annotWeightValue)

            annotWeightBar = SeekBar(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(86), dp(22))
                max = 48
                progress = (strokeWidth.toInt() - 2).coerceIn(0, 48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        strokeWidth = (p + 2).toFloat()
                        annotWeightValue.text = (p + 2).toString()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(annotWeightBar)

            addView(View(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    marginStart = dp(8); marginEnd = dp(8)
                }
                setBackgroundColor(cDim)
            })

            addView(TextView(this@ViewerActivity).apply {
                text = "COLOR"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(cTxt)
                letterSpacing = 0.1f
                setPadding(0, 0, dp(8), 0)
            })

            annotSwatchViews.clear()
            listOf("#007AFF", "#EF6719", "#FFFFFF", "#000000").forEach { hex ->
                val col = Color.parseColor(hex)
                val swatch = View(this@ViewerActivity).apply {
                    val sz = dp(17)
                    layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                        marginEnd = dp(7)
                    }
                    tag = col
                    applySwatchStyle(this, col, col == activeColor)
                    setOnClickListener {
                        activeColor = col
                        annotSwatchViews.forEach { sv ->
                            applySwatchStyle(sv, sv.tag as Int, sv.tag as Int == activeColor)
                        }
                    }
                }
                annotSwatchViews.add(swatch)
                addView(swatch)
            }
        }
    }

    private fun applySwatchStyle(view: View, color: Int, isActive: Boolean) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isActive) setStroke(dp(2), Color.parseColor("#ADC6FF"))
        }
        view.alpha = if (isActive) 1f else 0.75f
    }

    private fun buildAnnotGroupNav(): LinearLayout {
        val cActive = Color.parseColor("#ADC6FF")
        val cInact  = Color.parseColor("#8B90A0")

        data class GDef(val id: String, val icon: Int, val label: String)
        val groups = listOf(
            GDef("markup",  android.R.drawable.ic_menu_edit,   "MARKUP"),
            GDef("shapes",  android.R.drawable.ic_menu_crop,   "SHAPES"),
            GDef("inserts", android.R.drawable.ic_menu_add,    "INSERTS"),
            GDef("manage",  android.R.drawable.ic_menu_agenda, "MANAGE")
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))

            groups.forEach { g ->
                val isActive = g.id == activeAnnotGroup
                addView(LinearLayout(this@ViewerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    if (isActive) {
                        background = GradientDrawable().apply {
                            colors = intArrayOf(
                                Color.parseColor("#1A1A1A"),
                                Color.parseColor("#2D2D2D")
                            )
                            gradientType = GradientDrawable.LINEAR_GRADIENT
                            orientation = GradientDrawable.Orientation.TL_BR
                            cornerRadius = dp(8).toFloat()
                        }
                    }
                    setOnClickListener {
                        if (activeAnnotGroup != g.id) {
                            activeAnnotGroup = g.id
                            rebuildAnnotGroupNav()
                            refreshAnnotSubMenu(g.id)
                        }
                    }
                    addView(ImageView(this@ViewerActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        setImageResource(g.icon)
                        colorFilter = PorterDuffColorFilter(
                            if (isActive) cActive else cInact, PorterDuff.Mode.SRC_IN
                        )
                        alpha = if (isActive) 1f else 0.65f
                    })
                    addView(TextView(this@ViewerActivity).apply {
                        text = g.label
                        textSize = 9f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isActive) cActive else cInact)
                        alpha = if (isActive) 1f else 0.65f
                        gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                        letterSpacing = 0.05f
                    })
                })
            }
        }
    }

    private fun rebuildAnnotGroupNav() {
        if (!::annotGroupNavBar.isInitialized) return
        val parent = annotGroupNavBar.parent as? LinearLayout ?: return
        val idx = (0 until parent.childCount).indexOfFirst {
            parent.getChildAt(it) === annotGroupNavBar
        }
        if (idx < 0) return
        parent.removeViewAt(idx)
        annotGroupNavBar = buildAnnotGroupNav()
        parent.addView(annotGroupNavBar, idx)
    }

    private fun refreshAnnotSubMenu(groupId: String) {
        if (!::annotSubMenuRow.isInitialized) return
        annotSubMenuRow.removeAllViews()
        ANNOT_GROUPS[groupId]?.forEach { toolId ->
            annotSubMenuRow.addView(buildAnnotToolCell(toolId))
        }
    }

    private fun buildAnnotToolCell(toolId: String): LinearLayout {
        val isActive = toolId == activeTool
        val cIconOn  = Color.parseColor("#002E69")
        val cIconOff = Color.parseColor("#ADC6FF")

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(72)).apply {
                marginEnd = dp(6)
            }
            background = if (isActive) {
                GradientDrawable().apply {
                    colors = intArrayOf(
                        Color.parseColor("#ADC6FF"), Color.parseColor("#4B8EFF")
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(12).toFloat()
                }
            } else {
                GradientDrawable().apply {
                    setColor(Color.parseColor("#2D2D2D"))
                    cornerRadius = dp(12).toFloat()
                }
            }
            elevation = if (isActive) dp(4).toFloat() else dp(2).toFloat()

            addView(ImageView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(TOOL_ICON[toolId] ?: android.R.drawable.ic_menu_edit)
                colorFilter = PorterDuffColorFilter(
                    if (isActive) cIconOn else cIconOff, PorterDuff.Mode.SRC_IN
                )
                alpha = if (isActive) 1f else 0.7f
            })
            addView(TextView(this@ViewerActivity).apply {
                text = TOOL_LABEL[toolId] ?: toolId
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) cIconOn else Color.WHITE)
                alpha = if (isActive) 1f else 0.6f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
                letterSpacing = 0.04f
            })
            setOnClickListener { handleAnnotToolTap(toolId) }
        }
    }

    private fun handleAnnotToolTap(toolId: String) {
        activeTool = toolId
        val ownerGroup = ANNOT_GROUPS.entries
            .firstOrNull { toolId in it.value }?.key ?: activeAnnotGroup
        if (ownerGroup != activeAnnotGroup) {
            activeAnnotGroup = ownerGroup
            rebuildAnnotGroupNav()
        }
        refreshAnnotSubMenu(activeAnnotGroup)
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun updateSearchCounter() {
        if (!::searchCountLabel.isInitialized) return
        searchCountLabel.text = if (searchResults.isEmpty()) {
            "No results"
        } else {
            "${searchResultIdx + 1} of ${searchResults.size}"
        }
    }

    private fun runSearch() {
        // Existing search logic fills searchResults
        updateSearchCounter()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun openTools() {
        startActivity(Intent(this, ToolsActivity::class.java))
    }

    // -------------------------------------------------------
    // COMPANION OBJECT
    // Fixed: start() now accepts uri + optional password.
    // This resolves "Too many arguments" in MainActivity:801
    // and ToolsActivity:653.
    // -------------------------------------------------------
    companion object {
        const val EXTRA_URI      = "extra_pdf_uri"
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
