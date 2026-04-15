// ============================================================
// ViewerActivity_UIUpdates.kt
// ProPDF Editor - UI/UX Improvements
// Based on: Find_opt_onlinenotepad_io.txt reference designs
//
// HOW TO USE:
//   1. Open ViewerActivity.kt in your GitHub repo
//   2. Find and REPLACE each labelled section below
//   3. Add the NEW STATE VARIABLES block near the top of the class
//   4. Commit and trigger Codemagic build
//
// All code is pure ASCII. No Compose. No XML. API 21+ safe.
// ============================================================

// ============================================================
// SECTION A: NEW STATE VARIABLES
// Add these fields to the ViewerActivity class body,
// alongside the existing state variables (pdfFile, totalPages, etc.)
// ============================================================

// Annotation group nav state
private var activeAnnotGroup = "markup"
private lateinit var annotSubMenuRow: LinearLayout
private lateinit var annotGroupNavContainer: LinearLayout
private lateinit var annotSettingsPill: LinearLayout
private lateinit var annotWeightValue: TextView
private lateinit var annotWeightBar: SeekBar
private val annotSwatchViews = mutableListOf<View>()

// Search bar state
private lateinit var searchInput: EditText
private lateinit var searchCountLabel: TextView

// Tool-to-group mapping
private val ANNOT_GROUPS = linkedMapOf(
    "markup"  to listOf("freehand","highlight","underline","strikeout","eraser"),
    "shapes"  to listOf("rect","circle","arrow"),
    "inserts" to listOf("text","stamp","image"),
    "manage"  to listOf("move_text","move_shape","undo","redo","save")
)
private val TOOL_LABEL = mapOf(
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
    "move_text"  to "Move T",
    "move_shape" to "Move S",
    "undo"       to "Undo",
    "redo"       to "Redo",
    "save"       to "Save"
)
private val TOOL_ICON = mapOf(
    "freehand"   to android.R.drawable.ic_menu_edit,
    "highlight"  to android.R.drawable.ic_menu_view,
    "underline"  to android.R.drawable.ic_menu_agenda,
    "strikeout"  to android.R.drawable.ic_menu_close_clear_cancel,
    "eraser"     to android.R.drawable.ic_menu_delete,
    "rect"       to android.R.drawable.ic_menu_crop,
    "circle"     to android.R.drawable.ic_menu_search,
    "arrow"      to android.R.drawable.ic_media_next,
    "text"       to android.R.drawable.ic_dialog_info,
    "stamp"      to android.R.drawable.ic_menu_send,
    "image"      to android.R.drawable.ic_menu_gallery,
    "move_text"  to android.R.drawable.ic_menu_directions,
    "move_shape" to android.R.drawable.ic_menu_compass,
    "undo"       to android.R.drawable.ic_menu_revert,
    "redo"       to android.R.drawable.ic_media_ff,
    "save"       to android.R.drawable.ic_menu_save
)

// ============================================================
// SECTION B: REPLACE buildSearchBar()
// Locate the existing buildSearchBar() function in
// ViewerActivity.kt and replace the entire function body.
// Design ref: Find_opt_onlinenotepad_io.txt lines 117-136
// ============================================================

private fun buildSearchBar(): LinearLayout {
    val C_BG      = Color.parseColor("#1A1A1A")
    val C_INPUT   = Color.parseColor("#0E0E0E")
    val C_TEXT    = Color.parseColor("#E5E2E1")
    val C_HINT    = Color.parseColor("#8B90A0")
    val C_NAV     = Color.parseColor("#2D2D2D")
    val C_COUNT   = Color.parseColor("#64B5F6")
    val C_GO_FROM = Color.parseColor("#ADC6FF")
    val C_GO_TO   = Color.parseColor("#4B8EFF")
    val C_GO_TXT  = Color.parseColor("#002E69")
    val C_ERR     = Color.parseColor("#FF4444")

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C_BG)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        visibility = View.GONE   // toggled by showSearch() / hideSearch()
    }

    // -- Row 1: Input field + clear button + GO button --
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
        setHintTextColor(C_HINT)
        setTextColor(C_TEXT)
        textSize = 13f
        setSingleLine(true)
        setPadding(dp(14), 0, dp(44), 0)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        background = GradientDrawable().apply {
            setColor(C_INPUT)
            cornerRadius = dp(12).toFloat()
        }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
                runSearch()
                true
            } else false
        }
    }
    inputWrap.addView(searchInput)

    // X clear button inside the input field
    val clearBtn = android.widget.ImageButton(this).apply {
        layoutParams = FrameLayout.LayoutParams(dp(38), dp(38),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) }
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setColorFilter(C_ERR)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Clear search"
        setOnClickListener {
            searchInput.setText("")
            searchResults = emptyList()
            searchResultIdx = 0
            updateSearchCounter()
        }
    }
    inputWrap.addView(clearBtn)
    inputRow.addView(inputWrap)

    // GO button: gradient background, bold uppercase
    val goBtn = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(68), dp(46))
        text = "GO"
        setTextColor(C_GO_TXT)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
        background = GradientDrawable().apply {
            colors = intArrayOf(C_GO_FROM, C_GO_TO)
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(windowToken, 0)
            runSearch()
        }
    }
    inputRow.addView(goBtn)
    container.addView(inputRow)

    // Spacer between rows
    container.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
    })

    // -- Row 2: PREV | count "1 of N" | NEXT --
    val navRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val prevBtn = buildSearchNavButton("< PREV") {
        if (searchResults.isNotEmpty()) {
            searchResultIdx =
                (searchResultIdx - 1 + searchResults.size) % searchResults.size
            goToPage(searchResults[searchResultIdx])
            updateSearchCounter()
        }
    }
    navRow.addView(prevBtn)

    searchCountLabel = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(C_COUNT)
        typeface = Typeface.DEFAULT
        text = ""
    }
    navRow.addView(searchCountLabel)

    val nextBtn = buildSearchNavButton("NEXT >") {
        if (searchResults.isNotEmpty()) {
            searchResultIdx = (searchResultIdx + 1) % searchResults.size
            goToPage(searchResults[searchResultIdx])
            updateSearchCounter()
        }
    }
    navRow.addView(nextBtn)
    container.addView(navRow)

    return container
}

// Helper: PREV / NEXT button inside search bar
private fun buildSearchNavButton(label: String, action: () -> Unit): TextView {
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

// Call this after runSearch() to refresh the "X of N" label
private fun updateSearchCounter() {
    if (!::searchCountLabel.isInitialized) return
    searchCountLabel.text = when {
        searchResults.isEmpty() -> "No results"
        else -> "${searchResultIdx + 1} of ${searchResults.size}"
    }
}

// ============================================================
// SECTION C: REPLACE buildAnnotationToolbar()
// Locate the existing annotation toolbar builder
// (often called buildAnnotToolbar / buildAnnotBar / the
// section that creates the horizontally-scrollable tool row)
// and replace the entire function with these three functions.
//
// Design ref: Find_opt_onlinenotepad_io.txt lines 258-485
//             and lines 487-5414 (Annotations_grouping.html)
// ============================================================

// Top-level builder - returns the full 3-layer annotation panel
private fun buildAnnotationToolbar(): LinearLayout {
    val C_OUTER = Color.parseColor("#131313")

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C_OUTER)
        // Shadow above toolbar
        elevation = dp(8).toFloat()

        // Layer 1: floating settings pill (weight + color swatches)
        val pillWrapper = FrameLayout(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        }
        annotSettingsPill = buildAnnotSettingsPill()
        pillWrapper.addView(annotSettingsPill, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(pillWrapper)

        // Layer 2: contextual tool sub-menu (horizontal scroll)
        val scroll = HorizontalScrollView(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(C_OUTER)
        }
        annotSubMenuRow = LinearLayout(this@ViewerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scroll.addView(annotSubMenuRow)
        addView(scroll)

        // Layer 3: 4-group bottom navigation
        annotGroupNavContainer = buildAnnotGroupNav()
        addView(annotGroupNavContainer)

        // Init with markup group selected
        refreshAnnotSubMenu("markup")
    }
}

// Layer 1: settings pill - weight slider + 4 color swatches
private fun buildAnnotSettingsPill(): LinearLayout {
    val C_PILL  = Color.parseColor("#1A1A1A")
    val C_DIM   = Color.parseColor("#2D2D2D")
    val C_TXT   = Color.parseColor("#8B90A0")
    val C_BLUE  = Color.parseColor("#ADC6FF")

    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply {
            setColor(C_PILL)
            cornerRadius = dp(24).toFloat()
        }
        elevation = dp(4).toFloat()

        // "WEIGHT" label
        addView(TextView(this@ViewerActivity).apply {
            text = "WEIGHT"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TXT)
            letterSpacing = 0.1f
        })

        // Current weight numeric value
        annotWeightValue = TextView(this@ViewerActivity).apply {
            text = strokeWidth.toInt().toString()
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_BLUE)
            setPadding(dp(6), 0, dp(6), 0)
        }
        addView(annotWeightValue)

        // SeekBar - range 2..50, default = strokeWidth
        annotWeightBar = SeekBar(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(22))
            max = 48  // maps to 2..50
            progress = (strokeWidth.toInt() - 2).coerceIn(0, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val w = (p + 2).toFloat()
                    strokeWidth = w
                    annotWeightValue.text = (p + 2).toString()
                    canvases[currentPage]?.setStrokeWidth(w)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        addView(annotWeightBar)

        // Divider
        addView(View(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
            setBackgroundColor(C_DIM)
        })

        // "COLOR" label
        addView(TextView(this@ViewerActivity).apply {
            text = "COLOR"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TXT)
            letterSpacing = 0.1f
            setPadding(0, 0, dp(8), 0)
        })

        // 4 color swatches
        annotSwatchViews.clear()
        val swatchHex = listOf("#007AFF", "#EF6719", "#FFFFFF", "#000000")
        swatchHex.forEach { hex ->
            val color = Color.parseColor(hex)
            val swatch = View(this@ViewerActivity).apply {
                val sz = dp(17)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = dp(7)
                }
                tag = color
                updateSwatchDrawable(this, color, color == activeColor)
                setOnClickListener {
                    activeColor = color
                    canvases[currentPage]?.setTool(activeTool, activeColor)
                    annotSwatchViews.forEach { sv ->
                        updateSwatchDrawable(sv, sv.tag as Int, (sv.tag as Int) == activeColor)
                    }
                }
            }
            annotSwatchViews.add(swatch)
            addView(swatch)
        }
    }
}

// Helper: apply or update circular swatch drawable with optional ring
private fun updateSwatchDrawable(view: View, color: Int, isActive: Boolean) {
    view.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (isActive) {
            setStroke(dp(2), Color.parseColor("#ADC6FF"))
        }
    }
    view.alpha = if (isActive) 1f else 0.75f
}

// Layer 2: rebuild the tool cells for the given group
private fun refreshAnnotSubMenu(groupId: String) {
    if (!::annotSubMenuRow.isInitialized) return
    annotSubMenuRow.removeAllViews()
    val tools = ANNOT_GROUPS[groupId] ?: return

    tools.forEach { toolId ->
        annotSubMenuRow.addView(buildAnnotToolCell(toolId))
    }
}

// Single 64x72dp tool card cell
private fun buildAnnotToolCell(toolId: String): LinearLayout {
    val isActive = (toolId == activeTool)
    val C_CARD_INACTIVE = Color.parseColor("#2D2D2D")
    val C_ICON_ACTIVE   = Color.parseColor("#002E69")
    val C_ICON_INACTIVE = Color.parseColor("#ADC6FF")
    val C_LBL_ACTIVE    = Color.parseColor("#002E69")
    val C_LBL_INACTIVE  = Color.WHITE

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(72)).apply {
            marginEnd = dp(6)
        }
        background = if (isActive) {
            GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#ADC6FF"),
                    Color.parseColor("#4B8EFF")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(12).toFloat()
            }
        } else {
            GradientDrawable().apply {
                setColor(C_CARD_INACTIVE)
                cornerRadius = dp(12).toFloat()
            }
        }
        elevation = if (isActive) dp(4).toFloat() else dp(2).toFloat()

        // Icon
        val iconRes = TOOL_ICON[toolId] ?: android.R.drawable.ic_menu_edit
        val icon = ImageView(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setImageResource(iconRes)
            setColorFilter(if (isActive) C_ICON_ACTIVE else C_ICON_INACTIVE)
            alpha = if (isActive) 1f else 0.65f
        }
        addView(icon)

        // Label
        val label = TextView(this@ViewerActivity).apply {
            text = TOOL_LABEL[toolId] ?: toolId
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isActive) C_LBL_ACTIVE else C_LBL_INACTIVE)
            alpha = if (isActive) 1f else 0.6f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            letterSpacing = 0.05f
        }
        addView(label)

        setOnClickListener {
            handleAnnotToolTap(toolId)
        }
    }
}

// Dispatch tool tap actions
private fun handleAnnotToolTap(toolId: String) {
    when (toolId) {
        "undo"  -> { canvases[currentPage]?.undo(); return }
        "redo"  -> { canvases[currentPage]?.redo(); return }
        "save"  -> { lifecycleScope.launch { saveAnnotations() }; return }
        "image" -> { pickImageForPage(); return }
        "stamp" -> { showStampPicker(); return }
        else -> {
            activeTool = toolId
            canvases[currentPage]?.setTool(toolId, activeColor)
            // Determine which group owns this tool and switch if needed
            val ownerGroup = ANNOT_GROUPS.entries.firstOrNull { toolId in it.value }?.key
                ?: activeAnnotGroup
            if (ownerGroup != activeAnnotGroup) {
                activeAnnotGroup = ownerGroup
                refreshAnnotGroupNav()
            }
            refreshAnnotSubMenu(activeAnnotGroup)
        }
    }
}

// Layer 3: 4-group nav bar at the bottom
private fun buildAnnotGroupNav(): LinearLayout {
    val C_BAR    = Color.parseColor("#1A1A1A")
    val C_ACTIVE = Color.parseColor("#ADC6FF")
    val C_INACT  = Color.parseColor("#8B90A0")

    data class GroupDef(val id: String, val iconRes: Int, val label: String)
    val groups = listOf(
        GroupDef("markup",  android.R.drawable.ic_menu_edit,       "MARKUP"),
        GroupDef("shapes",  android.R.drawable.ic_menu_crop,       "SHAPES"),
        GroupDef("inserts", android.R.drawable.ic_input_add,       "INSERTS"),
        GroupDef("manage",  android.R.drawable.ic_menu_manage,     "MANAGE")
    )

    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(C_BAR)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56))

        // Top separator line
        val sep = View(this@ViewerActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#2D2D2D"))
        }
        // Note: can't add a view here directly in apply{} for LinearLayout
        // so we add the separator outside after building, or use a wrapper.
        // Instead, draw the divider via background or just skip it.

        groups.forEach { g ->
            val isActive = g.id == activeAnnotGroup
            val tab = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                background = if (isActive) {
                    GradientDrawable().apply {
                        colors = intArrayOf(
                            Color.parseColor("#1A1A1A"),
                            Color.parseColor("#2D2D2D")
                        )
                        gradientType = GradientDrawable.LINEAR_GRADIENT
                        orientation = GradientDrawable.Orientation.TL_BR
                        cornerRadius = dp(8).toFloat()
                    }
                } else null
                setOnClickListener {
                    if (activeAnnotGroup != g.id) {
                        activeAnnotGroup = g.id
                        refreshAnnotGroupNav()
                        refreshAnnotSubMenu(g.id)
                    }
                }
            }

            val icon = ImageView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                setImageResource(g.iconRes)
                setColorFilter(if (isActive) C_ACTIVE else C_INACT)
                alpha = if (isActive) 1f else 0.65f
            }
            tab.addView(icon)

            val lbl = TextView(this@ViewerActivity).apply {
                text = g.label
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isActive) C_ACTIVE else C_INACT)
                alpha = if (isActive) 1f else 0.65f
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                letterSpacing = 0.05f
            }
            tab.addView(lbl)
            addView(tab)
        }
    }
}

// Refresh the group nav in-place by rebuilding it
private fun refreshAnnotGroupNav() {
    if (!::annotGroupNavContainer.isInitialized) return
    val parent = annotGroupNavContainer.parent as? LinearLayout ?: return
    val idx = (0 until parent.childCount).indexOfFirst {
        parent.getChildAt(it) === annotGroupNavContainer
    }
    if (idx < 0) return
    parent.removeViewAt(idx)
    annotGroupNavContainer = buildAnnotGroupNav()
    parent.addView(annotGroupNavContainer, idx)
}

// ============================================================
// SECTION D: WIRING NOTES
// After replacing the functions above, also:
//
// 1. In onCreate() / buildViewerUI(), replace the old toolbar
//    builder call with:
//        val annotBar = buildAnnotationToolbar()
//        rootLayout.addView(annotBar, ...)   // at bottom
//
// 2. In showSearch() / toggleSearch(), set visibility:
//        searchBar.visibility = View.VISIBLE
//        searchInput.requestFocus()
//
// 3. In runSearch(), after populating searchResults, call:
//        updateSearchCounter()
//
// 4. After any tool selection from outside the toolbar (e.g.
//    menu items), call:
//        refreshAnnotSubMenu(activeAnnotGroup)
//
// 5. The existing weight SeekBar and color picker dialog can
//    be REMOVED -- the settings pill replaces both.
// ============================================================

// ============================================================
// SECTION E: COLOR SYSTEM CONSTANTS (optional cleanup)
// Replace scattered parseColor calls with these constants
// at the top of ViewerActivity companion object / file-level.
// ============================================================

// object ViewerColors {
//     const val BG          = "#121212"
//     const val NAV_BAR     = "#1E1E2E"
//     const val SURFACE     = "#1A1A1A"
//     const val CARD        = "#2D2D2D"
//     const val CARD_HIGH   = "#353534"
//     const val PRIMARY     = "#ADC6FF"
//     const val PRIMARY_CON = "#4B8EFF"
//     const val ON_PRIMARY  = "#002E69"
//     const val TEXT_MAIN   = "#E5E2E1"
//     const val TEXT_DIM    = "#8B90A0"
//     const val ACCENT_BLU  = "#64B5F6"
//     const val ACCENT_ORG  = "#EF6719"
//     const val DANGER      = "#FF4444"
// }
