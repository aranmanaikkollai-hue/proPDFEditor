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
    
    // UI State from Additions
    private var viewMode: String = "list"   
    private var sortMode: String = "date"   
    private var sortAsc: Boolean = false
    private var allFileEntities: List<RecentFileEntity> = emptyList()

    private lateinit var rootFrame: FrameLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var viewToggleBtn: ImageButton
    private lateinit var sortBtn: TextView

    private val PRIMARY = "#448AFF"

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

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Header Section
        mainLayout.addView(LinearLayout(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "ProPDF"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(PRIMARY)); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(if (isDark) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_day)
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.parseColor(txt2()))
                setOnClickListener { toggleTheme() }
            })
        })

        // Tab Selector Row
        val tabs = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        listOf("Recent", "Starred", "Vault").forEach { label ->
            tabs.addView(TextView(this@MainActivity).apply {
                text = label; setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(Color.parseColor(txt2()))
                setOnClickListener { currentTab = label.lowercase(); rebuildFileList() }
            })
        }
        mainLayout.addView(tabs)

        // Controls Row (Sort and View Mode)
        mainLayout.addView(LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
            sortBtn = TextView(this@MainActivity).apply {
                text = "Sort by Date ▼"; textSize = 11f; setTextColor(Color.parseColor(txt2()))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { showSortMenu() }
            }
            viewToggleBtn = ImageButton(this@MainActivity).apply {
                // FIXED: Using standard ic_menu_agenda instead of unresolved ic_menu_grid
                setImageResource(android.R.drawable.ic_menu_agenda)
                setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.parseColor(PRIMARY))
                setOnClickListener { 
                    viewMode = if (viewMode == "list") "grid" else "list"
                    rebuildFileList()
                }
            }
            addView(sortBtn); addView(viewToggleBtn)
        })

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        fileListContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(100)) 
        }
        tvEmpty = TextView(this).apply { 
            text = "No Files Found"; gravity = Gravity.CENTER; setTextColor(Color.parseColor(txt2()))
            setPadding(0, dp(50), 0, 0); visibility = View.GONE
        }

        val bodyFrame = FrameLayout(this)
        bodyFrame.addView(tvEmpty); bodyFrame.addView(fileListContainer)
        scroll.addView(bodyFrame); mainLayout.addView(scroll)
        rootFrame.addView(mainLayout)

        // Floating Action Button
        rootFrame.addView(MaterialCardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) }
            radius = dp(28).toFloat(); setCardBackgroundColor(Color.parseColor(PRIMARY)); elevation = dp(6).toFloat()
            setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_input_add); setColorFilter(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            })
        })

        setContentView(rootFrame)
    }

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { entities ->
                allFileEntities = entities
                rebuildFileList()
            }
        }
    }

    private fun rebuildFileList() {
        fileListContainer.removeAllViews()
        val filtered = if (currentTab == "starred") allFileEntities.filter { it.isFavourite } else allFileEntities
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        
        filtered.forEach { file ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
                setCardBackgroundColor(Color.parseColor(if (isDark) "#2A2A2A" else "#FFFFFF"))
                radius = dp(12).toFloat()
                setOnClickListener { ViewerActivity.start(this@MainActivity, Uri.parse(file.uri)) }
                addView(TextView(this@MainActivity).apply {
                    text = file.displayName; setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(Color.parseColor(txt1()))
                })
            }
            fileListContainer.addView(card)
        }
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

    private fun showSortMenu() {
        val options = arrayOf("Name", "Date", "Size")
        AlertDialog.Builder(this).setTitle("Sort By").setItems(options) { _, i ->
            sortMode = options[i].lowercase()
            rebuildFileList()
        }.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
