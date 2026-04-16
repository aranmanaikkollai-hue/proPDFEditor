package com.propdf.editor.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    // Add these variables at the top of MainActivity class
private var currentViewMode = 0 // 0: List, 1: Grid, 2: Tiles

private fun toggleDashboardView(recyclerView: RecyclerView) {
    currentViewMode = (currentViewMode + 1) % 3
    
    when (currentViewMode) {
        0 -> { // List View
            recyclerView.layoutManager = LinearLayoutManager(this)
            // Update button icon/text to "List"
        }
        1 -> { // Grid View
            recyclerView.layoutManager = GridLayoutManager(this, 2)
            // Update button icon/text to "Grid"
        }
        2 -> { // Tiles View (Larger Grid)
            recyclerView.layoutManager = GridLayoutManager(this, 3)
            // Update button icon/text to "Tiles"
        }
    }
    recyclerView.adapter?.notifyDataSetChanged()
}


    private val db   by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("propdf_prefs", MODE_PRIVATE) }

    private var isDark     = true   // default dark mode
    private var currentTab = "recent"
    private var allFiles   = listOf<RecentFileEntity>()

    private lateinit var rootFrame     : FrameLayout
    private lateinit var listContainer : LinearLayout
    private lateinit var tvEmpty       : TextView
    private lateinit var tvSection     : TextView
    private lateinit var tabRow        : LinearLayout
    private lateinit var themeBtn      : ImageButton

    // ?? Color scheme (switches between dark/light) ????????????????????
    // Dark  mode: bg #121212, card #2A2A2A, text white
    // Light mode: bg #F5F5F7, card #FFFFFF, text #1A1A1A

    private fun bg()       = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F7")
    private fun card()     = if (isDark) Color.parseColor("#2A2A2A") else Color.WHITE
    private fun cardBrd()  = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E8E8EC")
    private fun txt1()     = if (isDark) Color.WHITE               else Color.parseColor("#1A1A1A")
    private fun txt2()     = if (isDark) Color.parseColor("#A0A0A0") else Color.parseColor("#6B7280")
    private fun navBg()    = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun divLine()  = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun tabPill()  = if (isDark) Color.parseColor("#2C2C2E") else Color.TRANSPARENT
    private fun tabActTxt()= Color.parseColor("#448AFF")
    private fun tabInaTxt()= if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")

    // Fixed accent colours (same both modes)
    val PRIMARY  = "#448AFF"
    val ACCENT   = "#FFD60A"
    val DANGER   = "#E53935"
    val c_pri    = Color.parseColor(PRIMARY)
    val c_acc    = Color.parseColor(ACCENT)

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        openUri(uri)
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

    // ?? Build entire UI ???????????????????????????????????????????????
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
            text = "No files yet\nTap + Open to add a PDF"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(txt2())
            setPadding(dp(32), dp(64), dp(32), dp(64))
            visibility = View.GONE
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        body.addView(tvEmpty); body.addView(listContainer)
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

    // ?? Header (from screenshots: logo+title left, search/theme/settings right) ??
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(68))
            setBackgroundColor(bg())
            setPadding(dp(16), dp(4), dp(12), dp(4))

            // Blue rounded-square app icon
            addView(FrameLayout(this@MainActivity).apply {
                val s = dp(46)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0,0,dp(10),0) }
                setBackgroundColor(c_pri)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0,0,view.width,view.height, dp(12).toFloat())
                    }
                }
                clipToOutline = true
                elevation = dp(2).toFloat()
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_agenda)
                    setColorFilter(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
                })
            })

            // Title column: "ProPDF" + "HOME"
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
                setTextColor(txt2()); letterSpacing = 0.15f
            })
            addView(titleCol)

            // Search icon
            addView(hdrIconBtn(android.R.drawable.ic_menu_search) { showSearchDialog() })

            // Theme toggle: sun (dark mode) / moon (light mode)
            themeBtn = ImageButton(this@MainActivity).apply {
                setImageResource(
                    if (isDark) android.R.drawable.ic_menu_view   // sun = currently dark, click=light
                    else android.R.drawable.ic_menu_view           // moon = currently light, click=dark
                )
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(txt2())
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setOnClickListener { toggleTheme() }
                // Set drawable based on mode
                post {
                    setImageResource(
                        if (isDark) android.R.drawable.ic_menu_mapmode   // brightness icon
                        else android.R.drawable.ic_menu_day               // moon-like
                    )
                    setColorFilter(txt2())
                }
            }
            addView(themeBtn)

            // Settings icon
            addView(hdrIconBtn(android.R.drawable.ic_menu_preferences) { showSettings() })
        }
    }

    // ?? Tab bar (pill style dark / underline style light) ?????????????
    private fun buildTabBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
        }
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        listOf("Recent" to "recent", "Starred" to "starred", "Categories" to "categories")
            .forEach { (label, id) ->
                val active = id == currentTab
                val tv = TextView(this).apply {
                    text = label; textSize = 14f; gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setTextColor(if (active) tabActTxt() else tabInaTxt())
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    tag = "${id}_tv"
                    // Dark mode: pill bg on active; Light mode: no pill
                    if (isDark && active) {
                        setBackgroundColor(tabPill())
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) {
                                o.setRoundRect(0,0,v.width,v.height, dp(20).toFloat())
                            }
                        }
                        clipToOutline = true
                    }
                    setOnClickListener { switchTab(id) }
                }
                tabRow.addView(tv)
                // Add spacer between tabs
                if (id != "categories") {
                    tabRow.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(4), dp(1))
                    })
                }
            }

        wrapper.addView(tabRow)
        // Light mode: bottom border line
        wrapper.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
            setBackgroundColor(divLine())
        })
        return wrapper
    }

    // ?? Section header row ????????????????????????????????????????????
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
                setTextColor(txt1())
                letterSpacing = if (isDark) 0f else 0.12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(tvSection)

            // Version tag (dark) / Sort+view toggle (light)
            addView(TextView(this@MainActivity).apply {
                text = if (isDark) "EDITOR V4.2" else "SORT"
                textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(txt2()); letterSpacing = 0.1f
            })
        }
    }

    // ?? Bottom navigation with center FAB ?????????????????????????????
    // Design: HOME ? FILES ? [+FAB] ? SCAN ? TOOLS
    private fun buildBottomNav(): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        }
        // Top divider
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
        val navItems = listOf(
            NI("home",  "HOME",  android.R.drawable.ic_menu_view),
            NI("files", "FILES", android.R.drawable.ic_menu_agenda),
            NI("__fab__", "OPEN", 0),
            NI("scan",  "SCAN",  android.R.drawable.ic_menu_camera),
            NI("tools", "TOOLS", android.R.drawable.ic_menu_preferences)
        )

        navItems.forEach { item ->
            if (item.id == "__fab__") {
                // Empty slot for FAB
                navBar.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                return@forEach
            }
            val active = (item.id == "home" && currentTab in listOf("recent","starred","categories","cat_detail"))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener {
                    when (item.id) {
                        "home"  -> if (currentTab != "recent") switchTab("recent")
                        "files" -> switchTab("categories")
                        "scan"  -> startActivity(Intent(this@MainActivity, DocumentScannerActivity::class.java))
                        "tools" -> startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                    }
                }
            }
            col.addView(ImageView(this).apply {
                setImageResource(item.icon)
                setColorFilter(if (active) c_pri else txt2())
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                tag = "${item.id}_icon"
            })
            col.addView(TextView(this).apply {
                text = item.lbl; textSize = 8f; gravity = Gravity.CENTER
                setTextColor(if (active) c_pri else txt2())
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
                setPadding(0, dp(2), 0, 0)
                tag = "${item.id}_label"
            })
            navBar.addView(col)
        }

        // Center FAB (+) -- raised above nav bar
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
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER }
        })

        frame.addView(navBar)
        frame.addView(fab)
        return frame
    }

    // ?? Tab switching ?????????????????????????????????????????????????
    private fun switchTab(tab: String) {
        currentTab = tab
        listOf("recent","starred","categories").forEach { t ->
            tabRow.findViewWithTag<TextView>("${t}_tv")?.apply {
                setTextColor(if (t == tab) tabActTxt() else tabInaTxt())
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                // Pill background for dark mode active tab
                setBackgroundColor(if (isDark && t == tab) tabPill() else Color.TRANSPARENT)
            }
        }
        tvSection.apply {
            text = when {
                isDark && tab == "recent"     -> "Recent Files"
                isDark && tab == "starred"    -> "Starred Files"
                isDark && tab == "categories" -> "My Vault"
                !isDark && tab == "recent"    -> "LAST ACCESSED DOCUMENTS"
                !isDark && tab == "starred"   -> "STARRED DOCUMENTS"
                !isDark && tab == "categories"-> "MY VAULT"
                else -> "Files"
            }
            textSize = if (isDark) 22f else 9f
        }
        updateNavHighlight(tab)
        refreshDisplay()
    }

    private fun updateNavHighlight(tab: String) {
        val atHome = tab in listOf("recent","starred","categories","cat_detail")
        listOf("home","files","scan","tools").forEach { id ->
            val active = (id == "home" && atHome) || (id == "files" && tab == "cat_detail_direct")
            rootFrame.findViewWithTag<ImageView>("${id}_icon")?.setColorFilter(if (active) c_pri else txt2())
            rootFrame.findViewWithTag<TextView>("${id}_label")?.setTextColor(if (active) c_pri else txt2())
        }
    }

    // ?? Theme toggle ??????????????????????????????????????????????????
    private fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        // Rebuild entire UI to apply new theme
        buildUI()
        observeFiles()
    }

    // ?? Data observation ??????????????????????????????????????????????
    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFiles = files; refreshDisplay()
            }
        }
    }

    private fun refreshDisplay() {
        listContainer.removeAllViews()
        tvSection.text = when {
            isDark && currentTab == "recent"     -> "Recent Files"
            isDark && currentTab == "starred"    -> "Starred Files"
            isDark && currentTab == "categories" -> "My Vault"
            !isDark && currentTab == "recent"    -> "LAST ACCESSED DOCUMENTS"
            !isDark && currentTab == "starred"   -> "STARRED DOCUMENTS"
            !isDark && currentTab == "categories"-> "MY VAULT"
            else -> currentTab.replaceFirstChar { it.uppercase() }
        }
        tvSection.textSize = if (isDark) 22f else 9f

        when (currentTab) {
            "recent"     -> renderFiles(allFiles.take(30))
            "starred"    -> renderFiles(allFiles.filter { it.isFavourite })
            "categories" -> renderVault()
            "cat_detail" -> renderFiles(allFiles.filter { it.category == tvSection.text })
        }
    }

    // ?? File cards ????????????????????????????????????????????????????
    // Dark: image thumbnail left, filename white, PDF blue pill, meta gray
    // Light: red PDF icon, filename dark, clock+time, dot+size, star, 3-dot menu
    private fun renderFiles(files: List<RecentFileEntity>) {
        tvEmpty.setTextColor(txt2())
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        files.forEach { listContainer.addView(buildFileCard(it, fmt)) }
    }

    private fun buildFileCard(f: RecentFileEntity, fmt: SimpleDateFormat): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(14).toFloat()
            cardElevation = if (isDark) 0f else dp(2).toFloat()
            setCardBackgroundColor(card())
            strokeWidth = if (isDark) dp(1) else 0
            strokeColor = cardBrd()
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        if (isDark) {
            // Dark mode: square thumbnail placeholder with gradient bg
            val thumb = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(0,0,dp(14),0) }
                setBackgroundColor(Color.parseColor("#3A3A3C"))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0,0,v.width,v.height, dp(8).toFloat())
                    }
                }
                clipToOutline = true
            }
            // PDF type indicator color block
            thumb.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#2C2C2E"))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            thumb.addView(TextView(this).apply {
                text = f.displayName.take(1).uppercase()
                textSize = 20f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_pri); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            row.addView(thumb)
        } else {
            // Light mode: red PDF icon (from screenshot)
            val iconBg = FrameLayout(this).apply {
                val s = dp(44)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0,0,dp(14),0) }
                setBackgroundColor(Color.parseColor("#FFEBEE"))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0,0,v.width,v.height, dp(8).toFloat())
                    }
                }
                clipToOutline = true
            }
            iconBg.addView(TextView(this).apply {
                text = "PDF"; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E53935")); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1,-1)
            })
            row.addView(iconBg)
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        // Filename
        info.addView(TextView(this).apply {
            text = f.displayName.removeSuffix(".pdf")
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(txt1())
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        })

        if (isDark) {
            // Dark mode: "PDF" blue pill + "MODIFIED X AGO ? Y MB"
            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            metaRow.addView(TextView(this).apply {
                text = "PDF"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_pri)
                setBackgroundColor(Color.argb(30, 68, 138, 255))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            metaRow.addView(TextView(this).apply {
                val sizeStr = formatSize(f.fileSizeBytes)
                text = "  MODIFIED ${fmt.format(Date(f.lastOpenedAt)).uppercase()} ? $sizeStr"
                textSize = 10f; setTextColor(txt2()); letterSpacing = 0.03f
            })
            info.addView(metaRow)
        } else {
            // Light mode: clock icon + time, dot, size
            info.addView(TextView(this).apply {
                val sizeStr = formatSize(f.fileSizeBytes)
                val timeStr = relativeTime(f.lastOpenedAt)
                text = "$timeStr  ?  $sizeStr"
                textSize = 11f; setTextColor(txt2()); setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(info)

        // Right side: star button
        val star = ImageButton(this).apply {
            setImageResource(if (f.isFavourite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().setFavourite(f.uri, !f.isFavourite)
                }
            }
        }
        row.addView(star)

        card.addView(row)
        card.setOnClickListener { openUri(Uri.parse(f.uri)) }
        card.setOnLongClickListener { showFileOptions(f); true }
        return card
    }

    // ?? Vault/Categories ??????????????????????????????????????????????
    private fun renderVault() {
        tvEmpty.visibility = View.GONE

        // Header row with + New Folder
        listContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
            addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
            addView(TextView(this@MainActivity).apply {
                text = "+ New Folder"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_pri); letterSpacing = 0.08f
                setOnClickListener { showCreateFolderDialog() }
            })
        })

        val saved    = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats = allFiles.groupBy { it.category }
        val names    = (fileCats.keys + saved).filter { it.isNotEmpty() }.distinct().sorted()

        if (names.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No folders yet.\nTap + New Folder to create one."
                textSize = 13f; setTextColor(txt2()); gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(40))
            })
            return
        }
        names.forEach { cat -> listContainer.addView(buildVaultCard(cat, fileCats[cat] ?: emptyList())) }
    }

    private fun buildVaultCard(cat: String, files: List<RecentFileEntity>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(10)) }
            radius = dp(14).toFloat()
            cardElevation = if (isDark) 0f else dp(2).toFloat()
            setCardBackgroundColor(card())
            strokeWidth = if (isDark) dp(1) else 0; strokeColor = cardBrd()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // Yellow folder icon
        val iconBg = FrameLayout(this).apply {
            val s = dp(48)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(0,0,dp(14),0) }
            setBackgroundColor(Color.argb(25, 255, 214, 10))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0,0,v.width,v.height, dp(10).toFloat())
                }
            }
            clipToOutline = true
        }
        iconBg.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            setColorFilter(c_acc)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        info.addView(TextView(this).apply {
            text = cat; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(txt1())
        })
        val n = files.size
        info.addView(TextView(this).apply {
            text = "$n DOCUMENT${if (n==1) "" else "S"}"
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(txt2()); letterSpacing = 0.1f; setPadding(0, dp(3), 0, 0)
        })
        row.addView(iconBg); row.addView(info)
        row.addView(TextView(this).apply {
            text = ">"; textSize = 16f; setTextColor(txt2()); setPadding(dp(10),0,0,0)
        })
        card.addView(row)
        card.setOnClickListener {
            currentTab = "cat_detail"
            tvSection.text = cat
            tvSection.textSize = if (isDark) 22f else 9f
            listContainer.removeAllViews()
            renderFiles(files)
        }
        card.setOnLongClickListener {
            android.app.AlertDialog.Builder(this).setTitle(cat)
                .setItems(arrayOf("Open all files", "Delete folder")) { _, i ->
                    if (i == 0) { currentTab = "cat_detail"; tvSection.text = cat; listContainer.removeAllViews(); renderFiles(files) }
                    else {
                        val ex = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                        prefs.edit().putStringSet("user_categories", ex.toMutableSet().apply { remove(cat) }).apply()
                        refreshDisplay()
                    }
                }.show()
            true
        }
        return card
    }

    // ?? Dialogs ???????????????????????????????????????????????????????
    private fun showCreateFolderDialog() {
        val et = EditText(this).apply { hint = "Folder name"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle("New Folder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                val ex = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(name) }).apply()
                toast("Folder '$name' created"); refreshDisplay()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val saved  = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fromDb = allFiles.map { it.category }.filter { it.isNotEmpty() }
        val cats   = (fromDb + saved + listOf("General","Work","Personal","Finance"))
                     .distinct().filter { it.isNotEmpty() }.sorted()
        android.app.AlertDialog.Builder(this).setTitle(f.displayName.take(30))
            .setItems(arrayOf(
                if (f.isFavourite) "Remove from Starred" else "Add to Starred",
                "Move to Folder...", "Delete from list", "Share"
            )) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
                    1 -> showMoveDialog(f, cats)
                    2 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) }
                    3 -> shareFile(f)
                }
            }.show()
    }

    private fun showMoveDialog(f: RecentFileEntity, cats: List<String>) {
        if (cats.isEmpty()) { showCreateAndMoveDialog(f); return }
        android.app.AlertDialog.Builder(this).setTitle("Move to Folder")
            .setItems(cats.toTypedArray()) { _, i ->
                val chosen = cats[i]
                val ex = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(chosen) }).apply()
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, chosen) }
                toast("Moved to $chosen")
            }
            .setNeutralButton("+ New Folder") { _, _ -> showCreateAndMoveDialog(f) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showCreateAndMoveDialog(f: RecentFileEntity) {
        val et = EditText(this).apply { hint = "Folder name"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle("New Folder").setView(et)
            .setPositiveButton("Create & Move") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                val ex = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(name) }).apply()
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, name) }
                toast("Moved to '$name'"); refreshDisplay()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Search files..."; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase(); listContainer.removeAllViews()
                val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
                allFiles.filter { it.displayName.lowercase().contains(q) }
                    .forEach { listContainer.addView(buildFileCard(it, fmt)) }
            }
            override fun beforeTextChanged(s: CharSequence?, a:Int, b:Int, c:Int) {}
            override fun onTextChanged(s: CharSequence?, a:Int, b:Int, c:Int) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("Search Files").setView(et)
            .setNegativeButton("Close") { _, _ -> refreshDisplay() }.show()
    }

    private fun showSettings() {
        android.app.AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("Clear recent files", "About ProPDF")) { _, i ->
                when (i) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() }
                    1 -> toast("ProPDF v3.0 - Free, No Ads, All Features Unlocked")
                }
            }.show()
    }

    private fun shareFile(f: RecentFileEntity) {
        try {
            val file = FileHelper.uriToFile(this, Uri.parse(f.uri)) ?: return
            val uri2 = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri2)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (_: Exception) { toast("Cannot share") }
    }

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            val name = FileHelper.getFileName(this@MainActivity, uri) ?: "document.pdf"
            val size = try {
                contentResolver.query(uri,null,null,null,null)?.use { c ->
                    val col = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst() && col>=0) c.getLong(col) else 0L
                } ?: 0L
            } catch (_: Exception) { 0L }
            withContext(Dispatchers.IO) {
                db.recentFilesDao().insert(RecentFileEntity(
                    uri=uri.toString(), displayName=name, fileSizeBytes=size))
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    // ?? Helpers ???????????????????????????????????????????????????????
    private fun formatSize(bytes: Long) = when {
        bytes > 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes > 1_000     -> "%.0f KB".format(bytes / 1e3)
        else              -> "$bytes B"
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 3_600_000L  -> "${diff/60_000} min ago"
            diff < 86_400_000L -> "${diff/3_600_000} hours ago"
            diff < 172_800_000L-> "Yesterday"
            else               -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
        }
    }

    private fun hdrIconBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(txt2()); setPadding(dp(10),dp(10),dp(10),dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
