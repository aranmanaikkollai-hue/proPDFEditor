package com.propdf.editor.ui

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.R
import com.propdf.editor.data.local.RecentFilesDatabase
import com.propdf.editor.data.local.RecentFile
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var db: RecentFilesDatabase

    private lateinit var rootFrame: FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var searchBox: EditText
    private lateinit var fileListContainer: LinearLayout
    private lateinit var sortBtn: TextView
    private lateinit var viewModeBtn: TextView

    private var currentTab = "recent"
    private var catDetailName = ""
    private var catDetailDepth = 0
    private var sortField = "date"
    private var sortAsc = false
    private var viewMode = "list"
    private var searchQuery = ""
    private var isDark = true
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private val allFiles = mutableListOf<RecentFile>()

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun cardBorder() = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private fun navBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun divLine() = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun tabPill() = if (isDark) Color.parseColor("#2C2C2E") else Color.TRANSPARENT
    private fun tabActTxt() = Color.parseColor("#448AFF")
    private fun tabInaTxt() = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")

    private val PRIMARY = "#448AFF"; private val ACCENT = "#FFD60A"; private val DANGER = "#E53935"
    private val c_pri get() = Color.parseColor(PRIMARY)
    private val c_acc get() = Color.parseColor(ACCENT)

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        openUri(uri)
    }
    private val ocrPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { _: Uri? ->
        toast("OCR from gallery -- use OcrManager.ocrBitmap()")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(::openUri)
        }
        observeFiles()
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(::openUri)
        }
    }
    override fun onBackPressed() {
        // FIX: back from cat_detail returns to categories, not exits
        if (currentTab == "cat_detail") {
            currentTab = "categories"; catDetailName = ""; catDetailDepth = 0
            updateTabHighlight("categories"); rebuildFileList(); return
        }
        super.onBackPressed()
    }

    // -------------------------------------------------------
    // ROOT UI
    // -------------------------------------------------------

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
            text = "No files yet
Tap + to open a PDF"
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
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg())
            setPadding(dp(16), dp(48), dp(16), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "ProPDF Editor"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
            })
            addView(TextView(this@MainActivity).apply {
                text = "No ads, no watermarks, fully open-source"; textSize = 12f
                setTextColor(Color.parseColor(txt2())); setPadding(0, dp(2), 0, dp(12))
            })
            searchBox = EditText(this@MainActivity).apply {
                hint = "Search files..."; textSize = 14f; setHintTextColor(Color.parseColor(txt2()))
                setTextColor(Color.parseColor(txt1())); setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#E5E5EA"))
                    cornerRadius = dp(12).toFloat()
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { searchQuery = s?.toString()?.trim() ?: ""; rebuildFileList() }
                })
            }
            addView(searchBox)
        }
    }

    private fun buildTabBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg()); setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            listOf("recent" to "Recent", "starred" to "Starred", "categories" to "Categories", "bookmarks" to "Bookmarks").forEach { (key, label) ->
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    setTextColor(if (currentTab == key) tabActTxt() else tabInaTxt())
                    background = GradientDrawable().apply {
                        setColor(if (currentTab == key) tabPill() else Color.TRANSPARENT)
                        cornerRadius = dp(14).toFloat()
                    }
                    setOnClickListener {
                        currentTab = key; if (key == "categories") { catDetailName = ""; catDetailDepth = 0 }
                        updateTabHighlight(key); rebuildFileList()
                    }
                })
            }
        }
    }

    private fun updateTabHighlight(active: String) {
        (findViewById<LinearLayout>(android.R.id.content).findViewWithTag<LinearLayout>("tab_bar") ?: return).let { bar ->
            (0 until bar.childCount).forEach { i ->
                val tv = bar.getChildAt(i) as? TextView ?: return@forEach
                val isActive = tv.text.toString().lowercase().let { it == active || (active == "cat_detail" && it == "categories") }
                tv.setTextColor(if (isActive) tabActTxt() else tabInaTxt())
                tv.background = GradientDrawable().apply {
                    setColor(if (isActive) tabPill() else Color.TRANSPARENT); cornerRadius = dp(14).toFloat()
                }
            }
        }
    }

    private fun buildSectionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg()); setPadding(dp(16), dp(4), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            sortBtn = TextView(this@MainActivity).apply {
                text = "Sort: Date"; textSize = 12f; setTextColor(Color.parseColor(txt2()))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showSortDialog() }
            }
            viewModeBtn = TextView(this@MainActivity).apply {
                text = "View: List"; textSize = 12f; setTextColor(Color.parseColor(txt2()))
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
                gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(20), dp(100))
            }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c_pri) }
            addView(TextView(this@MainActivity).apply {
                text = "+"; textSize = 28f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            setOnClickListener { showAddMenu() }
        }
    }

    private fun showAddMenu() {
        val items = arrayOf("Open PDF from storage", "Scan document", "Create blank PDF", "Create from images", "OCR from image")
        AlertDialog.Builder(this).setTitle("Add Document").setItems(items) { _, which ->
            when (which) {
                0 -> pdfPicker.launch(arrayOf("application/pdf"))
                1 -> startActivity(Intent(this, com.propdf.editor.ui.scanner.DocumentScannerActivity::class.java))
                2 -> createBlankPdf()
                3 -> createFromImages()
                4 -> ocrPicker.launch("image/*")
            }
        }.show()
    }

    // -------------------------------------------------------
    // FILE LIST RENDERING
    // -------------------------------------------------------

    private fun rebuildFileList() {
        fileListContainer.removeAllViews()
        val filtered = filterAndSortFiles()
        if (filtered.isEmpty()) { tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE
        when (viewMode) {
            "list" -> filtered.forEach { fileListContainer.addView(buildListItem(it)) }
            "grid" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                var count = 0
                filtered.forEach { file ->
                    if (count == 2) { fileListContainer.addView(row); count = 0 }
                    if (count == 0) row.removeAllViews()
                    val card = buildGridCard(file); card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (count == 0) dp(8) else 0 }
                    row.addView(card); count++
                }
                if (count > 0) fileListContainer.addView(row)
            }
            "tile" -> filtered.forEach { fileListContainer.addView(buildTileItem(it)) }
        }
    }

    private fun filterAndSortFiles(): List<RecentFile> {
        var list = allFiles.toList()
        if (searchQuery.isNotEmpty()) list = list.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        when (currentTab) {
            "recent" -> list = list.sortedByDescending { it.lastOpened }
            "starred" -> list = list.filter { it.favourite }
            "categories" -> {
                if (catDetailName.isEmpty()) {
                    val cats = list.mapNotNull { it.category }.distinct().sorted()
                    return cats.map { cat -> RecentFile(
                        uri = "category://$cat", displayName = cat, size = 0, lastOpened = 0,
                        pageCount = list.count { it.category == cat }, favourite = false, category = cat
                    ) }
                } else {
                    list = list.filter { it.category == catDetailName }
                }
            }
            "bookmarks" -> {
                val bPrefs = getSharedPreferences("propdf_bookmarks", Context.MODE_PRIVATE)
                val bmUris = bPrefs.all.keys
                list = list.filter { it.uri in bmUris }
            }
        }
        list = when (sortField) {
            "date" -> if (sortAsc) list.sortedBy { it.lastOpened } else list.sortedByDescending { it.lastOpened }
            "name" -> if (sortAsc) list.sortedBy { it.displayName.lowercase() } else list.sortedByDescending { it.displayName.lowercase() }
            "size" -> if (sortAsc) list.sortedBy { it.size } else list.sortedByDescending { it.size }
            else -> list
        }
        return list
    }

    private fun buildListItem(file: RecentFile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            background = GradientDrawable().apply {
                setColor(cardBg()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), cardBorder())
            }
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(64)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(renderPdfThumbnail(file.uri))
            background = GradientDrawable().apply { setColor(Color.parseColor("#2A2A2A")); cornerRadius = dp(6).toFloat() }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val nameTv = TextView(this).apply {
            text = file.displayName; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(txt1())); maxLines = 1
        }
        val metaTv = TextView(this).apply {
            text = "${formatSize(file.size)}  \u2022  ${formatDate(file.lastOpened)}  \u2022  ${file.pageCount} pages"
            textSize = 11f; setTextColor(Color.parseColor(txt2()))
        }
        infoCol.addView(nameTv); infoCol.addView(metaTv)
        row.addView(thumb); row.addView(infoCol)
        val star = TextView(this).apply {
            text = if (file.favourite) "\u2605" else "\u2606"; textSize = 18f
            setTextColor(if (file.favourite) Color.parseColor(ACCENT) else Color.parseColor(txt2()))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { toggleFavourite(file); rebuildFileList() }
        }
        row.addView(star)
        row.setOnClickListener { openUriString(file.uri) }
        row.setOnLongClickListener { showFileContextMenu(file); true }
        return row
    }

    private fun buildGridCard(file: RecentFile): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(180), 1f).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply { setColor(cardBg()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), cardBorder()) }
            val thumb = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, dp(140))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(renderPdfThumbnail(file.uri))
            }
            addView(thumb)
            val label = TextView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
                text = file.displayName; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1())); setPadding(dp(8), dp(6), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(cardBg()); cornerRadius = dp(10).toFloat() }
            }
            addView(label)
            setOnClickListener { openUriString(file.uri) }
            setOnLongClickListener { showFileContextMenu(file); true }
        }
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
                text = "${file.displayName}
${formatSize(file.size)}  \u2022  ${file.pageCount} pages"
                textSize = 12f
                setTextColor(Color.parseColor(txt1()))
            })
            setOnClickListener { openUriString(file.uri) }
            setOnLongClickListener { showFileContextMenu(file); true }
        }
    }

    private fun showFileContextMenu(file: RecentFile) {
        val items = arrayOf("Open", "Star / Unstar", "Categorize...", "Rename", "Delete", "Share", "Properties")
        AlertDialog.Builder(this).setTitle(file.displayName).setItems(items) { _, which ->
            when (which) {
                0 -> openUriString(file.uri)
                1 -> { toggleFavourite(file); rebuildFileList() }
                2 -> showCategorizeDialog(file)
                3 -> showRenameDialog(file)
                4 -> deleteFile(file)
                5 -> shareFile(file)
                6 -> showProperties(file)
            }
        }.show()
    }

    private fun showCategorizeDialog(file: RecentFile) {
        val cats = allFiles.mapNotNull { it.category }.distinct().toMutableList()
        cats.add("New category...")
        AlertDialog.Builder(this).setTitle("Categorize").setItems(cats.toTypedArray()) { _, which ->
            if (which == cats.size - 1) {
                val et = EditText(this).apply { hint = "Category name" }
                AlertDialog.Builder(this).setTitle("New Category").setView(et).setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch {
                        val cat = et.text.toString().trim().ifEmpty { "Uncategorized" }
                        db.dao().updateCategory(file.uri, cat)
                        rebuildFileList()
                    }
                }.setNegativeButton("Cancel", null).show()
            } else {
                lifecycleScope.launch { db.dao().updateCategory(file.uri, cats[which]); rebuildFileList() }
            }
        }.show()
    }

    private fun showRenameDialog(file: RecentFile) {
        val et = EditText(this).apply { setText(file.displayName) }
        AlertDialog.Builder(this).setTitle("Rename").setView(et).setPositiveButton("Save") { _, _ ->
            val newName = et.text.toString().trim(); if (newName.isEmpty()) return@setPositiveButton
            lifecycleScope.launch { db.dao().updateName(file.uri, newName); rebuildFileList() }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteFile(file: RecentFile) {
        AlertDialog.Builder(this).setTitle("Delete?").setMessage("Remove ${file.displayName} from history?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { db.dao().deleteByUri(file.uri); rebuildFileList() }
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
        val msg = "Name: ${file.displayName}
URI: ${file.uri}
Size: ${formatSize(file.size)}
Pages: ${file.pageCount}
Opened: ${formatDate(file.lastOpened)}
Category: ${file.category ?: "None"}"
        AlertDialog.Builder(this).setTitle("Properties").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun toggleFavourite(file: RecentFile) {
        lifecycleScope.launch { db.dao().toggleFavourite(file.uri, !file.favourite) }
    }

    private fun showSortDialog() {
        val items = arrayOf("Date (newest first)", "Date (oldest first)", "Name (A-Z)", "Name (Z-A)", "Size (largest first)", "Size (smallest first)")
        AlertDialog.Builder(this).setTitle("Sort by").setItems(items) { _, which ->
            sortField = when (which) { 0, 1 -> "date"; 2, 3 -> "name"; else -> "size" }
            sortAsc = which in listOf(1, 2, 5)
            sortBtn.text = "Sort: ${items[which]}"
            rebuildFileList()
        }.show()
    }

    private fun showViewModeDialog() {
        val items = arrayOf("List", "Grid", "Tile")
        AlertDialog.Builder(this).setTitle("View mode").setItems(items) { _, which ->
            viewMode = items[which].lowercase(); viewModeBtn.text = "View: ${items[which]}"
            rebuildFileList()
        }.show()
    }

    // -------------------------------------------------------
    // THUMBNAIL RENDERING
    // -------------------------------------------------------

    private val thumbCache = android.util.LruCache<String, Bitmap>(20)

    private fun renderPdfThumbnail(uriStr: String): Bitmap? {
        val cached = thumbCache.get(uriStr); if (cached != null && !cached.isRecycled) return cached
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
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close(); pfd.close()
            thumbCache.put(uriStr, bmp); bmp
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // DATA OBSERVATION
    // -------------------------------------------------------

    private fun observeFiles() {
        lifecycleScope.launch {
            db.dao().getAll().collectLatest { list ->
                allFiles.clear(); allFiles.addAll(list)
                withContext(Dispatchers.Main) { rebuildFileList() }
            }
        }
    }

    // -------------------------------------------------------
    // OPEN FILE
    // -------------------------------------------------------

    private fun openUri(uri: Uri) {
        val name = FileHelper.getFileName(this, uri) ?: "document.pdf"
        lifecycleScope.launch {
            val pageCount = withContext(Dispatchers.IO) { countPdfPages(uri) }
            val size = withContext(Dispatchers.IO) { getUriSize(uri) }
            db.dao().insertOrUpdate(RecentFile(
                uri = uri.toString(), displayName = name, size = size,
                lastOpened = System.currentTimeMillis(), pageCount = pageCount
            ))
            val intent = Intent(this@MainActivity, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    private fun openUriString(uriStr: String) {
        try { openUri(Uri.parse(uriStr)) } catch (_: Exception) { toast("Cannot open file") }
    }

    private fun countPdfPages(uri: Uri): Int {
        return try {
            val pfd = if (uri.scheme == "content") {
                contentResolver.openFileDescriptor(uri, "r")
            } else {
                ParcelFileDescriptor.open(File(uri.path ?: return 0), ParcelFileDescriptor.MODE_READ_ONLY)
            } ?: return 0
            val renderer = PdfRenderer(pfd); val count = renderer.pageCount; renderer.close(); pfd.close(); count
        } catch (_: Exception) { 0 }
    }

    private fun getUriSize(uri: Uri): Long {
        return try {
            if (uri.scheme == "file") File(uri.path ?: return 0).length()
            else contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: 0
        } catch (_: Exception) { 0 }
    }

    // -------------------------------------------------------
    // BLANK PDF / IMAGE TO PDF
    // -------------------------------------------------------

    private fun createBlankPdf() {
        val et = EditText(this).apply { hint = "Page count (1-50)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Blank PDF").setView(et).setPositiveButton("Create") { _, _ ->
            val count = et.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 1
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) { saveBlankPdf(count) }
                if (uri != null) { toast("Blank PDF created"); openUri(uri) } else toast("Failed")
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun saveBlankPdf(count: Int): Uri? {
        val doc = android.graphics.pdf.PdfDocument()
        return try {
            try {
                for (i in 1..count) {
                    val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(612, 792, i).create()
                    val page = doc.startPage(pi)
                    page.canvas.drawColor(Color.WHITE)
                    doc.finishPage(page)
                }
                val fileName = "Blank_${System.currentTimeMillis()}.pdf"
                var outUri: Uri? = null
                val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                    }
                    outUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    outUri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                    val outFile = File(dir, fileName)
                    outUri = Uri.fromFile(outFile)
                    java.io.FileOutputStream(outFile)
                }
                if (out == null) return null
                out.use { doc.writeTo(it) }
                outUri
            } finally { doc.close() }
        } catch (_: Exception) { null }
    }

    private fun createFromImages() {
        val imagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) { imagesToPdf(uris) }
                if (uri != null) { toast("PDF created from ${uris.size} images"); openUri(uri) }
                else toast("Failed to create PDF")
            }
        }
        imagePicker.launch("image/*")
    }

    private fun imagesToPdf(uris: List<Uri>): Uri? {
        val doc = android.graphics.pdf.PdfDocument()
        return try {
            try {
                uris.forEachIndexed { i, uri ->
                    val bmp = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
                    val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                    val page = doc.startPage(pi); page.canvas.drawBitmap(bmp, 0f, 0f, null); doc.finishPage(page); bmp.recycle()
                }
                val fileName = "Images_${System.currentTimeMillis()}.pdf"
                var outUri: Uri? = null
                val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                    }
                    outUri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    outUri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                    val outFile = File(dir, fileName)
                    outUri = Uri.fromFile(outFile)
                    java.io.FileOutputStream(outFile)
                }
                if (out == null) return null
                out.use { doc.writeTo(it) }
                outUri
            } finally { doc.close() }
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // BOTTOM NAV
    // -------------------------------------------------------

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(navBg())
            setPadding(0, dp(10), 0, dp(24))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        }
        listOf(
            "Home" to { currentTab = "recent"; catDetailName = ""; catDetailDepth = 0; rebuildFileList() },
            "Scanner" to { startActivity(Intent(this, com.propdf.editor.ui.scanner.DocumentScannerActivity::class.java)) },
            "Tools" to { startActivity(Intent(this, ToolsActivity::class.java)) },
            "Settings" to { showSettingsDialog() }
        ).forEach { (label, action) ->
            nav.addView(TextView(this).apply {
                text = label; textSize = 11f; gravity = Gravity.CENTER
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
            "Clear Recent Files", "Delete All Data", "About ProPDF"
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, which ->
            when (which) {
                0 -> { isDark = !isDark; prefs.edit().putBoolean("dark_mode", isDark).apply(); recreate() }
                1 -> lifecycleScope.launch { db.dao().clearRecentOnly(); toast("Recent files cleared") }
                2 -> AlertDialog.Builder(this).setTitle("Delete ALL data?").setMessage("This cannot be undone.").setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { db.dao().deleteAll(); toast("All data deleted") }
                }.setNegativeButton("Cancel", null).show()
                3 -> AlertDialog.Builder(this).setTitle("About ProPDF").setMessage("ProPDF Editor v1.0
Open-source PDF editor for Android.
No ads, no watermarks.").setPositiveButton("OK", null).show()
            }
        }.show()
    }

    // -------------------------------------------------------
    // FIX: Clear recent strictly preserves starred, bookmarked AND categorized files
    // -------------------------------------------------------

    private suspend fun clearRecentPreservingPinned() {
        val bmPrefs = getSharedPreferences("propdf_bookmarks", MODE_PRIVATE)
        allFiles.forEach { file ->
            val bookmarkKey = file.uri.hashCode().toString()
            val hasBookmarks = (bmPrefs.getStringSet(bookmarkKey, emptySet()) ?: emptySet()).isNotEmpty()
            val pinned = file.favourite || !file.category.isNullOrBlank() || hasBookmarks
            if (!pinned) db.dao().updateLastOpened(file.uri, 0L)
        }
    }

    // -------------------------------------------------------
    // UTILS
    // -------------------------------------------------------

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
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
