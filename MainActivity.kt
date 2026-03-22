package com.propdf.editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.R
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { openPdfInViewer(it) } }

    private val multiPdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) openToolsWithFiles(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openPdfInViewer(it) }
        }

        setupBottomNav()
        setupFab()
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fab_open_pdf)?.setOnClickListener {
            pdfPickerLauncher.launch("application/pdf")
        }
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    true
                }
                R.id.nav_recent -> {
                    Toast.makeText(this, "Recent files coming soon", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val isDark = prefs.getInt("theme_mode", 0) == 2
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf(
                if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                "About ProPDF Editor"
            )) { _, which ->
                when (which) {
                    0 -> {
                        val newMode = if (isDark) 1 else 2
                        prefs.edit().putInt("theme_mode", newMode).apply()
                        AppCompatDelegate.setDefaultNightMode(
                            if (newMode == 2) AppCompatDelegate.MODE_NIGHT_YES
                            else AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                    1 -> Toast.makeText(this, "ProPDF Editor v1.0 — Free & Open Source", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    fun openPdfInViewer(uri: Uri) {
        startActivity(Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PDF_URI, uri.toString()))
    }

    private fun openToolsWithFiles(uris: List<Uri>) {
        startActivity(Intent(this, ToolsActivity::class.java).apply {
            putStringArrayListExtra("pdf_uris", ArrayList(uris.map { it.toString() }))
        })
    }
}
