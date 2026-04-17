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

    private val db by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("propdf_prefs", MODE_PRIVATE) }

    private var isDark = true
    private var currentTab = "recent"
    private var viewMode: String = "list"   // "list" | "grid" | "tile"
    private var sortMode: String = "date"   
    private var sortAsc: Boolean = false
    private var allFileEntities: List<RecentFileEntity> = emptyList()

    private lateinit var rootFrame: FrameLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvSection: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var viewToggleBtn: ImageButton
    private lateinit var sortBtn: TextView

    private val PRIMARY = "#448AFF"
    private val c_pri = Color.parseColor(PRIMARY)

    private fun bg() = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F7")
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"

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

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Header
        column.addView(LinearLayout(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "ProPDF"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(c_pri); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(if (isDark) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_day)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { toggleTheme() }
            })
        })

        // Tab Bar
        tabRow = LinearLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            listOf("Recent" to "recent", "Starred" to "starred", "Vault" to "categories").forEach { (name, id) ->
                addView(TextView(this@MainActivity).apply {
                    text = name; setPadding(dp(16), dp(8), dp(16), dp(8))
                    setTextColor(if (currentTab == id) c_pri else Color.parseColor(txt2()))
                    setOnClickListener { currentTab = id; rebuildFileList() }
                })
            }
        }
        column.addView(tabRow)

        // Sort/View Controls
        column.addView(LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
            sortBtn = TextView(this@MainActivity).apply {
                text = "Sort by Date ▼"; textSize = 12f; setTextColor(Color.parseColor(txt2()))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { showSortMenu() }
            }
            viewToggleBtn = ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_agenda) // FIX: Replaced ic_menu_grid
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(c_pri)
                setOnClickListener { cycleViewMode() }
            }
            addView(sortBtn); addView(viewToggleBtn)
        })

        fileListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(fileListContainer) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        rootFrame.addView(column)
        rootFrame.addView(buildFab())
        setContentView(rootFrame)
    }

    private fun buildFab() = MaterialCardView(this).apply {
        layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) }
        radius = dp(28).toFloat(); setCardBackgroundColor(c_pri)
        setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
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
        allFileEntities.forEach { entity ->
            fileListContainer.addView(buildFileCard(entity))
        }
    }

    private fun buildFileCard(f: RecentFileEntity) = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(12)) }
        radius = dp(12).toFloat(); setCardBackgroundColor(Color.parseColor(if (isDark) "#2A2A2A" else "#FFFFFF"))
        setOnClickListener { ViewerActivity.start(this@MainActivity, Uri.parse(f.uri)) }
        addView(TextView(this@MainActivity).apply { text = f.displayName; setPadding(dp(16), dp(16), dp(16), dp(16)); setTextColor(Color.parseColor(txt1())) })
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

    private fun toggleTheme() {
        isDark = !isDark
        prefs.edit().putBoolean("dark_mode", isDark).apply()
        recreate()
    }

    private fun cycleViewMode() {
        viewMode = if (viewMode == "list") "grid" else "list"
        rebuildFileList()
    }

    private fun showSortMenu() {
        AlertDialog.Builder(this).setTitle("Sort By").setItems(arrayOf("Name", "Date", "Size")) { _, _ -> }.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
