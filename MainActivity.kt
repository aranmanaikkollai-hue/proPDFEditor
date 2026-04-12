package com.propdf.editor.ui

import android.content.Intent
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

    private val db by lazy { RecentFilesDatabase.get(this) }
    private var currentTab = "recent"
    private var allFiles   = listOf<RecentFileEntity>()

    private lateinit var listContainer : LinearLayout
    private lateinit var tvEmpty       : TextView
    private lateinit var tvSection     : TextView
    private lateinit var tabRow        : LinearLayout

    // ?? Design tokens (from ProPDF_Preview.html) ??????????????????????
    companion object {
        const val BG         = "#121212"
        const val CARD       = "#1E1E1E"
        const val CARD_HOVER = "#252525"
        const val PRIMARY    = "#448AFF"
        const val ACCENT     = "#FFD60A"
        const val TEXT_PRI   = "#FFFFFF"
        const val TEXT_SEC   = "#A0A0A0"
        const val DIVIDER    = "#2A2A2A"
        const val DANGER     = "#FF4757"
    }
    private fun c(hex: String) = Color.parseColor(hex)

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
        window.statusBarColor  = c(BG)
        window.navigationBarColor = c(BG)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
        observeFiles()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
    }

    // ?? Full UI ???????????????????????????????????????????????????????
    private fun buildUI() {
        // Root: FrameLayout so FAB can overlay
        val root = FrameLayout(this).apply { setBackgroundColor(c(BG)) }

        // Vertical content column
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        col.addView(buildHeader())
        col.addView(buildTabBar())

        // Scrollable body
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(96))  // space for bottom nav + FAB
        }
        tvSection = TextView(this).apply {
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(TEXT_SEC))
            letterSpacing = 0.18f
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        tvEmpty = TextView(this).apply {
            text = "No files yet"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(c(TEXT_SEC))
            setPadding(dp(32), dp(48), dp(32), dp(48))
            visibility = View.GONE
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        body.addView(tvSection); body.addView(tvEmpty); body.addView(listContainer)
        scroll.addView(body)
        col.addView(scroll)

        root.addView(col)
        root.addView(buildBottomNavWithFab())  // overlay at bottom
        setContentView(root)
    }

    // ?? Header (from HTML: logo + search + settings icons) ????????????
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(64))
            setBackgroundColor(c(BG))
            setPadding(dp(20), dp(4), dp(12), dp(4))

            // Logo icon box (blue rounded square)
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0,0,dp(12),0) }
                setBackgroundColor(c(PRIMARY))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_agenda)
                    setColorFilter(Color.WHITE)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22)).apply { gravity = Gravity.CENTER }
                })
            })

            // Title + subtitle column
            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            titleCol.addView(TextView(this@MainActivity).apply {
                text = "ProPDF"
                textSize = 21f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c(PRIMARY))
            })
            titleCol.addView(TextView(this@MainActivity).apply {
                text = "WORKSPACE"
                textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c(TEXT_SEC))
                letterSpacing = 0.2f
            })
            addView(titleCol)

            // Search icon
            addView(iconBtn(android.R.drawable.ic_menu_search) { showSearchDialog() })
            // Settings icon
            addView(iconBtn(android.R.drawable.ic_menu_preferences) { showSettings() })
        }
    }

    // ?? Tab bar (Recent / Starred / Categories with underline) ????????
    private fun buildTabBar(): LinearLayout {
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
            setBackgroundColor(c(BG))
        }
        // Divider
        val div = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
            setBackgroundColor(c(DIVIDER))
        }

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        listOf("Recent" to "recent", "Starred" to "starred", "Categories" to "categories")
            .forEach { (label, id) ->
                val active = id == currentTab
                val tabCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setOnClickListener { switchTab(id) }
                    tag = id
                }
                tabCol.addView(TextView(this).apply {
                    text = label; textSize = 14f; gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    setTextColor(if (active) c(PRIMARY) else c(TEXT_SEC))
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    tag = "${id}_tv"
                })
                tabCol.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(3))
                    setBackgroundColor(if (active) c(PRIMARY) else Color.TRANSPARENT)
                    tag = "${id}_bar"
                })
                tabRow.addView(tabCol)
            }

        wrapper.addView(tabRow); wrapper.addView(div)
        return wrapper
    }

    // ?? Bottom nav with center-raised FAB ?????????????????????????????
    private fun buildBottomNavWithFab(): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
        }

        // Glass nav bar (dark, semi-transparent feel via color)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(-1, dp(72)).apply { gravity = Gravity.BOTTOM }
            setBackgroundColor(c(BG))
            setPadding(0, 0, 0, dp(8))
            // Top border line
        }

        // Top border of nav
        val topLine = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(1)).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(72) }
            setBackgroundColor(c(DIVIDER))
        }

        data class NavItem(val id: String, val label: String, val icon: Int)
        val items = listOf(
            NavItem("home",  "HOME",  android.R.drawable.ic_menu_view),
            NavItem("files", "FILES", android.R.drawable.ic_menu_agenda),
            NavItem("",      "",      0),  // FAB placeholder
            NavItem("tools", "TOOLS", android.R.drawable.ic_menu_preferences),
            NavItem("ai",    "AI",    android.R.drawable.ic_menu_help)
        )

        items.forEach { item ->
            if (item.id.isEmpty()) {
                // Empty space for FAB center position
                nav.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                return@forEach
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener {
                    when (item.id) {
                        "home"  -> switchTab("recent")
                        "files" -> switchTab("categories")
                        "tools" -> startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                        "ai"    -> showAiPanel()
                    }
                }
            }
            col.addView(ImageView(this).apply {
                setImageResource(item.icon)
                setColorFilter(if (item.id == "home" && currentTab == "recent") c(PRIMARY) else c(TEXT_SEC))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                tag = "${item.id}_icon"
            })
            col.addView(TextView(this).apply {
                text = item.label; textSize = 8f; gravity = Gravity.CENTER
                setTextColor(if (item.id == "home" && currentTab == "recent") c(PRIMARY) else c(TEXT_SEC))
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
                setPadding(0, dp(3), 0, 0)
                tag = "${item.id}_label"
            })
            nav.addView(col)
        }

        // Center FAB (raised above nav)
        val fab = FrameLayout(this).apply {
            val size = dp(60)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)  // raised above nav
            }
            setBackgroundColor(c(PRIMARY))
            // Make circle
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        }
        fab.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
        })

        // Scan FAB (secondary, long-press on FAB or separate)
        frame.addView(topLine)
        frame.addView(nav)
        frame.addView(fab)
        return frame
    }

    // ?? Tab switch ????????????????????????????????????????????????????
    private fun switchTab(tab: String) {
        currentTab = tab
        listOf("recent", "starred", "categories").forEach { t ->
            tabRow.findViewWithTag<TextView>("${t}_tv")?.apply {
                setTextColor(if (t == tab) c(PRIMARY) else c(TEXT_SEC))
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            tabRow.findViewWithTag<View>("${t}_bar")?.setBackgroundColor(
                if (t == tab) c(PRIMARY) else Color.TRANSPARENT)
        }
        // Update bottom nav home icon
        (contentView()?.findViewWithTag<ImageView>("home_icon"))?.setColorFilter(
            if (tab == "recent") c(PRIMARY) else c(TEXT_SEC))
        (contentView()?.findViewWithTag<TextView>("home_label"))?.setTextColor(
            if (tab == "recent") c(PRIMARY) else c(TEXT_SEC))

        tvSection.text = when (tab) {
            "starred"    -> "STARRED FILES"
            "categories" -> "MY VAULT"
            else         -> "RECENT FILES"
        }
        refreshDisplay()
    }

    private fun contentView() = window.decorView.findViewById<FrameLayout>(android.R.id.content)

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFiles = files; refreshDisplay()
            }
        }
    }

    private fun refreshDisplay() {
        listContainer.removeAllViews()
        tvSection.text = when (currentTab) {
            "starred"    -> "STARRED FILES"
            "categories" -> "MY VAULT"
            else         -> "RECENT FILES"
        }
        when (currentTab) {
            "recent"     -> renderFiles(allFiles.take(30))
            "starred"    -> renderFiles(allFiles.filter { it.isFavourite })
            "categories" -> renderVault()
        }
    }

    // ?? File list (from screenshot: PDF badge, title, meta, star) ?????
    private fun renderFiles(files: List<RecentFileEntity>) {
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        files.forEach { listContainer.addView(buildFileCard(it, fmt)) }
    }

    private fun buildFileCard(f: RecentFileEntity, fmt: SimpleDateFormat): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(CARD))
            strokeWidth = 0
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // PDF badge (pink background, red text - from screenshot)
        val badge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(52)).apply { setMargins(0,0,dp(14),0) }
            setBackgroundColor(Color.argb(30, 255, 71, 87))  // danger/10
        }
        badge.addView(TextView(this).apply {
            text = "PDF"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(DANGER)); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1,-1)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        // File name without extension
        info.addView(TextView(this).apply {
            text = f.displayName.removeSuffix(".pdf")
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(TEXT_PRI))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // Size + date metadata
        val sizeStr = when {
            f.fileSizeBytes > 1_000_000 -> "%.1f MB".format(f.fileSizeBytes / 1e6)
            f.fileSizeBytes > 1_000     -> "%.0f KB".format(f.fileSizeBytes / 1e3)
            else                        -> "${f.fileSizeBytes} B"
        }
        info.addView(TextView(this).apply {
            text = "$sizeStr   ${fmt.format(Date(f.lastOpenedAt))}"
            textSize = 11f; setTextColor(c(TEXT_SEC)); setPadding(0, dp(3), 0, 0)
        })

        // Star button (from screenshot: star icon on right)
        val star = ImageButton(this).apply {
            setImageResource(if (f.isFavourite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.recentFilesDao().setFavourite(f.uri, !f.isFavourite)
                }
            }
        }
        row.addView(badge); row.addView(info); row.addView(star)
        card.addView(row)
        card.setOnClickListener { openUri(Uri.parse(f.uri)) }
        card.setOnLongClickListener { showFileOptions(f); true }
        return card
    }

    // ?? Vault (categories) - from HTML vault section ???????????????????
    private fun renderVault() {
        tvEmpty.visibility = View.GONE

        // New Folder button row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(12))
        }
        headerRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        headerRow.addView(TextView(this).apply {
            text = "+ New Folder"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(PRIMARY)); letterSpacing = 0.1f
            setOnClickListener { showCreateCategoryDialog() }
        })
        listContainer.addView(headerRow)

        val prefs    = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val saved    = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats = allFiles.groupBy { it.category }
        val names    = (fileCats.keys + saved).filter { it.isNotEmpty() }.distinct().sorted()

        if (names.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No folders yet. Tap + New Folder to create one."
                textSize = 13f; setTextColor(c(TEXT_SEC)); gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            })
            return
        }
        names.forEach { cat -> listContainer.addView(buildVaultCard(cat, fileCats[cat] ?: emptyList())) }
    }

    private fun buildVaultCard(cat: String, files: List<RecentFileEntity>): View {
        // From HTML: flex items-center gap-4 p-5 bg-card rounded-2xl border border-white/5
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(10)) }
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(c(CARD)); strokeWidth = 0
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        // Yellow folder icon (from HTML: p-3 bg-accent/10 rounded-xl, yellow fill icon)
        val iconBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { setMargins(0,0,dp(16),0) }
            setBackgroundColor(Color.argb(25, 255, 214, 10))  // accent/10
        }
        iconBg.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            setColorFilter(c(ACCENT))
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER }
        })
        // Text column
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        info.addView(TextView(this).apply {
            text = cat; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c(TEXT_PRI))
        })
        val n = files.size
        info.addView(TextView(this).apply {
            text = "$n DOCUMENT${if (n == 1) "" else "S"}  |  Tap to open".uppercase()
            textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c(TEXT_SEC))
            letterSpacing = 0.12f; setPadding(0, dp(3), 0, 0)
        })
        // Chevron
        row.addView(iconBg); row.addView(info)
        row.addView(TextView(this).apply {
            text = ">"; textSize = 18f; setTextColor(c(TEXT_SEC)); setPadding(dp(12),0,0,0)
        })
        card.addView(row)
        card.setOnClickListener {
            currentTab = "cat_detail"; tvSection.text = cat.uppercase()
            listContainer.removeAllViews(); renderFiles(files)
        }
        card.setOnLongClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(cat)
                .setItems(arrayOf("Open all files", "Delete folder")) { _, i ->
                    if (i == 0) { currentTab = "cat_detail"; tvSection.text = cat; listContainer.removeAllViews(); renderFiles(files) }
                    else {
                        val pr = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                        val ex = pr.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                        pr.edit().putStringSet("user_categories", ex.toMutableSet().apply { remove(cat) }).apply()
                        refreshDisplay()
                    }
                }.show()
            true
        }
        return card
    }

    // ?? Dialogs ???????????????????????????????????????????????????????
    private fun showCreateCategoryDialog() {
        val et = EditText(this).apply {
            hint = "Folder name (e.g. Work, Finance, Personal)"
            setHintTextColor(c(TEXT_SEC)); setTextColor(c(TEXT_PRI))
            setBackgroundColor(c(CARD)); setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        android.app.AlertDialog.Builder(this).setTitle("New Folder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                val pr = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val ex = pr.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                pr.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(name) }).apply()
                toast("Folder '$name' created"); refreshDisplay()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val prefs  = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val saved  = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fromDb = allFiles.map { it.category }.filter { it.isNotEmpty() }
        val cats   = (fromDb + saved + listOf("General","Work","Personal","Finance"))
                     .distinct().filter { it.isNotEmpty() }.sorted()

        android.app.AlertDialog.Builder(this)
            .setTitle(f.displayName.take(30))
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
                val pr = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val ex = pr.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                pr.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(chosen) }).apply()
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
                val pr = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val ex = pr.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                pr.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(name) }).apply()
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
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("Search").setView(et)
            .setNegativeButton("Close") { _, _ -> refreshDisplay() }.show()
    }

    private fun showAiPanel() {
        android.app.AlertDialog.Builder(this).setTitle("AI Features")
            .setMessage("Coming soon:\n- Smart document summary\n- Key insights extraction\n- Auto-categorization\n\nFor now, use Extract Text in the PDF viewer to get text content.")
            .setPositiveButton("OK", null).show()
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
                db.recentFilesDao().insert(RecentFileEntity(uri=uri.toString(), displayName=name, fileSizeBytes=size))
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    private fun iconBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(c(TEXT_SEC)); setPadding(dp(10),dp(10),dp(10),dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
