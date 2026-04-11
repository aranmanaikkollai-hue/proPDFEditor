package com.propdf.editor.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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

    private lateinit var tabRow       : LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty      : TextView
    private lateinit var tvSection    : TextView
    private lateinit var activeTabBars: Array<View>   // underline indicators

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        openUri(uri)
    }

    // ?? Design tokens ?????????????????????????????????????????????????
    companion object {
        const val C_BG       = "#F8F9FB"
        const val C_SURFACE  = "#FFFFFF"
        const val C_PRIMARY  = "#2563EB"   // Vivid blue
        const val C_ACCENT   = "#7C3AED"   // Purple accent
        const val C_SUCCESS  = "#059669"   // Green
        const val C_DANGER   = "#DC2626"   // Red
        const val C_TEXT1    = "#111827"   // Near black
        const val C_TEXT2    = "#6B7280"   // Gray
        const val C_TEXT3    = "#9CA3AF"   // Light gray
        const val C_DIVIDER  = "#E5E7EB"
        const val C_TAB_INACTIVE = "#9CA3AF"
    }
    private fun pc(hex: String) = Color.parseColor(hex)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null)
            openUri(intent.data!!)
        observeFiles()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) openUri(intent.data!!)
    }

    private fun buildUI() {
        window.statusBarColor = pc(C_PRIMARY)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pc(C_BG))
        }

        root.addView(buildHeader())
        root.addView(buildQuickActions())
        root.addView(buildTabBar())
        root.addView(buildSectionRow())

        // Scrollable content
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(80))
        }
        tvEmpty = TextView(this).apply {
            text = "No files yet. Tap Open to add a PDF."
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(pc(C_TEXT3))
            setPadding(dp(32), dp(48), dp(32), dp(48))
            visibility = View.GONE
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        inner.addView(tvEmpty); inner.addView(listContainer)
        scroll.addView(inner)

        root.addView(scroll)
        root.addView(buildBottomNav())
        setContentView(root)
    }

    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(60))
            setBackgroundColor(pc(C_PRIMARY))
            setPadding(dp(20), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(this@MainActivity).apply {
                text = "ProPDF"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                letterSpacing = -0.02f
            })
            // Search button
            addView(iconBtn(android.R.drawable.ic_menu_search) { showSearchDialog() })
            addView(iconBtn(android.R.drawable.ic_menu_more) { showSettings() })
        }
    }

    private fun buildQuickActions(): LinearLayout {
        data class QA(val label: String, val icon: Int, val bg: String, val action: () -> Unit)
        val actions = listOf(
            QA("Open PDF",   android.R.drawable.ic_menu_agenda,    C_PRIMARY, { pdfPicker.launch(arrayOf("application/pdf")) }),
            QA("Scan",       android.R.drawable.ic_menu_camera,    C_ACCENT,  { startActivity(Intent(this, DocumentScannerActivity::class.java)) }),
            QA("Tools",      android.R.drawable.ic_menu_preferences, C_SUCCESS, { startActivity(Intent(this, ToolsActivity::class.java)) }),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pc(C_PRIMARY))
            setPadding(dp(16), 0, dp(16), dp(16))

            actions.forEach { qa ->
                val btn = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(68), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
                    setBackgroundColor(pc(qa.bg).let { base ->
                        // Semi-transparent overlay on primary bg
                        Color.argb(60, Color.red(base), Color.green(base), Color.blue(base))
                    })
                    // Use white background with tint to simulate card
                    setBackgroundColor(Color.argb(40, 255, 255, 255))
                    setOnClickListener { qa.action() }
                }
                btn.addView(ImageView(this@MainActivity).apply {
                    setImageResource(qa.icon)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                })
                btn.addView(TextView(this@MainActivity).apply {
                    text = qa.label
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(4), 0, 0)
                    gravity = Gravity.CENTER
                })
                addView(btn)
            }
        }
    }

    private fun buildTabBar(): LinearLayout {
        val tabs = listOf("Recent" to "recent", "Starred" to "starred", "Categories" to "categories")
        val bars  = mutableListOf<View>()

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setBackgroundColor(pc(C_SURFACE))
            elevation = dp(2).toFloat()
        }

        tabs.forEach { (label, id) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(10))
                setTextColor(if (id == currentTab) pc(C_PRIMARY) else pc(C_TAB_INACTIVE))
                typeface = if (id == currentTab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                tag = "${id}_tv"
                setOnClickListener { switchTab(id) }
            }
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(3))
                setBackgroundColor(if (id == currentTab) pc(C_PRIMARY) else Color.TRANSPARENT)
                tag = "${id}_bar"
            }
            col.addView(tv); col.addView(bar)
            tabRow.addView(col)
            bars.add(bar)
        }
        activeTabBars = bars.toTypedArray()
        return tabRow
    }

    private fun buildSectionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(6))
            setBackgroundColor(pc(C_BG))

            tvSection = TextView(this@MainActivity).apply {
                text = "Recent Files"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(pc(C_TEXT2))
                letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(tvSection)

            // Sort/filter button (placeholder)
            addView(TextView(this@MainActivity).apply {
                text = "Sort"
                textSize = 11f
                setTextColor(pc(C_PRIMARY))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(4), dp(4), dp(4))
            })
        }
    }

    private fun buildBottomNav(): LinearLayout {
        data class Tab(val id: String, val label: String, val icon: Int)
        val tabs = listOf(
            Tab("home",  "Home",  android.R.drawable.ic_menu_view),
            Tab("open",  "Open",  android.R.drawable.ic_menu_add),
            Tab("scan",  "Scan",  android.R.drawable.ic_menu_camera),
            Tab("tools", "Tools", android.R.drawable.ic_menu_preferences),
        )
        return LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(62))
            setBackgroundColor(pc(C_SURFACE))
            elevation = dp(16).toFloat()

            tabs.forEach { tab ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    setOnClickListener {
                        when (tab.id) {
                            "open"  -> pdfPicker.launch(arrayOf("application/pdf"))
                            "scan"  -> startActivity(Intent(this@MainActivity, DocumentScannerActivity::class.java))
                            "tools" -> startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                            else    -> switchTab("recent")
                        }
                    }
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(tab.icon)
                        setColorFilter(if (tab.id == "home") pc(C_PRIMARY) else pc(C_TAB_INACTIVE))
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = tab.label
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(if (tab.id == "home") pc(C_PRIMARY) else pc(C_TAB_INACTIVE))
                        setPadding(0, dp(2), 0, 0)
                    })
                })
            }
        }
    }

    // ?? Tab switching ?????????????????????????????????????????????????
    private fun switchTab(tab: String) {
        currentTab = tab
        val tabs = listOf("recent", "starred", "categories")
        tabs.forEach { t ->
            (tabRow.findViewWithTag<TextView>("${t}_tv"))?.apply {
                setTextColor(if (t == tab) pc(C_PRIMARY) else pc(C_TAB_INACTIVE))
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            (tabRow.findViewWithTag<View>("${t}_bar"))?.setBackgroundColor(
                if (t == tab) pc(C_PRIMARY) else Color.TRANSPARENT)
        }
        tvSection.text = when (tab) {
            "starred"    -> "Starred Files"
            "categories" -> "Categories"
            else         -> "Recent Files"
        }
        refreshDisplay()
    }

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFiles = files; refreshDisplay()
            }
        }
    }

    private fun refreshDisplay() {
        listContainer.removeAllViews()
        when (currentTab) {
            "recent"     -> renderFiles(allFiles.take(30))
            "starred"    -> renderFiles(allFiles.filter { it.isFavourite })
            "categories" -> renderCategories()
        }
    }

    // ?? File cards ????????????????????????????????????????????????????
    private fun renderFiles(files: List<RecentFileEntity>) {
        tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        files.forEach { listContainer.addView(buildFileCard(it, fmt)) }
    }

    private fun buildFileCard(f: RecentFileEntity, fmt: SimpleDateFormat): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = pc(C_DIVIDER)
            setCardBackgroundColor(pc(C_SURFACE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        // PDF badge
        val badge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(52)).apply { setMargins(0, 0, dp(14), 0) }
            setBackgroundColor(pc("#FEF2F2"))
        }
        badge.addView(TextView(this).apply {
            text = "PDF"; textSize = 9f
            setTextColor(pc(C_DANGER))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        info.addView(TextView(this).apply {
            text = f.displayName.removeSuffix(".pdf")
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pc(C_TEXT1))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            val sizeStr = when {
                f.fileSizeBytes > 1_000_000 -> "%.1f MB".format(f.fileSizeBytes / 1e6)
                f.fileSizeBytes > 1_000     -> "%.0f KB".format(f.fileSizeBytes / 1e3)
                else                        -> "${f.fileSizeBytes} B"
            }
            text = "$sizeStr ? ${fmt.format(Date(f.lastOpenedAt))}"
            textSize = 11f
            setTextColor(pc(C_TEXT2))
            setPadding(0, dp(3), 0, 0)
        })
        val star = ImageButton(this).apply {
            setImageResource(if (f.isFavourite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_VERTICAL }
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
            }
        }
        row.addView(badge); row.addView(info); row.addView(star)
        card.addView(row)
        card.setOnClickListener { openUri(Uri.parse(f.uri)) }
        card.setOnLongClickListener { showFileOptions(f); true }
        return card
    }

    // ?? Categories ????????????????????????????????????????????????????
    private fun renderCategories() {
        val prefs     = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val savedCats = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats  = allFiles.groupBy { it.category }
        val allNames  = (fileCats.keys + savedCats).filter { it.isNotEmpty() }.distinct().sorted()

        // New category button
        listContainer.addView(buildNewCatButton())

        if (allNames.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "No categories yet. Tap + to create one."
        } else {
            tvEmpty.visibility = View.GONE
            allNames.forEach { cat ->
                listContainer.addView(buildCategoryCard(cat, fileCats[cat] ?: emptyList()))
            }
        }
    }

    private fun buildNewCatButton(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(12).toFloat(); cardElevation = 0f
            strokeWidth = dp(1); strokeColor = pc(C_PRIMARY)
            setCardBackgroundColor(pc("#EFF6FF"))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(pc(C_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        })
        row.addView(TextView(this).apply {
            text = "Create New Category"
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(pc(C_PRIMARY))
        })
        card.addView(row)
        card.setOnClickListener { showCreateCategoryDialog() }
        return card
    }

    private fun buildCategoryCard(cat: String, files: List<RecentFileEntity>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            radius = dp(12).toFloat(); cardElevation = 0f
            strokeWidth = dp(1); strokeColor = pc(C_DIVIDER)
            setCardBackgroundColor(pc(C_SURFACE))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val iconBg = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(14), 0) }
            setBackgroundColor(pc("#EFF6FF"))
        }
        iconBg.addView(TextView(this).apply {
            text = cat.take(1).uppercase(); textSize = 18f; gravity = Gravity.CENTER
            setTextColor(pc(C_PRIMARY)); typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        info.addView(TextView(this).apply {
            text = cat; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(pc(C_TEXT1))
        })
        info.addView(TextView(this).apply {
            text = "${files.size} file${if (files.size == 1) "" else "s"}"
            textSize = 11f; setTextColor(pc(C_TEXT2)); setPadding(0, dp(2), 0, 0)
        })
        row.addView(iconBg); row.addView(info)
        row.addView(TextView(this).apply {
            text = ">"; textSize = 16f; setTextColor(pc(C_TEXT3)); setPadding(dp(8), 0, 0, 0)
        })
        card.addView(row)
        card.setOnClickListener {
            currentTab = "cat_detail"
            tvSection.text = cat
            listContainer.removeAllViews()
            renderFiles(files)
        }
        return card
    }

    // ?? Dialogs ???????????????????????????????????????????????????????
    private fun showCreateCategoryDialog() {
        val et = EditText(this).apply { hint = "e.g. Work, Personal, Invoices"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle("New Category").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val existing = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", existing.toMutableSet().apply { add(name) }).apply()
                toast("Category '$name' created")
                refreshDisplay()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val items = arrayOf(
            if (f.isFavourite) "Remove from Starred" else "Add to Starred",
            "Move to Category...", "Delete from list", "Share"
        )
        android.app.AlertDialog.Builder(this).setTitle(f.displayName.take(32))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
                    1 -> showMoveToCategoryDialog(f)
                    2 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) }
                    3 -> shareFile(f)
                }
            }.show()
    }

    private fun showMoveToCategoryDialog(f: RecentFileEntity) {
        val prefs    = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val saved    = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fromDb   = allFiles.map { it.category }.filter { it.isNotEmpty() }
        val all      = (fromDb + saved + listOf("General", "Work", "Personal", "Education"))
                       .distinct().filter { it.isNotEmpty() }.sorted().toTypedArray()
        if (all.isEmpty()) { showCreateAndMoveCategoryDialog(f); return }

        android.app.AlertDialog.Builder(this).setTitle("Move to Category")
            .setItems(all) { _, i ->
                val chosen = all[i]
                val existing = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", existing.toMutableSet().apply { add(chosen) }).apply()
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, chosen) }
                toast("Moved to $chosen")
            }
            .setNeutralButton("+ New") { _, _ -> showCreateAndMoveCategoryDialog(f) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showCreateAndMoveCategoryDialog(f: RecentFileEntity) {
        val et = EditText(this).apply { hint = "Category name"; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle("New Category").setView(et)
            .setPositiveButton("Create & Move") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val ex = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                prefs.edit().putStringSet("user_categories", ex.toMutableSet().apply { add(name) }).apply()
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, name) }
                toast("Moved to '$name'"); refreshDisplay()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Search files..."; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase()
                listContainer.removeAllViews()
                val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
                allFiles.filter { it.displayName.lowercase().contains(q) }
                    .forEach { listContainer.addView(buildFileCard(it, fmt)) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("Search Files").setView(et)
            .setNegativeButton("Close") { _, _ -> refreshDisplay() }.show()
    }

    private fun showSettings() {
        android.app.AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf("Clear all recent files", "About ProPDF")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() }
                    1 -> toast("ProPDF Editor v3.0 - Free, No Ads, All Features")
                }
            }.show()
    }

    private fun shareFile(f: RecentFileEntity) {
        try {
            val file = FileHelper.uriToFile(this, Uri.parse(f.uri))
            if (file != null) {
                val uri2 = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri2)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share PDF"))
            }
        } catch (_: Exception) { toast("Cannot share") }
    }

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
                db.recentFilesDao().insert(RecentFileEntity(uri = uri.toString(), displayName = name, fileSizeBytes = size))
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    private fun iconBtn(icon: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
