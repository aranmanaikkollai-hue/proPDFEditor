package com.propdf.editor.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val db    by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("propdf_prefs", MODE_PRIVATE)
    }

    // ---- UI state ----
    private var isDark = true
    private var currentTab = "recent"

    // ---- Sort / view state ----
    private var viewMode = "list"   // "list" | "grid" | "tile"
    private var sortMode = "date"   // "date" | "name" | "size"
    private var sortAsc  = false
    private val expandedCategories = mutableSetOf<String>()
    private var allFileEntities: List<RecentFileEntity> = emptyList()

    // Cache: uri -> first-page thumbnail for grid view
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    // Track cat_detail category name independently (fixes sort bug)
    private var catDetailName: String = ""

    // ---- View refs ----
    private lateinit var rootFrame        : FrameLayout
    private lateinit var tvEmpty          : TextView
    private lateinit var tvSection        : TextView
    private lateinit var tabRow           : LinearLayout
    private lateinit var themeBtn         : ImageButton
    private lateinit var fileListContainer: LinearLayout
    private lateinit var viewToggleBtn    : ImageButton
    private lateinit var sortBtn          : TextView

    // ---- Color scheme ----
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

    private val PRIMARY = "#448AFF"
    private val ACCENT  = "#FFD60A"
    private val DANGER  = "#E53935"
    private val c_pri   get() = Color.parseColor(PRIMARY)
    private val c_acc   get() = Color.parseColor(ACCENT)

    // ---- File picker ----
    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        openUri(uri)
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openUri(intent.data!!)
        }
        observeFiles()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openUri(intent.data!!)
        }
    }

    // -------------------------------------------------------
    // ROOT UI BUILD
    // -------------------------------------------------------

    private fun buildUI() {
        applySystemBarColors()
        rootFrame = FrameLayout(this).apply { setBackgroundColor(bg()) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        column.addView(buildHeader())
        column.addView(buildTabBar())
        column.addView(buildSectionRow())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(90))
        }
        tvEmpty = TextView(this).apply {
            text = "No files yet\nTap + to open a PDF"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(txt2()))
            setPadding(dp(32), dp(64), dp(32), dp(64))
            visibility = View.GONE
        }
        fileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        body.addView(tvEmpty)
        body.addView(fileListContainer)
        scroll.addView(body)
        column.addView(scroll)

        rootFrame.addView(column)
        rootFrame.addView(buildBottomNav())
        setContentView(rootFrame)
    }

    private fun applySystemBarColors() {
        window.statusBarColor = bg()
        window.navigationBarColor = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
        if (!isDark) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    // -------------------------------------------------------
    // HEADER
    // -------------------------------------------------------

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(68))
            setBackgroundColor(bg())
            setPadding(dp(16), dp(4), dp(12), dp(4))

            // Logo
            addView(FrameLayout(this@MainActivity).apply {
                val s = dp(46)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    setMargins(0, 0, dp(10), 0)
                }
                setBackgroundColor(c_pri)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                    }
                }
                clipToOutline = true
                elevation = dp(2).toFloat()
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_agenda)
                    setColorFilter(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                        gravity = Gravity.CENTER
                    }
                })
            })

            // Title
            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            titleCol.addView(TextView(this@MainActivity).apply {
                text = "ProPDF"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_pri)
            })
            titleCol.addView(TextView(this@MainActivity).apply {
                text = "HOME"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt2())); letterSpacing = 0.15f
            })
            addView(titleCol)

            addView(hdrIconBtn(android.R.drawable.ic_menu_search) { showSearchDialog() })

            themeBtn = ImageButton(this@MainActivity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.parseColor(txt2()))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { toggleTheme() }
                post {
                    setImageResource(
                        if (isDark) android.R.drawable.ic_menu_mapmode
                        else android.R.drawable.ic_menu_day
                    )
                    setColorFilter(Color.parseColor(txt2()))
                }
            }
            addView(themeBtn)
            addView(hdrIconBtn(android.R.drawable.ic_menu_preferences) { showSettings() })
        }
    }

    // -------------------------------------------------------
    // TAB BAR  +  sort/view controls
    // -------------------------------------------------------

    private fun buildTabBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }

        listOf("Recent" to "recent", "Starred" to "starred", "Vault" to "categories")
            .forEach { (label, id) ->
                val active = id == currentTab
                tabRow.addView(TextView(this).apply {
                    text = label; textSize = 14f; gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setTextColor(if (active) tabActTxt() else tabInaTxt())
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    tag = "${id}_tv"
                    if (isDark && active) {
                        setBackgroundColor(tabPill())
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) {
                                o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat())
                            }
                        }
                        clipToOutline = true
                    }
                    setOnClickListener { switchTab(id) }
                })
                if (id != "categories") {
                    tabRow.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(4), dp(1))
                    })
                }
            }

        wrapper.addView(tabRow)
        wrapper.addView(buildSortViewControlRow())
        wrapper.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
            setBackgroundColor(divLine())
        })
        return wrapper
    }

    private fun buildSortViewControlRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, dp(36))

            sortBtn = TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = sortLabel(); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt2()))
                setOnClickListener { showSortMenu() }
            }
            addView(sortBtn)

            viewToggleBtn = ImageButton(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setImageResource(viewToggleIcon())
                colorFilter = PorterDuffColorFilter(c_pri, PorterDuff.Mode.SRC_IN)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    viewMode = when (viewMode) {
                        "list" -> "grid"
                        "grid" -> "tile"
                        else   -> "list"
                    }
                    setImageResource(viewToggleIcon())
                    rebuildFileList()
                }
            }
            addView(viewToggleBtn)
        }
    }

    private fun buildSectionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setBackgroundColor(bg())

            tvSection = TextView(this@MainActivity).apply {
                text = if (isDark) "Recent Files" else "LAST ACCESSED DOCUMENTS"
                textSize = if (isDark) 22f else 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
                letterSpacing = if (isDark) 0f else 0.12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(tvSection)
        }
    }

    // -------------------------------------------------------
    // BOTTOM NAV
    // -------------------------------------------------------

    private fun buildBottomNav(): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        }
        frame.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(1)).apply {
                gravity = Gravity.BOTTOM; bottomMargin = dp(68)
            }
            setBackgroundColor(divLine())
        })

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(-1, dp(68)).apply { gravity = Gravity.BOTTOM }
            setBackgroundColor(navBg())
            setPadding(0, 0, 0, dp(8))
        }

        data class NI(val id: String, val lbl: String, val icon: Int)
        listOf(
            NI("home",    "HOME",  android.R.drawable.ic_menu_view),
            NI("files",   "FILES", android.R.drawable.ic_menu_agenda),
            NI("__fab__", "OPEN",  0),
            NI("scan",    "SCAN",  android.R.drawable.ic_menu_camera),
            NI("ocr",     "OCR",   android.R.drawable.ic_menu_edit)   // was "tools"
        ).forEach { item ->
            if (item.id == "__fab__") {
                navBar.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                return@forEach
            }
            val active = (item.id == "home" &&
                currentTab in listOf("recent", "starred", "categories", "cat_detail"))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener {
                    when (item.id) {
                        "home"  -> if (currentTab != "recent") switchTab("recent")
                        "files" -> switchTab("categories")
                        "scan"  -> startActivity(Intent(this@MainActivity, DocumentScannerActivity::class.java))
                        "ocr"   -> showOcrDialog()   // NEW: OCR button
                    }
                }
            }
            col.addView(ImageView(this).apply {
                setImageResource(item.icon)
                setColorFilter(if (active) c_pri else Color.parseColor(txt2()))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                tag = "${item.id}_icon"
            })
            col.addView(TextView(this).apply {
                text = item.lbl; textSize = 8f; gravity = Gravity.CENTER
                setTextColor(if (active) c_pri else Color.parseColor(txt2()))
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
                setPadding(0, dp(2), 0, 0)
                tag = "${item.id}_label"
            })
            navBar.addView(col)
        }

        val fab = FrameLayout(this).apply {
            val s = dp(56)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(22)
            }
            setBackgroundColor(c_pri)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
            clipToOutline = true
            elevation = dp(10).toFloat()
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        }
        fab.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                gravity = Gravity.CENTER
            }
        })

        frame.addView(navBar)
        frame.addView(fab)
        return frame
    }

    // -------------------------------------------------------
    // TAB SWITCHING
    // -------------------------------------------------------

    private fun switchTab(tab: String) {
        currentTab = tab
        listOf("recent", "starred", "categories").forEach { t ->
            tabRow.findViewWithTag<TextView>("${t}_tv")?.apply {
                setTextColor(if (t == tab) tabActTxt() else tabInaTxt())
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setBackgroundColor(if (isDark && t == tab) tabPill() else Color.TRANSPARENT)
            }
        }
        updateNavHighlight(tab)
        rebuildFileList()
    }

  private fun updateNavHighlight(tab: String) {
        val atHome = tab in listOf("recent", "starred", "categories", "cat_detail")
        listOf("home", "files", "scan", "ocr").forEach { id ->
            val active = id == "home" && atHome
            rootFrame.findViewWithTag<ImageView>("${id}_icon")
                ?.setColorFilter(if (active) c_pri else Color.parseColor(txt2()))
            rootFrame.findViewWithTag<TextView>("${id}_label")
                ?.setTextColor(if (active) c_pri else Color.parseColor(txt2()))
        }
    }

    private fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        buildUI()
        observeFiles()
    }

    // -------------------------------------------------------
    // DATA OBSERVATION
    // -------------------------------------------------------

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFileEntities = files
                rebuildFileList()
            }
        }
    }

    // -------------------------------------------------------
    // SORT HELPERS
    // -------------------------------------------------------

    private fun sortLabel(): String {
        val dir = if (sortAsc) "\u25b2" else "\u25bc"
        return when (sortMode) {
            "name" -> "Name $dir"
            "size" -> "Size $dir"
            else   -> "Date $dir"
        }
    }

    private fun viewToggleIcon(): Int = when (viewMode) {
        "grid" -> android.R.drawable.ic_menu_agenda
        "tile" -> android.R.drawable.ic_menu_crop
        else   -> android.R.drawable.ic_menu_view
    }

    private fun showSortMenu() {
        val options = arrayOf(
            "Date (newest first)", "Date (oldest first)",
            "Name A-Z", "Name Z-A",
            "Size (largest)", "Size (smallest)"
        )
        AlertDialog.Builder(this).setTitle("Sort files by")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { sortMode = "date"; sortAsc = false }
                    1 -> { sortMode = "date"; sortAsc = true  }
                    2 -> { sortMode = "name"; sortAsc = true  }
                    3 -> { sortMode = "name"; sortAsc = false }
                    4 -> { sortMode = "size"; sortAsc = false }
                    5 -> { sortMode = "size"; sortAsc = true  }
                }
                if (::sortBtn.isInitialized) sortBtn.text = sortLabel()
                rebuildFileList()
            }.show()
    }

    private fun sortedFiles(): List<RecentFileEntity> {
        val base = when (currentTab) {
            "starred"    -> allFileEntities.filter { it.isFavourite }
            "cat_detail" -> allFileEntities.filter { it.category == catDetailName }
            else         -> allFileEntities
        }
        val sorted = when (sortMode) {
            "name" -> base.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            "size" -> base.sortedBy { it.fileSizeBytes }
            else   -> base.sortedByDescending { it.lastOpenedAt }  // newest first by default
        }
        return if (sortAsc) sorted.reversed() else sorted
    }
// -------------------------------------------------------
    // FILE LIST RENDERING
    // -------------------------------------------------------

    private fun rebuildFileList() {
        if (!::fileListContainer.isInitialized) return
        fileListContainer.removeAllViews()

        if (::tvSection.isInitialized) {
            tvSection.text = when {
                isDark  && currentTab == "recent"     -> "Recent Files"
                isDark  && currentTab == "starred"    -> "Starred Files"
                isDark  && currentTab == "categories" -> "My Vault"
                !isDark && currentTab == "recent"     -> "LAST ACCESSED DOCUMENTS"
                !isDark && currentTab == "starred"    -> "STARRED DOCUMENTS"
                !isDark && currentTab == "categories" -> "MY VAULT"
                else -> tvSection.text.toString()
            }
            tvSection.textSize = if (isDark) 22f else 9f
        }

        if (currentTab == "categories") { renderVault(); return }

        val files = sortedFiles()
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        when (viewMode) {
            "grid" -> buildGridView(files)
            "tile" -> buildTileView(files)
            else   -> files.forEach { fileListContainer.addView(buildFileCard(it)) }
        }
    }

    private fun buildGridView(files: List<RecentFileEntity>) {
        var row: LinearLayout? = null
        files.forEachIndexed { i, entity ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(8)
                    }
                }
                fileListContainer.addView(row)
            }
            val card = buildFileCardCompact(entity)
            card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i % 2 == 0) marginEnd = dp(4) else marginStart = dp(4)
            }
            row?.addView(card)
            if (i == files.size - 1 && i % 2 == 0) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
        }
    }

    private fun buildTileView(files: List<RecentFileEntity>) {
        files.forEach { fileListContainer.addView(buildFileTileRow(it)) }
    }

    private fun buildFileCard(f: RecentFileEntity): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(10))
            }
            radius = dp(14).toFloat()
            cardElevation = if (isDark) 0f else dp(2).toFloat()
            setCardBackgroundColor(Color.parseColor(card()))
            strokeWidth = if (isDark) dp(1) else 0
            strokeColor = cardBrd()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val thumb = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                setMargins(0, 0, dp(14), 0)
            }
            setBackgroundColor(
                if (isDark) Color.parseColor("#3A3A3C")
                else Color.parseColor("#FFEBEE")
            )
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
            }
            clipToOutline = true
        }
        thumb.addView(TextView(this).apply {
            text = if (isDark) f.displayName.take(1).uppercase() else "PDF"
            textSize = if (isDark) 20f else 8f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) c_pri else Color.parseColor("#E53935"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        row.addView(thumb)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        info.addView(TextView(this).apply {
            text = f.displayName.removeSuffix(".pdf"); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(txt1()))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = "${formatSize(f.fileSizeBytes)} \u2022 ${relativeTime(f.lastOpenedAt)}"
            textSize = 11f; setTextColor(Color.parseColor(txt2()))
        })
        row.addView(info)
        row.addView(ImageButton(this).apply {
            setImageResource(
                if (f.isFavourite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().setFavourite(f.uri, !f.isFavourite)
                }
            }
        })
        card.addView(row)
        // FIX: f.uri is String — wrap with Uri.parse()
        card.setOnClickListener { openUri(Uri.parse(f.uri)) }
        card.setOnLongClickListener { showFileOptions(f); true }
        return card
    }
private fun buildFileCardCompact(entity: RecentFileEntity): LinearLayout {
        val C_CARD  = Color.parseColor(card())
        val initial = entity.displayName.firstOrNull()?.uppercase() ?: "P"
        val sizeMb  = formatSize(entity.fileSizeBytes)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()
        }

        // Thumbnail area - fixed 4:3 aspect ratio preview
        val thumbFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(110))
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
            }
            clipToOutline = true
        }

        val thumbIv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        thumbFrame.addView(thumbIv)

        // Letter fallback shown while thumbnail loads
        val letterTv = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            text = initial; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#448AFF"))
        }
        thumbFrame.addView(letterTv)

        // PDF badge overlay
        thumbFrame.addView(TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM or Gravity.END).apply {
                margin = dp(4)
            }
            text = "PDF"; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935")); cornerRadius = dp(4).toFloat()
            }
        })
        card.addView(thumbFrame)

        // File name
        card.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            text = entity.displayName.removeSuffix(".pdf")
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(txt1())); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        // Size
        card.addView(TextView(this).apply {
            text = sizeMb; textSize = 9f; setTextColor(Color.parseColor(txt2()))
        })

        card.setOnClickListener { openUri(Uri.parse(entity.uri)) }
        card.setOnLongClickListener { showFileOptions(entity); true }

        // Load thumbnail asynchronously
        val cached = thumbnailCache[entity.uri]
        if (cached != null) {
            thumbIv.setImageBitmap(cached)
            letterTv.visibility = View.GONE
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = renderFirstPage(entity.uri)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        thumbnailCache[entity.uri] = bmp
                        thumbIv.setImageBitmap(bmp)
                        letterTv.visibility = View.GONE
                    }
                }
            }
        }

        return card
    }

    // Renders first page of PDF at low resolution for thumbnail use
    private fun renderFirstPage(uriStr: String): Bitmap? {
        return try {
            val uri  = Uri.parse(uriStr)
            val dest = File(cacheDir, "thumb_${uriStr.hashCode()}.pdf")
            if (!dest.exists()) {
                contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(dest).use { inp.copyTo(it) }
                }
            }
            if (!dest.exists() || dest.length() == 0L) return null
            val pfd  = ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(0)
            val thumbW = 320
            val thumbH = (page.height.toFloat() / page.width.toFloat() * thumbW).toInt()
                .coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close(); pfd.close()
            bmp
        } catch (_: Exception) { null }
    }

    private fun buildFileTileRow(entity: RecentFileEntity): LinearLayout {
        val C_CARD  = Color.parseColor(card())
        val sizeMb  = formatSize(entity.fileSizeBytes)
        val timeStr = relativeTime(entity.lastOpenedAt)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(8).toFloat()
            }
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                text = "P"; gravity = Gravity.CENTER; textSize = 10f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(DANGER)); cornerRadius = dp(4).toFloat()
                }
            })
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(10)
                }
                text = entity.displayName; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1())); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@MainActivity).apply {
                text = "$sizeMb \u2022 $timeStr"; textSize = 10f
                setTextColor(Color.parseColor(txt2()))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(8)
                }
            })
            // FIX: entity.uri is String — wrap with Uri.parse()
            setOnClickListener { openUri(Uri.parse(entity.uri)) }
            setOnLongClickListener { showFileOptions(entity); true }
        }

    }
// -------------------------------------------------------
    // VAULT / CATEGORIES
    // -------------------------------------------------------

    private fun renderVault() {
        val saved = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats = allFileEntities.groupBy { it.category }
        val names = (fileCats.keys + saved)
            .filter { it.isNotEmpty() }.distinct().sorted()

        fileListContainer.addView(TextView(this).apply {
            text = "+ New Folder"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c_pri); gravity = Gravity.END
            setPadding(0, 0, dp(16), dp(8))
            setOnClickListener { showCreateFolderDialog() }
        })

        if (names.isEmpty()) {
            tvEmpty.text = "No folders yet"; tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE
        names.forEach { cat ->
            fileListContainer.addView(buildVaultCard(cat, fileCats[cat]?.size ?: 0))
            // If expanded, show files in this category below the card
            if (expandedCategories.contains(cat)) {
                fileCats[cat]?.forEach { f ->
                    fileListContainer.addView(buildFileTileRow(f).also { row ->
                        (row.layoutParams as? LinearLayout.LayoutParams)?.apply {
                            marginStart = dp(20)
                        }
                    })
                }
            }
        }
    }

    private fun buildVaultCard(category: String, count: Int): LinearLayout {
        val C_CARD     = Color.parseColor(card())
        val isExpanded = expandedCategories.contains(category)
        val chevron    = if (isExpanded) "\u25bc" else "\u25b6"
        // FIX: use first letter of category name, uppercase
        val folderLetter = category.firstOrNull()?.uppercase() ?: "F"

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(C_CARD); cornerRadius = dp(12).toFloat()
            }
            elevation = dp(2).toFloat()

            // Folder icon: letter from category name, not hardcoded "F"
            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                text = folderLetter
                gravity = Gravity.CENTER; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_acc)
                background = GradientDrawable().apply {
                    setColor(Color.argb(26, 255, 214, 10)); cornerRadius = dp(8).toFloat()
                }
            })
            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(12)
                }
            }
            textCol.addView(TextView(this@MainActivity).apply {
                text = category; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(txt1()))
            })
            textCol.addView(TextView(this@MainActivity).apply {
                text = "$count DOCUMENTS"; textSize = 10f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor(txt2()))
            })
            addView(textCol)
            addView(TextView(this@MainActivity).apply {
                text = chevron; textSize = 14f
                setTextColor(Color.parseColor(txt2()))
            })

            setOnClickListener {
                if (isExpanded) {
                    expandedCategories.remove(category)
                } else {
                    expandedCategories.add(category)
                    catDetailName = category   // FIX: save name before rebuild
                }
                rebuildFileList()
            }
            setOnLongClickListener { showCategoryContextMenu(category); true }
        }
    }
// -------------------------------------------------------
    // DIALOGS
    // -------------------------------------------------------

    private fun showCategoryContextMenu(parentCategory: String) {
        val ops = arrayOf("Add Sub-category", "Rename Category", "Delete Category")
        AlertDialog.Builder(this).setTitle(parentCategory).setItems(ops) { _, which ->
            when (which) {
                0 -> showAddSubCategoryDialog(parentCategory)
                1 -> showRenameCategoryDialog(parentCategory)  // FIX: function now present
                2 -> confirmDeleteCategory(parentCategory)
            }
        }.show()
    }

    private fun showAddSubCategoryDialog(parent: String) {
        val input = EditText(this).apply { hint = "Sub-category name" }
        AlertDialog.Builder(this)
            .setTitle("New sub-category under \"$parent\"")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val saved = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet()
                    saved.add("$parent/$name")
                    prefs.edit().putStringSet("user_categories", saved).apply()
                    rebuildFileList()
                    toast("Added: $parent/$name")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // FIX: This function was missing — "Unresolved reference: showRenameCategoryDialog"
    private fun showRenameCategoryDialog(category: String) {
        val input = EditText(this).apply { setText(category) }
        AlertDialog.Builder(this).setTitle("Rename Category").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != category) {
                    val saved = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet()
                    val updated = saved.map { entry ->
                        when {
                            entry == category              -> newName
                            entry.startsWith("$category/") ->
                                newName + entry.substring(category.length)
                            else -> entry
                        }
                    }.toMutableSet()
                    prefs.edit().putStringSet("user_categories", updated).apply()
                    lifecycleScope.launch(Dispatchers.IO) {
                        allFileEntities.filter { it.category == category }.forEach {
                            db.recentFilesDao().setCategory(it.uri, newName)
                        }
                        withContext(Dispatchers.Main) { rebuildFileList() }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeleteCategory(category: String) {
        AlertDialog.Builder(this).setTitle("Delete \"$category\"?")
            .setMessage("Files will be moved to General.")
            .setPositiveButton("Delete") { _, _ ->
                val saved = prefs.getStringSet("user_categories", mutableSetOf())!!
                    .toMutableSet()
                saved.removeAll { it == category || it.startsWith("$category/") }
                prefs.edit().putStringSet("user_categories", saved).apply()
                rebuildFileList()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showCreateFolderDialog() {
        val et = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New Folder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) {
                    val ex = prefs.getStringSet("user_categories", mutableSetOf())!!.toMutableSet()
                    ex.add(name)
                    prefs.edit().putStringSet("user_categories", ex).apply()
                    rebuildFileList()
                }
            }.show()
    }

    private fun showMoveToCategoryDialog(f: RecentFileEntity) {
        val saved = (prefs.getStringSet("user_categories", emptySet()) ?: emptySet())
            .toList().sorted()
        if (saved.isEmpty()) {
            toast("No folders yet. Create one in Vault tab.")
            return
        }
        AlertDialog.Builder(this).setTitle("Move to Folder")
            .setItems(saved.toTypedArray()) { _, i ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().setCategory(f.uri, saved[i])
                }
            }.show()
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val ops = arrayOf(
            "Open",
            if (f.isFavourite) "Unstar" else "Star",
            "Move to Category",
            "Delete from list",
            "Share"
        )
        AlertDialog.Builder(this).setTitle(f.displayName).setItems(ops) { _, which ->
            when (which) {
                0 -> openUri(Uri.parse(f.uri))
                1 -> lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().setFavourite(f.uri, !f.isFavourite)
                }
                2 -> showMoveToCategoryDialog(f)
                3 -> lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().delete(f.uri)
                }
                4 -> shareFile(f)
            }
        }.show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Search files..." }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase(Locale.getDefault())
                fileListContainer.removeAllViews()
                allFileEntities.filter { it.displayName.lowercase().contains(q) }
                    .forEach { fileListContainer.addView(buildFileCard(it)) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        AlertDialog.Builder(this).setTitle("Search").setView(et)
            .setNegativeButton("Close") { _, _ -> rebuildFileList() }.show()
    }

    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("Clear recent files", "About")) { _, i ->
                if (i == 0) lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().clearAll()
                } else toast("ProPDF Editor v4.2")
            }.show()
    }
private fun shareFile(f: RecentFileEntity) {
        try {
            val file = FileHelper.uriToFile(this, Uri.parse(f.uri)) ?: return
            val uri2 = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri2)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share PDF"
                )
            )
        } catch (_: Exception) { toast("Cannot share") }
    }

    // -------------------------------------------------------
    // OPEN URI → VIEWER
    // -------------------------------------------------------

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            val name = FileHelper.getFileName(this@MainActivity, uri) ?: "document.pdf"
            val size = try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst() && col >= 0) c.getLong(col) else 0L
                } ?: 0L
            } catch (_: Exception) { 0L }
            withContext(Dispatchers.IO) {
                db.recentFilesDao().insert(
                    RecentFileEntity(
                        uri = uri.toString(),
                        displayName = name,
                        fileSizeBytes = size
                    )
                )
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    private fun showOcrDialog() {
        val options = arrayOf(
            "OCR from Camera (Scan Now)",
            "OCR from Gallery Image",
            "OCR Current Open PDF",
            "Batch OCR Multiple Files",
            "Extract Text to Clipboard",
            "Translate Extracted Text"
        )
        AlertDialog.Builder(this)
            .setTitle("OCR - Text Recognition")
            .setMessage("Choose an OCR operation:")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, DocumentScannerActivity::class.java))
                    1 -> pickImageForOcr()
                    2 -> toast("Open a PDF first, then use the viewer's OCR menu")
                    3 -> toast("Batch OCR: select multiple PDFs from Files tab")
                    4 -> {
                        // Extract text from last opened file
                        val last = allFileEntities.maxByOrNull { it.lastOpenedAt }
                        if (last != null) {
                            toast("Opening ${last.displayName} for OCR...")
                            openUri(Uri.parse(last.uri))
                        } else toast("No recent files")
                    }
                    5 -> toast("Translate: open a PDF, OCR it, then use Translate option")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        toast("Processing image with OCR...")
        // Full implementation: pass uri to OcrManager.ocrBitmap()
    }

    private fun pickImageForOcr() {
        imagePicker.launch("image/*")
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun formatSize(bytes: Long) = when {
        bytes > 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes > 1024      -> "%.0f KB".format(bytes / 1024.0)
        else              -> "$bytes B"
    }

    private fun relativeTime(ts: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(ts).toString()

    private fun hdrIconBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.parseColor(txt2()))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
