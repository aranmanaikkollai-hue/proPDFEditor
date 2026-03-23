package com.propdf.editor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.R
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ── File picker — returns content URI ─────────────────────
    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        // Persist permission so we can re-read it later
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
        ViewerActivity.start(this, uri)
    }

    // ── Storage permission (API 22 and below only) ────────────
    private val storagePerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openPicker() else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupBottomNav()
        setupFab()

        // Handle PDF shared/opened from another app
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            ViewerActivity.start(this, intent.data!!)
        }
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fab_open_pdf)
            ?.setOnClickListener { checkPermissionAndOpen() }
    }

    private fun setupBottomNav() {
        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            ?.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home     -> true
                    R.id.nav_tools    -> { startActivity(Intent(this, ToolsActivity::class.java)); true }
                    R.id.nav_scan     -> { startActivity(Intent(this, DocumentScannerActivity::class.java)); true }
                    R.id.nav_settings -> { showSettings(); true }
                    else -> false
                }
            }
    }

    // ── Permission check ──────────────────────────────────────

    private fun checkPermissionAndOpen() {
        // API 33+ uses READ_MEDIA_IMAGES (but for PDFs we use SAF — no permission needed)
        // API 23-32: READ_EXTERNAL_STORAGE needed for direct file access
        // We use SAF (Storage Access Framework) so no permission needed at all
        openPicker()
    }

    private fun openPicker() {
        // SAF picker — works with content:// from any source without permissions
        pdfPicker.launch(arrayOf("application/pdf"))
    }

    private fun showPermissionRationale() {
        Toast.makeText(this, "Storage permission needed to open PDF files.", Toast.LENGTH_LONG).show()
    }

    private fun showSettings() {
        val prefs  = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        val isDark = prefs.getInt("theme_mode", 0) == 2
        android.app.AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setItems(arrayOf(
                if (isDark) "☀️  Switch to Light Mode" else "🌙  Switch to Dark Mode",
                "📁  Output folder: App Files",
                "ℹ️  About ProPDF Editor v2.0"
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
                    2 -> Toast.makeText(this,
                        "ProPDF Editor v2.0 — Free, No Ads, All Premium Features",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
