package com.propdf.editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.R
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // SAF picker — no storage permission needed, works on all Android versions
    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Persist read permission across reboots
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        ViewerActivity.start(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Open PDF shared from another app
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            ViewerActivity.start(this, intent.data!!)
        }

        // FAB opens the file picker
        findViewById<FloatingActionButton>(R.id.fab_open_pdf)
            ?.setOnClickListener {
                pdfPicker.launch(arrayOf("application/pdf"))
            }

        // Bottom navigation
        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            ?.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home  -> true
                    R.id.nav_tools -> {
                        startActivity(Intent(this, ToolsActivity::class.java))
                        true
                    }
                    R.id.nav_scan  -> {
                        startActivity(Intent(this,
                            DocumentScannerActivity::class.java))
                        true
                    }
                    R.id.nav_settings -> {
                        showSettings()
                        true
                    }
                    else -> false
                }
            }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            ViewerActivity.start(this, intent.data!!)
        }
    }

    private fun showSettings() {
        val prefs  = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val isDark = prefs.getInt("theme_mode", 0) == 2
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf(
                if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                "About ProPDF Editor v2.0"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val mode = if (isDark) 1 else 2
                        prefs.edit().putInt("theme_mode", mode).apply()
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                            if (mode == 2)
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            else
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                    1 -> Toast.makeText(this,
                        "ProPDF Editor v2.0 — Free, No Ads",
                        Toast.LENGTH_SHORT).show()
                }
            }.show()
    }
}
