package com.propdf.editor.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.propdf.editor.data.local.RecentFileEntity
import com.propdf.editor.data.local.RecentFilesDatabase
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val db    by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("propdf_prefs", MODE_PRIVATE) }

    private var isDark        = true
    private var currentTab    = "recent"
    private var viewMode      = "list"
    private var sortMode      = "date"
    private var sortAsc       = false
    private var catDetailName = ""
    private val expandedCategories = mutableSetOf<String>()
    private var allFileEntities: List<RecentFileEntity> = emptyList()
    private val thumbnailCache = mutableMapOf<String, Bitmap>()

    private lateinit var rootFrame        : FrameLayout
    private lateinit var tvEmpty          : TextView
    private lateinit var tvSection        : TextView
    private lateinit var tabRow           : LinearLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var viewToggleBtn    : ImageButton
    private lateinit var sortBtn          : TextView

    private fun bg()        = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F7")
    private fun card()      = if (isDark) "#2A2A2A" else "#FFFFFF"
    private fun cardBrd()   = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E8E8EC")
    private fun txt1()      = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2()      = if (isDark) "#A0A0A0" else "#6B7280"
    private fun navBg()     = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun divLine()   = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun tabPill()   = if (isDark) Color.parseColor("#2C2C2E") else Color.TRANSPARENT
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
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
        observeFiles()
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
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
            text = "No files yet\nTap + to open a PDF"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(txt2())); setPadding(dp(32), dp(64), dp(32), dp(64)); visibility = View.GONE
        }
        fileListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0) }
        body.addView(tvEmpty); body.addView(fileListContainer)
        scroll.addView(body); column.addView(scroll)
        rootFrame.addView(column); rootFrame.addView(buildBottomNav())
        setContentView(rootFrame)
    }

    private fun applySystemBarColors() {
        window.statusBarColor = bg()
        window.navigationBarColor = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
        if (!isDark) window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(68)); setBackgroundColor(bg()); setPadding(dp(16), dp(4), dp(12), dp(4))
            addView(FrameLayout(this@MainActivity).apply {
                val s = dp(46); layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0, 0, dp(10), 0) }
                setBackgroundColor(c_pri)
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) } }
                clipToOutline = true; elevation = dp(2).toFloat()
                addView(ImageView(this@MainActivity).apply { setImageResource(android.R.drawable.ic_menu_agenda); setColorFilter(Color.WHITE); layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER } })
            })
            val tc = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            tc.addView(TextView(this@MainActivity).apply { text = "ProPDF"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c_pri) })
            tc.addView(TextView(this@MainActivity).apply { text = "HOME"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt2())); letterSpacing = 0.15f })
            addView(tc)
            addView(hdrBtn(android.R.drawable.ic_menu_search) { showSearchDialog() })
            addView(ImageButton(this@MainActivity).apply {
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.parseColor(txt2())); setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { toggleTheme() }
                post { setImageResource(if (isDark) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_day); setColorFilter(Color.parseColor(txt2())) }
            })
            addView(hdrBtn(android.R.drawable.ic_menu_preferences) { showSettings() })
        }
    }

    // -------------------------------------------------------
    // TAB BAR (Recent | Starred | Vault | Bookmarks)
    // -------------------------------------------------------

    private fun buildTabBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg()) }
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(4)) }
        listOf("Recent" to "recent", "Starred" to "starred", "Vault" to "categories", "Bookmarks" to "bookmarks").forEach { (label, id) ->
            val active = id == currentTab
            tabRow.addView(TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(10), dp(7), dp(10), dp(7))
                setTextColor(if (active) tabActTxt() else tabInaTxt()); typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tag = "${id}_tv"
                if (isDark && active) { setBackgroundColor(tabPill()); outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()) } }; clipToOutline = true }
                setOnClickListener { switchTab(id) }
            })
            tabRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(2), dp(1)) })
        }
        wrapper.addView(tabRow); wrapper.addView(buildSortViewRow())
        wrapper.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(1)); setBackgroundColor(divLine()) })
        return wrapper
    }

    private fun buildSortViewRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(5)); layoutParams = LinearLayout.LayoutParams(-1, dp(34))
            sortBtn = TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f); text = sortLabel(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt2())); setOnClickListener { showSortMenu() }
            }
            addView(sortBtn)
            viewToggleBtn = ImageButton(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)); setImageResource(viewToggleIcon()); colorFilter = PorterDuffColorFilter(c_pri, PorterDuff.Mode.SRC_IN)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { viewMode = when (viewMode) { "list" -> "grid"; "grid" -> "tile"; else -> "list" }; setImageResource(viewToggleIcon()); rebuildFileList() }
            }
            addView(viewToggleBtn)
        }
    }

    private fun buildSectionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6)); setBackgroundColor(bg())
            tvSection = TextView(this@MainActivity).apply {
                text = if (isDark) "Recent Files" else "LAST ACCESSED"; textSize = if (isDark) 20f else 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1())); letterSpacing = if (isDark) 0f else 0.12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(tvSection)
        }
    }

    // -------------------------------------------------------
    // BOTTOM NAV (HOME | TOOLS | FAB | SCAN | OCR)
    // -------------------------------------------------------

    private fun buildBottomNav(): FrameLayout {
        val frame = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM } }
        frame.addView(View(this).apply { layoutParams = FrameLayout.LayoutParams(-1, dp(1)).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(68) }; setBackgroundColor(divLine()) })
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(-1, dp(68)).apply { gravity = Gravity.BOTTOM }; setBackgroundColor(navBg()); setPadding(0, 0, 0, dp(8))
        }
        data class NI(val id: String, val lbl: String, val icon: Int)
        listOf(NI("home","HOME",android.R.drawable.ic_menu_view), NI("tools","TOOLS",android.R.drawable.ic_menu_preferences),
               NI("__fab__","",0), NI("scan","SCAN",android.R.drawable.ic_menu_camera), NI("ocr","OCR",android.R.drawable.ic_menu_edit)).forEach { item ->
            if (item.id == "__fab__") { navBar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }); return@forEach }
            val active = item.id == "home" && currentTab in listOf("recent","starred","categories","cat_detail","bookmarks")
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener { when (item.id) { "home" -> if (currentTab != "recent") switchTab("recent"); "tools" -> startActivity(Intent(this@MainActivity, ToolsActivity::class.java)); "scan" -> startActivity(Intent(this@MainActivity, DocumentScannerActivity::class.java)); "ocr" -> showOcrDialog() } }
            }
            col.addView(ImageView(this).apply { setImageResource(item.icon); setColorFilter(if (active) c_pri else Color.parseColor(txt2())); layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)); tag = "${item.id}_icon" })
            col.addView(TextView(this).apply { text = item.lbl; textSize = 8f; gravity = Gravity.CENTER; setTextColor(if (active) c_pri else Color.parseColor(txt2())); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; setPadding(0, dp(2), 0, 0); tag = "${item.id}_label" })
            navBar.addView(col)
        }
        val fab = FrameLayout(this).apply {
            val s = dp(56); layoutParams = FrameLayout.LayoutParams(s, s).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) }
            setBackgroundColor(c_pri)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
            clipToOutline = true; elevation = dp(10).toFloat(); setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        }
        fab.addView(ImageView(this).apply { setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.WHITE); layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER } })
        frame.addView(navBar); frame.addView(fab); return frame
    }

    // -------------------------------------------------------
    // TAB SWITCHING
    // -------------------------------------------------------

    private fun switchTab(tab: String) {
        currentTab = tab
        listOf("recent","starred","categories","bookmarks").forEach { t ->
            tabRow.findViewWithTag<TextView>("${t}_tv")?.apply {
                setTextColor(if (t == tab) tabActTxt() else tabInaTxt()); typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setBackgroundColor(if (isDark && t == tab) tabPill() else Color.TRANSPARENT)
            }
        }
        updateNavHighlight(tab); rebuildFileList()
    }

    private fun updateNavHighlight(tab: String) {
        val atHome = tab in listOf("recent","starred","categories","cat_detail","bookmarks")
        listOf("home","tools","scan","ocr").forEach { id ->
            val active = id == "home" && atHome
            rootFrame.findViewWithTag<ImageView>("${id}_icon")?.setColorFilter(if (active) c_pri else Color.parseColor(txt2()))
            rootFrame.findViewWithTag<TextView>("${id}_label")?.setTextColor(if (active) c_pri else Color.parseColor(txt2()))
        }
    }

    private fun toggleTheme() { isDark = !isDark; prefs.edit().putBoolean("dark_mode", isDark).apply(); thumbnailCache.clear(); buildUI(); observeFiles() }

    private fun observeFiles() {
        lifecycleScope.launch { db.recentFilesDao().getAll().collect { files -> allFileEntities = files; rebuildFileList() } }
    }

    // -------------------------------------------------------
    // SORT
    // -------------------------------------------------------

    private fun sortLabel(): String {
        val dir = if (sortAsc) "\u25b2" else "\u25bc"
        return when (sortMode) { "name" -> "Name $dir"; "size" -> "Size $dir"; else -> "Date $dir" }
    }
    private fun viewToggleIcon() = when (viewMode) { "grid" -> android.R.drawable.ic_menu_agenda; "tile" -> android.R.drawable.ic_menu_crop; else -> android.R.drawable.ic_menu_view }

    private fun showSortMenu() {
        AlertDialog.Builder(this).setTitle("Sort by")
            .setItems(arrayOf("Date (newest)","Date (oldest)","Name A-Z","Name Z-A","Size (large)","Size (small)")) { _, which ->
                when (which) {
                    0 -> { sortMode = "date"; sortAsc = false }
                    1 -> { sortMode = "date"; sortAsc = true  }
                    2 -> { sortMode = "name"; sortAsc = false }
                    3 -> { sortMode = "name"; sortAsc = true  }
                    4 -> { sortMode = "size"; sortAsc = false }
                    5 -> { sortMode = "size"; sortAsc = true  }
                }
                if (::sortBtn.isInitialized) sortBtn.text = sortLabel(); rebuildFileList()
            }.show()
    }

    private fun getBookmarkedFiles(): List<RecentFileEntity> {
        val bmPrefs = getSharedPreferences("propdf_bookmarks", MODE_PRIVATE)
        return allFileEntities.filter { entity ->
            val key = entity.uri.hashCode().toString()
            (bmPrefs.getStringSet(key, emptySet()) ?: emptySet()).isNotEmpty()
        }
    }

    private fun sortedFiles(): List<RecentFileEntity> {
        val base = when (currentTab) {
            "starred"    -> allFileEntities.filter { it.isFavourite }
            "cat_detail" -> allFileEntities.filter { it.category == catDetailName }
            "bookmarks"  -> getBookmarkedFiles()
            else         -> allFileEntities.filter { it.lastOpenedAt > 0L }
        }
        return when (sortMode) {
            "name" -> if (sortAsc)
                base.sortedByDescending { it.displayName.lowercase(Locale.getDefault()) }
                else base.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            "size" -> if (sortAsc)
                base.sortedBy { it.fileSizeBytes }
                else base.sortedByDescending { it.fileSizeBytes }
            else -> if (sortAsc)
                base.sortedBy { it.lastOpenedAt }
                else base.sortedByDescending { it.lastOpenedAt }
        }
    }

    private fun sortedFilesForCat(files: List<RecentFileEntity>): List<RecentFileEntity> {
        return when (sortMode) {
            "name" -> if (sortAsc)
                files.sortedByDescending { it.displayName.lowercase(Locale.getDefault()) }
                else files.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            "size" -> if (sortAsc)
                files.sortedBy { it.fileSizeBytes }
                else files.sortedByDescending { it.fileSizeBytes }
            else -> if (sortAsc)
                files.sortedBy { it.lastOpenedAt }
                else files.sortedByDescending { it.lastOpenedAt }
        }
    }

    // -------------------------------------------------------
    // FILE LIST RENDERING
    // -------------------------------------------------------

    private fun rebuildFileList() {
        if (!::fileListContainer.isInitialized) return
        fileListContainer.removeAllViews()
        if (::tvSection.isInitialized) {
            tvSection.text = when (currentTab) { "recent" -> if (isDark) "Recent Files" else "LAST ACCESSED"; "starred" -> "Starred"; "categories" -> "My Vault"; "bookmarks" -> "Bookmarks"; "cat_detail" -> catDetailName; else -> tvSection.text.toString() }
            tvSection.textSize = if (isDark) 20f else 9f
        }
        if (currentTab == "categories") { renderVault(); return }
        val files = sortedFiles()
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (currentTab) { "starred" -> "No starred files\nTap the star on any file"; "bookmarks" -> "No bookmarks yet\nBookmark pages inside the PDF viewer"; else -> "No files yet\nTap + to open a PDF" }
        when (viewMode) { "grid" -> buildGridView(files); "tile" -> buildTileView(files); else -> files.forEach { fileListContainer.addView(buildFileCard(it)) } }
    }

    private fun buildGridView(files: List<RecentFileEntity>) {
        var row: LinearLayout? = null
        files.forEachIndexed { i, entity ->
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }; fileListContainer.addView(row) }
            val card = buildFileCardCompact(entity)
            card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 2 == 0) marginEnd = dp(4) else marginStart = dp(4) }
            row?.addView(card)
            if (i == files.size - 1 && i % 2 == 0) row?.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    private fun buildTileView(files: List<RecentFileEntity>) { files.forEach { fileListContainer.addView(buildFileTileRow(it)) } }

    private fun buildFileCard(f: RecentFileEntity): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(14).toFloat(); cardElevation = if (isDark) 0f else dp(2).toFloat()
            setCardBackgroundColor(Color.parseColor(card())); strokeWidth = if (isDark) dp(1) else 0; strokeColor = cardBrd()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val thumb = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0, 0, dp(14), 0) }
            setBackgroundColor(if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#FFEBEE"))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
            clipToOutline = true
        }
        thumb.addView(TextView(this).apply { text = f.displayName.take(1).uppercase(); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c_pri); gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-1, -1) })
        row.addView(thumb)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        info.addView(TextView(this).apply { text = f.displayName.removeSuffix(".pdf"); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt1())); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(TextView(this).apply { text = "${formatSize(f.fileSizeBytes)} \u2022 ${relativeTime(f.lastOpenedAt)}"; textSize = 11f; setTextColor(Color.parseColor(txt2())) })
        row.addView(info)
        row.addView(ImageButton(this).apply {
            setImageResource(if (f.isFavourite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) } }
        })
        card.addView(row); card.setOnClickListener { openUri(Uri.parse(f.uri)) }; card.setOnLongClickListener { showFileOptions(f); true }
        return card
    }

    private fun buildFileCardCompact(entity: RecentFileEntity): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor(card())); cornerRadius = dp(12).toFloat() }; elevation = dp(2).toFloat()
        }
        val tf = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(110))
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
            clipToOutline = true
        }
        val iv = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); scaleType = ImageView.ScaleType.CENTER_CROP }
        val lv = TextView(this).apply { layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER); text = entity.displayName.firstOrNull()?.uppercase() ?: "P"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#448AFF")) }
        tf.addView(iv); tf.addView(lv)
        tf.addView(TextView(this).apply { layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) }; text = "PDF"; textSize = 8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(4), dp(2), dp(4), dp(2)); background = GradientDrawable().apply { setColor(Color.parseColor("#E53935")); cornerRadius = dp(4).toFloat() } })
        card.addView(tf)
        card.addView(TextView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }; text = entity.displayName.removeSuffix(".pdf"); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt1())); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        card.addView(TextView(this).apply { text = formatSize(entity.fileSizeBytes); textSize = 9f; setTextColor(Color.parseColor(txt2())) })
        card.setOnClickListener { openUri(Uri.parse(entity.uri)) }; card.setOnLongClickListener { showFileOptions(entity); true }
        val cached = thumbnailCache[entity.uri]
        if (cached != null) { iv.setImageBitmap(cached); lv.visibility = View.GONE }
        else lifecycleScope.launch(Dispatchers.IO) { val bmp = renderFirstPage(entity.uri); withContext(Dispatchers.Main) { if (bmp != null) { thumbnailCache[entity.uri] = bmp; iv.setImageBitmap(bmp); lv.visibility = View.GONE } } }
        return card
    }

    private fun buildFileTileRow(entity: RecentFileEntity): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            background = GradientDrawable().apply { setColor(Color.parseColor(card())); cornerRadius = dp(8).toFloat() }
            addView(TextView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)); text = "PDF"; gravity = Gravity.CENTER; textSize = 8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor(DANGER)); cornerRadius = dp(4).toFloat() } })
            addView(TextView(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) }; text = entity.displayName; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt1())); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@MainActivity).apply { text = "${formatSize(entity.fileSizeBytes)} \u2022 ${relativeTime(entity.lastOpenedAt)}"; textSize = 10f; setTextColor(Color.parseColor(txt2())); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } })
            setOnClickListener { openUri(Uri.parse(entity.uri)) }; setOnLongClickListener { showFileOptions(entity); true }
        }
    }

    private fun renderFirstPage(uriStr: String): Bitmap? {
        return try {
            val uri  = Uri.parse(uriStr)
            val dest = File(cacheDir, "thumb_${uriStr.hashCode()}.pdf")
            if (!dest.exists() || dest.length() == 0L) contentResolver.openInputStream(uri)?.use { inp -> FileOutputStream(dest).use { inp.copyTo(it) } }
            if (!dest.exists() || dest.length() == 0L) return null
            val pfd = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
            val rnd = PdfRenderer(pfd); val page = rnd.openPage(0)
            val bmpW = 320; val bmpH = (page.height.toFloat() / page.width.toFloat() * bmpW).toInt().coerceAtLeast(1)
            val bmp  = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); rnd.close(); pfd.close(); bmp
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // VAULT -- nested folder display
    // -------------------------------------------------------

    private fun renderVault() {
        fileListContainer.addView(buildSortViewRow())
        val saved    = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats = allFileEntities.groupBy { it.category }
        // Only top-level (no "/" in name)
        val topLevel = (fileCats.keys + saved).filter { it.isNotEmpty() && !it.contains("/") }.distinct().sorted()
        fileListContainer.addView(TextView(this).apply {
            text = "+ New Folder"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c_pri)
            gravity = Gravity.END; setPadding(0, 0, dp(16), dp(8)); setOnClickListener { showCreateFolderDialog() }
        })
        if (topLevel.isEmpty()) { tvEmpty.text = "No folders -- tap New Folder"; tvEmpty.visibility = View.VISIBLE; return }
        tvEmpty.visibility = View.GONE
        topLevel.forEach { cat ->
            val allInCat = fileCats.entries.filter { it.key == cat || it.key.startsWith("$cat/") }.flatMap { it.value }
            fileListContainer.addView(buildVaultCard(cat, allInCat.size, 0))
            if (expandedCategories.contains(cat)) {
                // Show sub-folders
                val subFolders = (fileCats.keys + saved).filter { it.startsWith("$cat/") && it.removePrefix("$cat/").isNotEmpty() && !it.removePrefix("$cat/").contains("/") }.distinct().sorted()
                subFolders.forEach { sub ->
                    val subFiles = fileCats[sub] ?: emptyList()
                    fileListContainer.addView(buildVaultCard(sub, subFiles.size, 1))
                    if (expandedCategories.contains(sub)) {
                        sortedFilesForCat(subFiles).forEach { f -> fileListContainer.addView(buildFileTileRow(f).also { row -> (row.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(40) }) }
                    }
                }
                // Direct files in this category (not sub-folders)
                val directFiles = fileCats[cat] ?: emptyList()
                sortedFilesForCat(directFiles).forEach { f -> fileListContainer.addView(buildFileTileRow(f).also { row -> (row.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(20) }) }
            }
        }
    }

    private fun buildVaultCard(category: String, count: Int, depth: Int): LinearLayout {
        val displayName  = if (depth > 0) category.substringAfterLast("/") else category
        val folderLetter = displayName.firstOrNull()?.uppercase() ?: "F"
        val isExpanded   = expandedCategories.contains(category)
        val indent       = dp(depth * 20)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14) + indent, dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            background = GradientDrawable().apply { setColor(Color.parseColor(card())); cornerRadius = dp(12).toFloat() }
            elevation = dp(if (depth == 0) 2 else 1).toFloat()
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)); text = folderLetter
                gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c_acc)
                background = GradientDrawable().apply { setColor(Color.argb(26, 255, 214, 10)); cornerRadius = dp(6).toFloat() }
            })
            val tc = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) } }
            tc.addView(TextView(this@MainActivity).apply { text = displayName; textSize = if (depth == 0) 14f else 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt1())) })
            tc.addView(TextView(this@MainActivity).apply { text = "$count DOCUMENTS"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt2())) })
            addView(tc)
            addView(TextView(this@MainActivity).apply { text = if (isExpanded) "\u25bc" else "\u25b6"; textSize = 13f; setTextColor(Color.parseColor(txt2())) })
            setOnClickListener {
                if (isExpanded) expandedCategories.remove(category) else { expandedCategories.add(category); catDetailName = category }
                rebuildFileList()
            }
            setOnLongClickListener { showCategoryContextMenu(category); true }
        }
    }

    // -------------------------------------------------------
    // DIALOGS
    // -------------------------------------------------------

    private fun showCategoryContextMenu(cat: String) {
        AlertDialog.Builder(this).setTitle(cat).setItems(arrayOf("Add Sub-folder","Rename","Delete","Move Files Here")) { _, w ->
            when (w) { 0 -> showAddSubCat(cat); 1 -> showRenameCat(cat); 2 -> confirmDeleteCat(cat); 3 -> showMoveAllDialog(cat) }
        }.show()
    }

    private fun showAddSubCat(parent: String) {
        val et = EditText(this).apply { hint = "Sub-folder name" }
        AlertDialog.Builder(this).setTitle("New sub-folder under \"${parent.substringAfterLast("/")}\"").setView(et)
            .setPositiveButton("Add") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { val s = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet(); s.add("$parent/$n"); prefs.edit().putStringSet("user_categories", s).apply(); rebuildFileList() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showRenameCat(cat: String) {
        val displayName = cat.substringAfterLast("/")
        val et = EditText(this).apply { setText(displayName) }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n != displayName) {
                    val prefix = if (cat.contains("/")) cat.substringBeforeLast("/") + "/" else ""
                    val newFull = prefix + n
                    val s = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet()
                    val updated = s.map { if (it == cat) newFull else if (it.startsWith("$cat/")) newFull + it.substring(cat.length) else it }.toMutableSet()
                    prefs.edit().putStringSet("user_categories", updated).apply()
                    lifecycleScope.launch(Dispatchers.IO) {
                        allFileEntities.filter { it.category == cat || it.category.startsWith("$cat/") }.forEach { db.recentFilesDao().setCategory(it.uri, newFull) }
                        withContext(Dispatchers.Main) { rebuildFileList() }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeleteCat(cat: String) {
        AlertDialog.Builder(this).setTitle("Delete \"${cat.substringAfterLast("/")}\"?").setMessage("Files will move to General (Recent).")
            .setPositiveButton("Delete") { _, _ ->
                val s = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet()
                s.removeAll { it == cat || it.startsWith("$cat/") }; prefs.edit().putStringSet("user_categories", s).apply()
                lifecycleScope.launch(Dispatchers.IO) { allFileEntities.filter { it.category == cat || it.category.startsWith("$cat/") }.forEach { db.recentFilesDao().setCategory(it.uri, "") }; withContext(Dispatchers.Main) { rebuildFileList() } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showMoveAllDialog(targetCat: String) {
        val saved = (prefs.getStringSet("user_categories", emptySet()) ?: emptySet()).filter { !it.startsWith(targetCat) }.sorted()
        if (saved.isEmpty()) { toast("No other folders to move from"); return }
        AlertDialog.Builder(this).setTitle("Move all files from:").setItems(saved.toTypedArray()) { _, i ->
            val src = saved[i]
            lifecycleScope.launch(Dispatchers.IO) { allFileEntities.filter { it.category == src }.forEach { db.recentFilesDao().setCategory(it.uri, targetCat) }; withContext(Dispatchers.Main) { rebuildFileList(); toast("Moved files to ${targetCat.substringAfterLast("/")}") } }
        }.show()
    }

    private fun showCreateFolderDialog() {
        val et = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New Folder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { val s = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet(); s.add(n); prefs.edit().putStringSet("user_categories", s).apply(); rebuildFileList() }
            }.show()
    }

    private fun showMoveToCategoryDialog(f: RecentFileEntity) {
        val saved = (prefs.getStringSet("user_categories", emptySet()) ?: emptySet()).sorted()
        if (saved.isEmpty()) { toast("No folders -- create one in Vault tab"); return }
        // Show display names (last segment) but use full path
        val display = saved.map { it.replace("/", " / ") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Move to Folder").setItems(display) { _, i ->
            lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, saved[i]) }
        }.show()
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val ops = arrayOf("Open", if (f.isFavourite) "Unstar" else "Star", "Move to Category", "Rename", "Delete", "Share", "File Info")
        AlertDialog.Builder(this).setTitle(f.displayName).setItems(ops) { _, w ->
            when (w) {
                0 -> openUri(Uri.parse(f.uri))
                1 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
                2 -> showMoveToCategoryDialog(f)
                3 -> showRenameDialog(f)
                4 -> AlertDialog.Builder(this).setTitle("Delete?").setMessage("Remove \"${f.displayName}\" from recent list?")
                        .setPositiveButton("Remove") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) } }
                        .setNegativeButton("Cancel", null).show()
                5 -> shareFile(f)
                6 -> showFileInfo(f)
            }
        }.show()
    }

    private fun showRenameDialog(f: RecentFileEntity) {
        val et = EditText(this).apply { setText(f.displayName.removeSuffix(".pdf")) }
        AlertDialog.Builder(this).setTitle("Rename").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    val newName = if (n.endsWith(".pdf")) n else "$n.pdf"
                    lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().insert(f.copy(displayName = newName)); withContext(Dispatchers.Main) { toast("Renamed to $newName") } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFileInfo(f: RecentFileEntity) {
        val bmPrefs = getSharedPreferences("propdf_bookmarks", MODE_PRIVATE)
        val bms     = (bmPrefs.getStringSet(f.uri.hashCode().toString(), emptySet()) ?: emptySet()).size
        val info    = "Name: ${f.displayName}\nSize: ${formatSize(f.fileSizeBytes)}\nOpened: ${relativeTime(f.lastOpenedAt)}\nCategory: ${if (f.category.isEmpty()) "None" else f.category}\nStarred: ${f.isFavourite}\nBookmarked pages: $bms"
        AlertDialog.Builder(this).setTitle("File Info").setMessage(info).setPositiveButton("OK", null).show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Search files..." }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase(Locale.getDefault())
                fileListContainer.removeAllViews()
                if (q.isEmpty()) { rebuildFileList(); return }
                val results = allFileEntities.filter { it.displayName.lowercase().contains(q) }
                if (results.isEmpty()) { tvEmpty.text = "No files matching \"$q\""; tvEmpty.visibility = View.VISIBLE }
                else { tvEmpty.visibility = View.GONE; results.forEach { fileListContainer.addView(buildFileCard(it)) } }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        AlertDialog.Builder(this)
            .setTitle("Search Files")
            .setView(et)
            .setPositiveButton("Find") { _, _ ->
                val q = et.text.toString().trim().lowercase(Locale.getDefault())
                fileListContainer.removeAllViews()
                if (q.isEmpty()) {
                    rebuildFileList()
                } else {
                    val results = allFileEntities.filter {
                        it.displayName.lowercase(Locale.getDefault()).contains(q)
                    }
                    if (results.isEmpty()) {
                        tvEmpty.text = "No files matching \"$q\""
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        tvEmpty.visibility = View.GONE
                        results.forEach { fileListContainer.addView(buildFileCard(it)) }
                    }
                }
            }
            .setNegativeButton("Close") { _, _ -> rebuildFileList() }
            .show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("Clear Recent Files (keep starred/categorized)", "Delete ALL Files", "About")) { _, i ->
                when (i) {
                    // FIX: clearRecentOnly() preserves starred and categorized files
                    0 -> AlertDialog.Builder(this).setTitle("Clear Recent?").setMessage("Removes only recent non-starred and non-bookmarked files. Starred, bookmarked, and categorized files are kept.")
                            .setPositiveButton("Clear") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { clearRecentPreservingPinned() } }
                            .setNegativeButton("Cancel", null).show()
                    1 -> AlertDialog.Builder(this).setTitle("Delete ALL?").setMessage("This removes every file from the list including starred and categorized.")
                            .setPositiveButton("Delete All") { _, _ -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() } }
                            .setNegativeButton("Cancel", null).show()
                    2 -> AlertDialog.Builder(this).setTitle("ProPDF Editor v4.2").setMessage("Production-grade PDF editor.\nAnnotation engine: data-driven.\nStorage: Room + MediaStore.\nBuild: Codemagic CI/CD.").setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun showOcrDialog() {
        val options = arrayOf("OCR from Camera (Scan)", "OCR from Gallery Image", "OCR from open PDF", "Extract text from last opened", "Open Tools (Merge/Split/Compress)")
        AlertDialog.Builder(this).setTitle("OCR -- Text Recognition").setItems(options) { _, w ->
            when (w) {
                0 -> startActivity(Intent(this, DocumentScannerActivity::class.java))
                1 -> ocrPicker.launch("image/*")
                2 -> toast("Open a PDF first, then tap the OCR icon in the viewer")
                3 -> { val last = allFileEntities.maxByOrNull { it.lastOpenedAt }; if (last != null) openUri(Uri.parse(last.uri)) else toast("No recent files") }
                4 -> startActivity(Intent(this, ToolsActivity::class.java))
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun shareFile(f: RecentFileEntity) {
        try {
            val file = FileHelper.uriToFile(this, Uri.parse(f.uri)) ?: return
            val uri2 = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri2); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share PDF"))
        } catch (_: Exception) { toast("Cannot share") }
    }

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            val name = FileHelper.getFileName(this@MainActivity, uri) ?: "document.pdf"
            val size = try { contentResolver.query(uri, null, null, null, null)?.use { c -> val col = c.getColumnIndex(android.provider.OpenableColumns.SIZE); if (c.moveToFirst() && col >= 0) c.getLong(col) else 0L } ?: 0L } catch (_: Exception) { 0L }
            withContext(Dispatchers.IO) { db.recentFilesDao().insert(RecentFileEntity(uri = uri.toString(), displayName = name, fileSizeBytes = size)) }
            ViewerActivity.start(this@MainActivity, uri, displayName = name)
        }
    }

    private fun formatSize(bytes: Long) = when { bytes > 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0); bytes > 1024 -> "%.0f KB".format(bytes / 1024.0); else -> "$bytes B" }
    private fun relativeTime(ts: Long): String = android.text.format.DateUtils.getRelativeTimeSpanString(ts).toString()
    private fun hdrBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply { setImageResource(icon); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.parseColor(txt2())); layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); setOnClickListener { action() } }
    private suspend fun clearRecentPreservingPinned() {
        val bmPrefs = getSharedPreferences("propdf_bookmarks", MODE_PRIVATE)
        allFileEntities.forEach { entity ->
            val bookmarkKey = entity.uri.hashCode().toString()
            val hasBookmarks = (bmPrefs.getStringSet(bookmarkKey, emptySet()) ?: emptySet()).isNotEmpty()
            val pinned = entity.isFavourite || entity.category.isNotBlank() || hasBookmarks
            if (!pinned) db.recentFilesDao().delete(entity.uri)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
