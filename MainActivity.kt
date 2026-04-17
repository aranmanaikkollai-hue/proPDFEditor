package com.propdf.editor.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
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
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val db   by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("propdf_prefs", MODE_PRIVATE) }

    private var isDark     = true
    private var currentTab = "recent"
    
    // UI State for Additions
    private var viewMode: String = "list"   // "list" | "grid" | "tile"
    private var sortMode: String = "date"   // "date" | "name" | "size"
    private var sortAsc: Boolean = false
    private var allFileEntities: List<RecentFileEntity> = emptyList()

    private lateinit var rootFrame     : FrameLayout
    private lateinit var fileListContainer : LinearLayout
    private lateinit var tvEmpty       : TextView
    private lateinit var tvSection     : TextView
    private lateinit var tabRow        : LinearLayout
    private lateinit var themeBtn      : ImageButton
    private lateinit var sortBtn       : TextView
    private lateinit var viewToggleBtn : ImageButton

    // Styling Colors
    private fun bg()       = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F7")
    private fun card()     = if (isDark) "#2A2A2A" else "#FFFFFF"
    private fun txt1()     = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2()     = if (isDark) "#A0A0A0" else "#6B7280"
    private fun navBg()    = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun divLine()  = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private val PRIMARY    = "#448AFF"
    private val DANGER     = "#E53935"

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { openUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        observeFiles()
    }

private fun buildUI() {
        window.statusBarColor = bg()
        rootFrame = FrameLayout(this).apply { setBackgroundColor(bg()) }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        mainLayout.addView(buildHeader())
        mainLayout.addView(buildTabBar())
        mainLayout.addView(buildSectionRow())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(90))
        }

        tvEmpty = TextView(this).apply {
            text = "No files found"; gravity = Gravity.CENTER
            setTextColor(Color.parseColor(txt2()))
            setPadding(0, dp(64), 0, 0)
            visibility = View.GONE
        }

        fileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }

        body.addView(tvEmpty)
        body.addView(fileListContainer)
        scroll.addView(body)
        mainLayout.addView(scroll)

        rootFrame.addView(mainLayout)
        rootFrame.addView(buildBottomNav())
        setContentView(rootFrame)
    }

    private fun buildHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        
        val title = TextView(this@MainActivity).apply {
            text = "ProPDF"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(PRIMARY))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        addView(title)
        
        themeBtn = ImageButton(this@MainActivity).apply {
            setImageResource(if (isDark) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_day)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor(txt2()))
            setOnClickListener { toggleTheme() }
        }
        addView(themeBtn)
    }

    private fun buildTabBar() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        tabRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        listOf("Recent" to "recent", "Starred" to "starred", "Vault" to "categories").forEach { (label, id) ->
            val tv = TextView(this@MainActivity).apply {
                text = label; setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(if (currentTab == id) Color.parseColor(PRIMARY) else Color.parseColor(txt2()))
                typeface = if (currentTab == id) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setOnClickListener { switchTab(id) }
                tag = "${id}_tv"
            }
            tabRow.addView(tv)
        }
        addView(tabRow)
        addView(buildSortViewControlRow())
    }

private fun buildSortViewControlRow() = LinearLayout(this).apply {
        setPadding(dp(16), 0, dp(16), dp(8))
        sortBtn = TextView(this@MainActivity).apply {
            text = "Sort by Date ▼"; textSize = 12f
            setTextColor(Color.parseColor(txt2()))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { showSortMenu() }
        }
        viewToggleBtn = ImageButton(this@MainActivity).apply {
            setImageResource(android.R.drawable.ic_menu_grid)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor(PRIMARY))
            setOnClickListener { cycleViewMode() }
        }
        addView(sortBtn)
        addView(viewToggleBtn)
    }

    private fun buildSectionRow() = LinearLayout(this).apply {
        setPadding(dp(16), dp(12), dp(16), dp(8))
        tvSection = TextView(this@MainActivity).apply {
            text = "Recent Files"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(txt1()))
        }
        addView(tvSection)
    }

    private fun buildBottomNav() = FrameLayout(this).apply {
        layoutParams = FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM)
        setBackgroundColor(navBg())
        
        val fab = MaterialCardView(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
            radius = dp(28).toFloat(); setCardBackgroundColor(Color.parseColor(PRIMARY))
            elevation = dp(8).toFloat()
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            }
            addView(icon)
        }
        addView(fab)
    }

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFileEntities = files
                rebuildFileList()
            }
        }
    }

    private fun rebuildFileList() {
        fileListContainer.removeAllViews()
        val filtered = when(currentTab) {
            "starred" -> allFileEntities.filter { it.isFavourite }
            else -> allFileEntities
        }
        
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        
        filtered.forEach { entity ->
            fileListContainer.addView(buildFileCard(entity))
        }
    }

    private fun buildFileCard(f: RecentFileEntity) = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
        radius = dp(12).toFloat(); setCardBackgroundColor(Color.parseColor(card()))
        
        val content = LinearLayout(this@MainActivity).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            
            val icon = TextView(this@MainActivity).apply {
                text = "PDF"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.parseColor(DANGER)); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            
            val info = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
                addView(TextView(this@MainActivity).apply { text = f.displayName; setTextColor(Color.parseColor(txt1())) })
                addView(TextView(this@MainActivity).apply { text = formatSize(f.fileSizeBytes); textSize = 11f; setTextColor(Color.parseColor(txt2())) })
            }
            
            addView(icon); addView(info)
        }
        addView(content)
        setOnClickListener { openUri(Uri.parse(f.uri)) }
    }

    private fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        recreate()
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        rebuildFileList()
    }

    private fun cycleViewMode() {
        viewMode = when(viewMode) {
            "list" -> "grid"
            else -> "list"
        }
        rebuildFileList()
    }

    private fun showSortMenu() {
        val items = arrayOf("Name", "Date", "Size")
        AlertDialog.Builder(this).setTitle("Sort By").setItems(items) { _, which ->
            sortMode = items[which].lowercase()
            rebuildFileList()
        }.show()
    }

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            val name = FileHelper.getFileName(this@MainActivity, uri) ?: "Document.pdf"
            withContext(Dispatchers.IO) {
                db.recentFilesDao().insert(RecentFileEntity(uri.toString(), name, 0L))
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun formatSize(b: Long) = "${b/1024} KB"
}