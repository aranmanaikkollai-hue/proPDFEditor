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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.R
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
    private var currentTab = "recent"  // recent | starred | categories
    private var currentCategory = ""
    private var allFiles = listOf<RecentFileEntity>()

    private lateinit var tabRow         : LinearLayout
    private lateinit var listContainer  : LinearLayout
    private lateinit var emptyState     : LinearLayout
    private lateinit var tvSectionLabel : TextView

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        saveRecentAndOpen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null)
            saveRecentAndOpen(intent.data!!)
        observeData()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null)
            saveRecentAndOpen(intent.data!!)
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F4FF"))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(dp(16), 0, dp(12), 0); gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "ProPDF"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(makeIconBtn(android.R.drawable.ic_menu_search, Color.WHITE) { showSearchDialog() })
        header.addView(makeIconBtn(android.R.drawable.ic_menu_preferences, Color.WHITE) { showSettings() })

        // Tab bar: Recent | Starred | Categories
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        listOf("Recent", "Starred", "Categories").forEachIndexed { i, label ->
            val id = listOf("recent", "starred", "categories")[i]
            tabRow.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                tag = "${id}_tab"
                setTextColor(if (i == 0) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
                typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { switchTab(id) }
            })
        }

        // Section label
        tvSectionLabel = TextView(this).apply {
            text = "Recent Files"; textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(16), dp(12), dp(16), dp(6))
        }

        // Empty state
        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(0, dp(60), 0, dp(40)); visibility = View.GONE
        }
        emptyState.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_agenda)
            setColorFilter(Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        emptyState.addView(TextView(this).apply {
            text = "No files here yet"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA")); setPadding(0, dp(12), 0, dp(4))
        })

        // Scrollable list
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val innerLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        innerLayout.addView(tvSectionLabel)
        innerLayout.addView(emptyState)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(80))
        }
        innerLayout.addView(listContainer)
        scroll.addView(innerLayout)

        // Bottom nav
        val bottomNav = buildBottomNav()

        // FAB
        val fabFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(0))
        }

        root.addView(header); root.addView(tabRow); root.addView(scroll); root.addView(bottomNav)
        setContentView(root)

        // Floating action button
        val fabContainer = FrameLayout(this)
        val fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A73E8"))
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(20), dp(78))
            }
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        }
        // We can't add FAB to already-set content view easily without coordinator layout
        // So use window decorView overlay approach
    }

    private fun buildBottomNav(): LinearLayout {
        data class Tab(val id: String, val lbl: String, val icon: Int)
        val tabs = listOf(
            Tab("home",  "Home",  android.R.drawable.ic_menu_view),
            Tab("files", "Files", android.R.drawable.ic_menu_agenda),
            Tab("open",  "Open",  android.R.drawable.ic_menu_add),
            Tab("scan",  "Scan",  android.R.drawable.ic_menu_camera),
            Tab("tools", "Tools", android.R.drawable.ic_menu_preferences),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(60))
            setBackgroundColor(Color.WHITE); elevation = dp(12).toFloat()
            tabs.forEach { tab ->
                val btn = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener {
                        when (tab.id) {
                            "open"  -> pdfPicker.launch(arrayOf("application/pdf"))
                            "scan"  -> startActivity(Intent(this@MainActivity, DocumentScannerActivity::class.java))
                            "tools" -> startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                            "home"  -> switchTab("recent")
                            "files" -> switchTab("categories")
                        }
                    }
                }
                btn.addView(ImageView(this@MainActivity).apply {
                    setImageResource(tab.icon)
                    setColorFilter(if (tab.id == "home") Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
                btn.addView(TextView(this@MainActivity).apply {
                    text = tab.lbl; textSize = 9f; gravity = Gravity.CENTER
                    setTextColor(if (tab.id == "home") Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
                    setPadding(0, dp(2), 0, 0)
                })
                addView(btn)
            }
        }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        // Update tab highlight
        listOf("recent","starred","categories").forEach { t ->
            val tv = tabRow.findViewWithTag<TextView>("${t}_tab")
            tv?.setTextColor(if (t == tab) Color.parseColor("#1A73E8") else Color.parseColor("#888888"))
            tv?.typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        tvSectionLabel.text = when (tab) {
            "recent"     -> "Recent Files"
            "starred"    -> "Starred Files"
            "categories" -> "Categories"
            else -> "Files"
        }
        refreshDisplay()
    }

    private fun observeData() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFiles = files
                refreshDisplay()
            }
        }
    }

    private fun refreshDisplay() {
        listContainer.removeAllViews()
        when (currentTab) {
            "recent"     -> renderFileList(allFiles.take(20))
            "starred"    -> renderFileList(allFiles.filter { it.isFavourite })
            "categories" -> renderCategories()
        }
    }

    private fun renderFileList(files: List<RecentFileEntity>) {
        emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        files.forEach { f -> listContainer.addView(buildFileCard(f, fmt)) }
    }

    private fun renderCategories() {
        emptyState.visibility = View.GONE
        // Show categories from files PLUS any user-created categories stored in prefs
        val prefs      = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val savedCats  = prefs.getStringSet("user_categories", emptySet()) ?: emptySet()
        val fileCats   = allFiles.groupBy { it.category }
        val allCatNames = (fileCats.keys + savedCats).filter { it.isNotEmpty() }.distinct().sorted()
        if (allCatNames.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            // Show a hint
            listContainer.addView(TextView(this).apply {
                text = "No categories yet. Tap + to create one."
                textSize = 13f; setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0)
            })
            return
        }

        // Show "Add Category" button
        listContainer.addView(MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2),dp(4),dp(2),dp(4)) }
            radius = dp(12).toFloat(); cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.parseColor("#E8F0FE"))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_input_add)
                    setColorFilter(Color.parseColor("#1A73E8"))
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0,0,dp(8),0) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Create New Category"; textSize = 14f
                    setTextColor(Color.parseColor("#1A73E8")); typeface = Typeface.DEFAULT_BOLD
                })
            })
            setOnClickListener { showCreateCategoryDialog() }
        })

        allCatNames.forEach { cat ->
            listContainer.addView(buildCategoryCard(cat, fileCats[cat] ?: emptyList()))
        }
    }

    private fun buildCategoryCard(cat: String, files: List<RecentFileEntity>): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2),dp(4),dp(2),dp(4)) }
            radius = dp(12).toFloat(); cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val icon = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0,0,dp(12),0) }
            setBackgroundColor(Color.parseColor("#E8F0FE"))
        }
        icon.addView(TextView(this).apply {
            text = cat.take(1).uppercase(); textSize = 18f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A73E8")); typeface = Typeface.DEFAULT_BOLD
            layoutParams = FrameLayout.LayoutParams(-1,-1)
        })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        info.addView(TextView(this).apply { text = cat; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#111111")) })
        info.addView(TextView(this).apply { text = "${files.size} file${if(files.size==1) "" else "s"}"; textSize = 11f; setTextColor(Color.parseColor("#888888")); setPadding(0,dp(2),0,0) })
        val arr = TextView(this).apply { text = ">"; textSize = 16f; setTextColor(Color.parseColor("#CCCCCC")); setPadding(dp(8),0,0,0) }
        inner.addView(icon); inner.addView(info); inner.addView(arr)
        card.addView(inner)
        card.setOnClickListener { showCategoryFiles(cat) }
        return card
    }

    private fun showCategoryFiles(cat: String) {
        currentCategory = cat; currentTab = "category_detail"
        tvSectionLabel.text = cat
        listContainer.removeAllViews()
        renderFileList(allFiles.filter { it.category == cat })
    }

    private fun showCreateCategoryDialog() {
        val et = EditText(this).apply { hint = "Category name (e.g. Work, Personal)"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        android.app.AlertDialog.Builder(this).setTitle("New Category").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // Persist category name in SharedPreferences so it shows even with no files
                val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
                val existing = prefs.getStringSet("user_categories", mutableSetOf()) ?: mutableSetOf()
                val updated  = existing.toMutableSet().apply { add(name) }
                prefs.edit().putStringSet("user_categories", updated).apply()
                toast("Category '$name' created!")
                refreshDisplay()  // re-render to show new category
            }.setNegativeButton("Cancel", null).show()
    }

    private fun buildFileCard(f: RecentFileEntity, fmt: SimpleDateFormat): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2),dp(3),dp(2),dp(3)) }
            radius = dp(12).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
            strokeWidth = 0
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(14),dp(12),dp(14),dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        // PDF badge
        val badge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(50)).apply { setMargins(0,0,dp(12),0) }
            setBackgroundColor(Color.parseColor("#FDECEA"))
        }
        badge.addView(TextView(this).apply {
            text = "PDF"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#D32F2F")); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1,-1)
        })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        info.addView(TextView(this).apply {
            text = f.displayName; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111111")); isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            val sizeStr = when { f.fileSizeBytes>1_000_000->"%.1f MB".format(f.fileSizeBytes/1e6); f.fileSizeBytes>1_000->"%.0f KB".format(f.fileSizeBytes/1e3); else->"${f.fileSizeBytes} B" }
            text = "$sizeStr  |  ${fmt.format(Date(f.lastOpenedAt))}"
            textSize = 10f; setTextColor(Color.parseColor("#999999")); setPadding(0,dp(3),0,0)
        })
        // Star button
        val btnStar = ImageButton(this).apply {
            setImageResource(if (f.isFavourite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(36),dp(36)).apply { gravity=Gravity.CENTER_VERTICAL }
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
            }
        }
        val btnDel = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(dp(32),dp(32)).apply { gravity=Gravity.CENTER_VERTICAL }
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) } }
        }
        row.addView(badge); row.addView(info); row.addView(btnStar); row.addView(btnDel)
        card.addView(row)
        // Long press to manage
        card.setOnLongClickListener {
            showFileOptions(f); true
        }
        card.setOnClickListener { saveRecentAndOpen(Uri.parse(f.uri)) }
        return card
    }

    private fun showFileOptions(f: RecentFileEntity) {
        val cats = allFiles.map { it.category }.distinct().filter { it.isNotEmpty() }
        val items = mutableListOf(
            if (f.isFavourite) "Remove from Starred" else "Add to Starred",
            "Move to Category...",
            "Remove from list",
            "Share"
        )
        android.app.AlertDialog.Builder(this).setTitle(f.displayName.take(30))
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setFavourite(f.uri, !f.isFavourite) }
                    1 -> showMoveToCategoryDialog(f, cats)
                    2 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) }
                    3 -> {
                        try {
                            val file = FileHelper.uriToFile(this, Uri.parse(f.uri))
                            if (file != null) {
                                val uri2 = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", file)
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri2)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share PDF"))
                            }
                        } catch (_: Exception) { toast("Cannot share this file") }
                    }
                }
            }.show()
    }

    private fun showMoveToCategoryDialog(f: RecentFileEntity, existingCats: List<String>) {
        val allCats = (existingCats + listOf("General", "Work", "Personal", "Education")).distinct().toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Move to Category")
            .setItems(allCats) { _, i ->
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, allCats[i]) }
                toast("Moved to ${allCats[i]}")
            }
            .setNeutralButton("New Category") { _, _ ->
                val et = EditText(this).apply { hint = "Category name"; setPadding(dp(20),dp(8),dp(20),dp(8)) }
                android.app.AlertDialog.Builder(this).setTitle("New Category").setView(et)
                    .setPositiveButton("Create") { _, _ ->
                        val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                        lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().setCategory(f.uri, name) }
                        toast("Moved to $name")
                    }.setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSearchDialog() {
        val et = EditText(this).apply { hint = "Search file names..."; setPadding(dp(20),dp(8),dp(20),dp(8)) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().lowercase()
                listContainer.removeAllViews()
                val results = allFiles.filter { it.displayName.lowercase().contains(q) }
                val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                results.forEach { listContainer.addView(buildFileCard(it, fmt)) }
            }
            override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int) {}
            override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("Search Files").setView(et)
            .setNegativeButton("Close") { _, _ -> refreshDisplay() }.show()
    }

    private fun saveRecentAndOpen(uri: Uri) {
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

    private fun showSettings() {
        val prefs  = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val isDark = prefs.getInt("theme_mode", 0) == 2
        android.app.AlertDialog.Builder(this).setTitle("Settings")
            .setItems(arrayOf(
                if (isDark) "Light Mode" else "Dark Mode",
                "Clear all recent files",
                "About ProPDF Editor v3.0"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val mode = if (isDark) 1 else 2
                        prefs.edit().putInt("theme_mode", mode).apply()
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                            if (mode==2) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                    }
                    1 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() }
                    2 -> toast("ProPDF Editor v3.0 - Free, No Ads, All Premium Features")
                }
            }.show()
    }

    private fun makeIconBtn(icon: Int, tint: Int, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(tint); setPadding(dp(8),dp(8),dp(8),dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(44),dp(44))
        setOnClickListener { action() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
