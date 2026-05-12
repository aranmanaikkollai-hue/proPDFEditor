package com.propdf.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.propdf.editor.R
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.utils.FileHelper
import com.propdf.viewer.presentation.ViewerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Refactored ViewerActivity using MVVM + StateFlow.
 * All PDF rendering, search, and page management delegated to ViewerViewModel.
 * UI reacts to StateFlow emissions.
 */
@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_PASSWORD = "extra_pdf_password"
        const val EXTRA_DISPLAY_NAME = "extra_pdf_display_name"

        fun start(context: Context, uri: Uri, password: String? = null, displayName: String? = null) {
            val intent = Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                password?.let { putExtra(EXTRA_PASSWORD, it) }
                displayName?.let { putExtra(EXTRA_DISPLAY_NAME, it) }
            }
            context.startActivity(intent)
        }
    }

    @Inject lateinit var viewModel: ViewerViewModel

    private lateinit var rootFrame: FrameLayout
    private lateinit var pageContainer: LinearLayout
    private lateinit var pageCounter: TextView
    private lateinit var searchBox: EditText
    private lateinit var searchCountLabel: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var searchBar: LinearLayout

    private var isDark = true
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private val c_pri = Color.parseColor("#448AFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr != null) {
            val file = FileHelper.uriToFile(this, Uri.parse(uriStr))
            if (file != null) {
                viewModel.loadPdf(file)
                viewModel.setScreenWidth(resources.displayMetrics.widthPixels - dp(16))
            } else {
                toast("Cannot read PDF")
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: com.propdf.viewer.presentation.ViewerUiState) {
        // Update page counter
        pageCounter.text = "Page ${state.currentPage + 1} of ${state.totalPages}"

        // Render pages
        if (state.pages.isNotEmpty()) {
            pageContainer.removeAllViews()
            state.pages.entries.sortedBy { it.key }.forEach { (index, bitmap) ->
                addPageView(bitmap, index)
            }
        }

        // Update search counter
        if (state.searchResults.isNotEmpty()) {
            val idx = state.searchResults.indexOf(state.currentPage).coerceAtLeast(0)
            searchCountLabel.text = "${idx + 1} / ${state.searchResults.size}"
        } else {
            searchCountLabel.text = if (state.searchQuery.isNotEmpty()) "No matches" else "Tap Find"
        }

        // Handle errors
        state.error?.let {
            toast(it)
            viewModel.clearError()
        }
    }

    private fun buildUI() {
        applySystemBarColors()
        rootFrame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        column.addView(buildTopBar())
        searchBar = buildSearchBar()
        column.addView(searchBar)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ -> updatePageFromScroll(scrollY) }
        }
        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll.addView(pageContainer)
        column.addView(scroll)
        column.addView(buildBottomBar())
        rootFrame.addView(column)
        setContentView(rootFrame)
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(40), dp(12), dp(10))
            addView(TextView(this@ViewerActivity).apply {
                text = "Back"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
            addView(View(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(TextView(this@ViewerActivity).apply {
                text = "Search"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { toggleSearchMode() }
            })
            addView(TextView(this@ViewerActivity).apply {
                text = "More"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showPdfOpsMenu() }
            })
        }
    }

    private fun buildSearchBar(): LinearLayout {
        val cBg = Color.parseColor("#1E1E2E")
        val cInput = Color.parseColor("#0E0E1A")
        val cBlue = Color.parseColor("#ADC6FF")
        val cDark = Color.parseColor("#4B8EFF")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE

            val row1 = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val wrap = FrameLayout(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            }
            searchBox = EditText(this@ViewerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                hint = "Search in PDF..."
                setHintTextColor(Color.parseColor("#666888"))
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(cInput)
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(12), 0, dp(12), 0)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setSingleLine(true)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        hideKeyboard()
                        viewModel.search(text.toString().trim())
                        true
                    } else false
                }
            }
            wrap.addView(searchBox)
            row1.addView(wrap)

            row1.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(44))
                text = "Find"
                setTextColor(Color.parseColor("#001A4D"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    colors = intArrayOf(cBlue, cDark)
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener { hideKeyboard(); viewModel.search(searchBox.text.toString().trim()) }
            })

            val closeBtn = ImageButton(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginStart = dp(4) }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(PorterDuffColorFilter(Color.parseColor("#FF4444"), PorterDuff.Mode.SRC_IN))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { hideSearchBar() }
            }
            row1.addView(closeBtn)
            addView(row1)
            addView(View(this@ViewerActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(6)) })

            val row2 = LinearLayout(this@ViewerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row2.addView(buildSearchNavBtn("< Prev") {
                val results = viewModel.uiState.value.searchResults
                if (results.isNotEmpty()) {
                    val current = viewModel.uiState.value.currentPage
                    val idx = (results.indexOf(current) - 1).coerceAtLeast(0)
                    viewModel.goToPage(results[idx])
                }
            })
            searchCountLabel = TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(cBlue)
                text = "Tap Find"
            }
            row2.addView(searchCountLabel)
            row2.addView(buildSearchNavBtn("Next >") {
                val results = viewModel.uiState.value.searchResults
                if (results.isNotEmpty()) {
                    val current = viewModel.uiState.value.currentPage
                    val idx = (results.indexOf(current) + 1).coerceAtMost(results.size - 1)
                    viewModel.goToPage(results[idx])
                }
            })
            row2.addView(TextView(this@ViewerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(8) }
                text = "Go to Pg"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(cBlue)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2D2D44"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { showGoToPageDialog() }
            })
            addView(row2)
        }
    }

    private fun buildSearchNavBtn(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
                if (label.startsWith("<")) marginEnd = dp(6) else marginStart = dp(6)
            }
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E5E2E1"))
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun buildBottomBar(): LinearLayout {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE)
            setPadding(0, dp(10), 0, dp(24))
        }
        pageCounter = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor(txt2()))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        bottomBar.addView(pageCounter)
        val navBtns = listOf(
            "< Prev" to { viewModel.prevPage() },
            "Next >" to { viewModel.nextPage() },
            "Zoom+" to { viewModel.setZoom(viewModel.uiState.value.zoom * 1.2f) },
            "Zoom-" to { viewModel.setZoom(viewModel.uiState.value.zoom * 0.8f) },
            "Fit" to { viewModel.setZoom(1.0f) },
            "Annot" to { showAnnotationMenu() },
            "OCR" to { showOcrMenu() },
            "Theme" to { toggleTheme() }
        )
        navBtns.forEach { (label, action) ->
            bottomBar.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(c_pri)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { action() }
            })
        }
        return bottomBar
    }

    private fun addPageView(bmp: Bitmap, pageIndex: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply {
                setColor(cardBg())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#2A2A2A"))
            }
        }
        val zoomView = ZoomableImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            setImageBitmap(bmp)
            tag = pageIndex
        }
        frame.addView(zoomView)
        pageContainer.addView(frame)
    }

    private fun updatePageFromScroll(scrollY: Int) {
        if (pageContainer.childCount == 0) return
        var accumulated = 0
        for (i in 0 until pageContainer.childCount) {
            val child = pageContainer.getChildAt(i)
            if (scrollY < accumulated + child.height / 2) {
                // viewModel.setCurrentPage(i) // Optional: sync scroll to VM
                break
            }
            accumulated += child.height
        }
    }

    private fun toggleSearchMode() {
        val isVisible = searchBar.visibility == View.VISIBLE
        searchBar.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (isVisible) {
            searchBox.setText("")
            viewModel.clearSearch()
        }
    }

    private fun hideSearchBar() {
        searchBar.visibility = View.GONE
        searchBox.setText("")
        viewModel.clearSearch()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun showAnnotationMenu() {
        val tools = arrayOf("Freehand", "Highlighter", "Rectangle", "Circle", "Arrow", "Underline", "Strikeout", "Text", "Stamp", "Clear All")
        AlertDialog.Builder(this).setTitle("Annotation Tool").setItems(tools) { _, which ->
            if (which == 9) { toast("Annotations cleared"); return@setItems }
            toast("${tools[which]} selected — annotation layer coming in v3.1")
        }.show()
    }

    private fun showOcrMenu() {
        val items = arrayOf("Extract Text (this page)", "Extract Text (all pages)", "Copy to Clipboard", "ML Kit OCR")
        AlertDialog.Builder(this).setTitle("OCR Options").setItems(items) { _, which ->
            when (which) {
                0, 1, 2, 3 -> toast("OCR delegated to OcrManager — coming in v3.1")
            }
        }.show()
    }

    private fun showPdfOpsMenu() {
        AlertDialog.Builder(this).setTitle("PDF Operations").setItems(arrayOf(
            "Bookmark This Page", "All Bookmarks", "Go to Page", "Add Watermark", "Rotate Page",
            "Delete Page", "Compress PDF", "Page to Image", "Image to PDF", "Merge / Split", "Share PDF", "Open Tools"
        )) { _, which ->
            when (which) {
                2 -> showGoToPageDialog()
                10 -> sharePdf()
                11 -> startActivity(Intent(this, ToolsActivity::class.java))
                else -> toast("Operation coming in v3.1")
            }
        }.show()
    }

    private fun showGoToPageDialog() {
        val total = viewModel.uiState.value.totalPages
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Page 1-$total"
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Go to Page").setView(et).setPositiveButton("Go") { _, _ ->
            val pg = et.text.toString().toIntOrNull()?.let { it - 1 }?.coerceIn(0, total - 1)
            if (pg != null) viewModel.goToPage(pg) else toast("Invalid page number")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun sharePdf() {
        val uriStr = intent.getStringExtra(EXTRA_URI) ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
        }
        startActivity(Intent.createChooser(share, "Share PDF"))
    }

    private fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        recreate()
    }

    private fun applySystemBarColors() {
        window.statusBarColor = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
        window.navigationBarColor = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    inner class ZoomableImageView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
        private var scaleDetector: android.view.ScaleGestureDetector? = null
        private var scaleFactor = 1.0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var posX = 0f
        private var posY = 0f
        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        init {
            scaleType = ScaleType.MATRIX
            scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                    applyMatrix()
                    return true
                }
            })
        }

        fun setZoom(zoom: Float) {
            scaleFactor = zoom.coerceIn(0.5f, 5.0f)
            applyMatrix()
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            applyMatrix()
        }

        private fun applyMatrix() {
            val matrix = Matrix()
            val drawable = drawable ?: return
            val dW = drawable.intrinsicWidth.toFloat()
            val dH = drawable.intrinsicHeight.toFloat()
            val vW = width.toFloat()
            val vH = height.toFloat()
            val fitScale = kotlin.math.min(vW / dW, vH / dH)
            val finalScale = fitScale * scaleFactor
            matrix.setScale(finalScale, finalScale)
            val dx = (vW - dW * finalScale) / 2f + posX
            val dy = (vH - dH * finalScale) / 2f + posY
            matrix.postTranslate(dx, dy)
            imageMatrix = matrix
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaleDetector?.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = ev.x
                    lastTouchY = ev.y
                    activePointerId = ev.getPointerId(0)
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = ev.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val dx = ev.getX(pointerIndex) - lastTouchX
                        val dy = ev.getY(pointerIndex) - lastTouchY
                        posX += dx
                        posY += dy
                        lastTouchX = ev.getX(pointerIndex)
                        lastTouchY = ev.getY(pointerIndex)
                        applyMatrix()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = (ev.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
                    val pointerId = ev.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        lastTouchX = ev.getX(newPointerIndex)
                        lastTouchY = ev.getY(newPointerIndex)
                        activePointerId = ev.getPointerId(newPointerIndex)
                    }
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyMatrix()
        }
    }
}
