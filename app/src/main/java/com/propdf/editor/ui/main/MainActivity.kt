package com.propdf.editor.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.R
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Refactored MainActivity using MVVM + StateFlow.
 * All business logic moved to MainViewModel.
 * UI is purely reactive to StateFlow emissions.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var rootFrame: FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var searchBox: EditText
    private lateinit var fileListContainer: LinearLayout
    private lateinit var sortBtn: TextView
    private lateinit var viewModeBtn: TextView

    private var isDark = true
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun cardBorder() = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private fun navBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun tabPill() = if (isDark) Color.parseColor("#2C2C2E") else Color.TRANSPARENT
    private fun tabActTxt() = Color.parseColor("#448AFF")
    private fun tabInaTxt() = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")

    private val PRIMARY = "#448AFF"
    private val ACCENT = "#FFD60A"
    private val c_pri get() = Color.parseColor(PRIMARY)
    private val c_acc get() = Color.parseColor(ACCENT)

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        viewModel.openPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        observeViewModel()
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openPdf(it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openPdf(it) }
        }
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

    private fun updateUI(state: MainUiState) {
        fileListContainer.removeAllViews()
        if (state.files.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        when (state.viewMode) {
            ViewMode.LIST -> state.files.forEach { fileListContainer.addView(buildListItem(it)) }
            ViewMode.GRID -> buildGridLayout(state.files)
            ViewMode.TILE -> state.files.forEach { fileListContainer.addView(buildTileItem(it)) }
        }
        sortBtn.text = "Sort: ${state.sortLabel}"
        viewModeBtn.text = "View: ${state.viewMode.name}"
    }

    private fun buildUI() {
        applySystemBarColors()
        rootFrame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        column.addView(buildHeader())
        column.addView(buildTabBar())
        column.addView(buildSectionRow())
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(90)) }
        tvEmpty = TextView(this).apply {
            text = "No files yet\nTap + to open a PDF"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(txt2()))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(80) }
        }
        body.addView(tvEmpty)
        fileListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(fileListContainer)
        scroll.addView(body)
        column.addView(scroll)
        rootFrame.addView(column)
        rootFrame.addView(buildFab())
        rootFrame.addView(buildBottomNav())
        setContentView(rootFrame)
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(16), dp(48), dp(16), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "ProPDF Editor"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
            })
            addView(TextView(this@MainActivity).apply {
                text = "No ads, no watermarks, fully open-source"
                textSize = 12f
                setTextColor(Color.parseColor(txt2()))
                setPadding(0, dp(2), 0, dp(12))
            })
            searchBox = EditText(this@MainActivity).apply {
                hint = "Search files..."
                textSize = 14f
                setHintTextColor(Color.parseColor(txt2()))
                setTextColor(Color.parseColor(txt1()))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#E5E5EA"))
                    cornerRadius = dp(12).toFloat()
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        viewModel.setSearchQuery(s?.toString()?.trim() ?: "")
                    }
                })
            }
            addView(searchBox)
        }
    }

    private fun buildTabBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            Tab.entries.forEach { tab ->
                addView(TextView(this@MainActivity).apply {
                    text = tab.label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    val isActive = viewModel.uiState.value.currentTab == tab
                    setTextColor(if (isActive) tabActTxt() else tabInaTxt())
                    background = GradientDrawable().apply {
                        setColor(if (isActive) tabPill() else Color.TRANSPARENT)
                        cornerRadius = dp(14).toFloat()
                    }
                    setOnClickListener { viewModel.setTab(tab) }
                })
            }
        }
    }

    private fun buildSectionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(16), dp(4), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            sortBtn = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.parseColor(txt2()))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showSortDialog() }
            }
            viewModeBtn = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Color.parseColor(txt2()))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showViewModeDialog() }
            }
            addView(sortBtn)
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(viewModeBtn)
        }
    }

    private fun buildFab(): View {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(20), dp(100))
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c_pri) }
            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            setOnClickListener { showAddMenu() }
        }
    }

    private fun showAddMenu() {
        val items = arrayOf("Open PDF from storage", "Scan document", "Create blank PDF", "Create from images", "OCR from image")
        AlertDialog.Builder(this).setTitle("Add Document").setItems(items) { _, which ->
            when (which) {
                0 -> pdfPicker.launch(arrayOf("application/pdf"))
                1 -> startActivity(Intent(this, DocumentScannerActivity::class.java))
                2 -> createBlankPdf()
                3 -> viewModel.createFromImages()
                4 -> { }
            }
        }.show()
    }

    private fun buildListItem(file: RecentFile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            background = GradientDrawable().apply {
                setColor(cardBg())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), cardBorder())
            }
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(64)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(renderPdfThumbnail(file.uri))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = dp(6).toFloat()
            }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val nameTv = TextView(this).apply {
            text = file.displayName
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(txt1()))
            maxLines = 1
        }
        val metaTv = TextView(this).apply {
            text = "${formatSize(file.fileSizeBytes)} • ${formatDate(file.lastOpenedAt)} • ${file.pageCount} pages"
            textSize = 11f
            setTextColor(Color.parseColor(txt2()))
        }
        infoCol.addView(nameTv)
        infoCol.addView(metaTv)
        row.addView(thumb)
        row.addView(infoCol)
        val star = TextView(this).apply {
            text = if (file.isFavourite) "\u2605" else "\u2606"
            textSize = 18f
            setTextColor(if (file.isFavourite) c_acc else Color.parseColor(txt2()))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { viewModel.toggleFavourite(file.uri) }
        }
        row.addView(star)
        row.setOnClickListener { viewModel.openPdfString(file.uri) }
        row.setOnLongClickListener { showFileContextMenu(file); true }
        return row
    }

    private fun buildGridLayout(files: List<RecentFile>) {
        files.forEach { fileListContainer.addView(buildListItem(it)) }
    }

    private fun buildTileItem(file: RecentFile): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            background = GradientDrawable().apply {
                setColor(cardBg())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), cardBorder())
            }
            addView(TextView(this@MainActivity).apply {
                text = "\uD83D\uDCC4"
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }
            })
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = "${file.displayName}\n${formatSize(file.fileSizeBytes)} • ${file.pageCount} pages"
                textSize = 12f
                setTextColor(Color.parseColor(txt1()))
            })
            setOnClickListener { viewModel.openPdfString(file.uri) }
            setOnLongClickListener { showFileContextMenu(file); true }
        }
    }

    private fun showFileContextMenu(file: RecentFile) {
        val items = arrayOf("Open", "Star / Unstar", "Categorize...", "Rename", "Delete", "Share", "Properties")
        AlertDialog.Builder(this).setTitle(file.displayName).setItems(items) { _, which ->
            when (which) {
                0 -> viewModel.openPdfString(file.uri)
                1 -> viewModel.toggleFavourite(file.uri)
                2 -> showCategorizeDialog(file)
                3 -> showRenameDialog(file)
                4 -> viewModel.deleteFile(file.uri)
                5 -> shareFile(file)
                6 -> showProperties(file)
            }
        }.show()
    }

    private fun showCategorizeDialog(file: RecentFile) {
        val cats = viewModel.uiState.value.allCategories.toMutableList()
        cats.add("New category...")
        AlertDialog.Builder(this).setTitle("Categorize").setItems(cats.toTypedArray()) { _, which ->
            if (which == cats.size - 1) {
                val et = EditText(this).apply { hint = "Category name" }
                AlertDialog.Builder(this).setTitle("New Category").setView(et).setPositiveButton("Save") { _, _ ->
                    viewModel.setCategory(file.uri, et.text.toString().trim().ifEmpty { "Uncategorized" })
                }.setNegativeButton("Cancel", null).show()
            } else {
                viewModel.setCategory(file.uri, cats[which])
            }
        }.show()
    }

    private fun showRenameDialog(file: RecentFile) {
        val et = EditText(this).apply { setText(file.displayName) }
        AlertDialog.Builder(this).setTitle("Rename").setView(et).setPositiveButton("Save") { _, _ ->
            val newName = et.text.toString().trim()
            if (newName.isNotEmpty()) viewModel.renameFile(file.uri, newName)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun shareFile(file: RecentFile) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(file.uri))
        }
        startActivity(Intent.createChooser(share, "Share PDF"))
    }

    private fun showProperties(file: RecentFile) {
        val msg = "Name: ${file.displayName}\nURI: ${file.uri}\nSize: ${formatSize(file.fileSizeBytes)}\nPages: ${file.pageCount}\nOpened: ${formatDate(file.lastOpenedAt)}\nCategory: ${file.category.takeIf { it.isNotBlank() } ?: "None"}"
        AlertDialog.Builder(this).setTitle("Properties").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun showSortDialog() {
        val items = arrayOf("Date (newest first)", "Date (oldest first)", "Name (A-Z)", "Name (Z-A)", "Size (largest first)", "Size (smallest first)")
        AlertDialog.Builder(this).setTitle("Sort by").setItems(items) { _, which ->
            val field = when (which) { 0, 1 -> SortField.DATE; 2, 3 -> SortField.NAME; else -> SortField.SIZE }
            val asc = which in listOf(1, 2, 5)
            viewModel.setSort(field, asc)
        }.show()
    }

    private fun showViewModeDialog() {
        val items = arrayOf("List", "Grid", "Tile")
        AlertDialog.Builder(this).setTitle("View mode").setItems(items) { _, which ->
            viewModel.setViewMode(ViewMode.entries[which])
        }.show()
    }

    private fun createBlankPdf() {
        val et = EditText(this).apply {
            hint = "Page count (1-50)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("Blank PDF").setView(et).setPositiveButton("Create") { _, _ ->
            val count = et.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 1
            viewModel.createBlankPdf(count)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(navBg())
            setPadding(0, dp(10), 0, dp(24))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        }
        listOf(
            "Home" to { viewModel.setTab(Tab.RECENT) },
            "Scanner" to { startActivity(Intent(this, DocumentScannerActivity::class.java)) },
            "Tools" to { startActivity(Intent(this, ToolsActivity::class.java)) },
            "Settings" to { showSettingsDialog() }
        ).forEach { (label, action) ->
            nav.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(Color.parseColor(txt2()))
                setOnClickListener { action() }
            })
        }
        return nav
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
            "Clear Recent Files",
            "Delete All Data",
            "About ProPDF"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    isDark = !isDark
                    prefs.edit().putBoolean("dark_mode", isDark).apply()
                    recreate()
                }
                1 -> viewModel.clearRecent()
                2 -> AlertDialog.Builder(this).setTitle("Delete ALL data?").setMessage("This cannot be undone.").setPositiveButton("Delete") { _, _ ->
                    viewModel.clearAll()
                }.setNegativeButton("Cancel", null).show()
                3 -> AlertDialog.Builder(this).setTitle("About ProPDF").setMessage("ProPDF Editor v3.0\nOpen-source PDF editor for Android.\nNo ads, no watermarks.").setPositiveButton("OK", null).show()
            }
        }.show()
    }

    // ===================== THUMBNAIL RENDERING =====================
    private val thumbCache = android.util.LruCache<String, Bitmap>(20)

    private fun renderPdfThumbnail(uriStr: String): Bitmap? {
        val cached = thumbCache.get(uriStr)
        if (cached != null && !cached.isRecycled) return cached
        return try {
            val uri = Uri.parse(uriStr)
            val pfd = if (uri.scheme == "content") {
                contentResolver.openFileDescriptor(uri, "r")
            } else {
                ParcelFileDescriptor.open(File(uri.path ?: return null), ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: return null
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(0)
            val scale = 320f / page.width.coerceAtLeast(1).toFloat()
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()
            thumbCache.put(uriStr, bmp)
            bmp
        } catch (_: Exception) { null }
    }

    // ===================== UTILITIES =====================
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatDate(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }

    private fun applySystemBarColors() {
        window.statusBarColor = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
        window.navigationBarColor = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
