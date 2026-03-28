package com.propdf.editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
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

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        saveRecentAndOpen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupViews()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            saveRecentAndOpen(intent.data!!)
        }

        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files -> refreshRecentList(files) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            saveRecentAndOpen(intent.data!!)
        }
    }

    private fun setupViews() {
        // Quick action cards
        findViewById<MaterialCardView>(R.id.card_scan)?.setOnClickListener {
            startActivity(Intent(this, DocumentScannerActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.card_import)?.setOnClickListener {
            pdfPicker.launch(arrayOf("application/pdf"))
        }
        findViewById<MaterialCardView>(R.id.card_tools_quick)?.setOnClickListener {
            startActivity(Intent(this, ToolsActivity::class.java))
        }

        // FAB
        findViewById<FloatingActionButton>(R.id.fab_open_pdf)
            ?.setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }

        // Clear recent
        findViewById<TextView>(R.id.btn_clear_recent)?.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() }
        }

        // Settings
        findViewById<android.widget.ImageButton>(R.id.btn_settings)?.setOnClickListener {
            showSettings()
        }

        // Bottom nav
        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            ?.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home  -> true
                    R.id.nav_tools -> { startActivity(Intent(this, ToolsActivity::class.java)); true }
                    R.id.nav_scan  -> {
                        try {
                            val cls = Class.forName("com.propdf.editor.ui.scanner.DocumentScannerActivity")
                            startActivity(Intent(this, cls))
                        } catch (_: Exception) {}
                        true
                    }
                    R.id.nav_settings -> { showSettings(); true }
                    else -> false
                }
            }
    }

    private fun saveRecentAndOpen(uri: Uri) {
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
                    RecentFileEntity(uri = uri.toString(), displayName = name, fileSizeBytes = size)
                )
            }
            ViewerActivity.start(this@MainActivity, uri)
        }
    }

    private fun refreshRecentList(files: List<RecentFileEntity>) {
        val container = findViewById<LinearLayout>(R.id.recent_list_container) ?: return
        val empty     = findViewById<LinearLayout>(R.id.tv_empty_state)
        container.removeAllViews()

        if (files.isEmpty()) { empty?.visibility = View.VISIBLE; return }
        empty?.visibility = View.GONE

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        files.forEach { f -> container.addView(buildFileCard(f, fmt)) }
    }

    private fun buildFileCard(f: RecentFileEntity, fmt: SimpleDateFormat): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            }
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(android.graphics.Color.WHITE)
            strokeWidth = 0
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // PDF badge
        val badge = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(50)).apply {
                setMargins(0, 0, dp(14), 0)
            }
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#FDECEA"))
        }
        badge.addView(TextView(this).apply {
            text = "PDF"; textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#D32F2F"))
            gravity = android.view.Gravity.CENTER
        })

        // File info
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        info.addView(TextView(this).apply {
            text = f.displayName; textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#111111"))
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            val sizeStr = when {
                f.fileSizeBytes > 1_000_000 -> "%.1f MB".format(f.fileSizeBytes / 1_000_000.0)
                f.fileSizeBytes > 1_000     -> "%.0f KB".format(f.fileSizeBytes / 1_000.0)
                else                        -> "${f.fileSizeBytes} B"
            }
            text = "$sizeStr  |  ${fmt.format(Date(f.lastOpenedAt))}"
            textSize = 11f; setTextColor(android.graphics.Color.parseColor("#999999"))
            setPadding(0, dp(3), 0, 0)
        })

        // Delete button
        val btnDel = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(android.graphics.Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().delete(f.uri) }
            }
        }

        row.addView(badge); row.addView(info); row.addView(btnDel)
        card.addView(row)
        card.setOnClickListener { saveRecentAndOpen(Uri.parse(f.uri)) }
        return card
    }

    private fun showSettings() {
        val prefs  = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val isDark = prefs.getInt("theme_mode", 0) == 2
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf(
                if (isDark) "Light Mode" else "Dark Mode",
                "Clear recent files",
                "About ProPDF Editor v3.0"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val mode = if (isDark) 1 else 2
                        prefs.edit().putInt("theme_mode", mode).apply()
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                            if (mode == 2) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                    1 -> lifecycleScope.launch(Dispatchers.IO) { db.recentFilesDao().clearAll() }
                    2 -> Toast.makeText(this, "ProPDF Editor v3.0 - Free, No Ads", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
