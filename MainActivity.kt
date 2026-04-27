package com.propdf.editor.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.propdf.editor.data.local.RecentFileEntity
import com.propdf.editor.data.local.RecentFilesDatabase
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // region Properties and State
    private val db by lazy { RecentFilesDatabase.get(this) }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("propdf_prefs", MODE_PRIVATE) }
    private val bookmarkPrefs: SharedPreferences by lazy { getSharedPreferences("propdf_bookmarks", MODE_PRIVATE) }

    private var isDark = true
    private var currentTab = "recent"
    private var viewMode = "list"
    private var sortMode = "date"
    private var sortAsc = false
    private var catDetailName = ""

    private val expandedCategories = mutableSetOf<String>()
    private var allFileEntities: List<RecentFileEntity> = emptyList()
    private val thumbnailCache = mutableMapOf<String, Bitmap>()

    private lateinit var rootFrame: FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvSection: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var viewToggleBtn: ImageButton
    private lateinit var sortBtn: TextView
    // endregion

    // region Colors & Constants
    private fun bg() = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F5F7")
    private fun card() = if (isDark) "#2A2A2A" else "#FFFFFF"
    private fun cardBrd() = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E8E8EC")
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private fun navBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun divLine() = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")
    private fun tabPill() = if (isDark) Color.parseColor("#2C2C2E") else Color.TRANSPARENT
    private fun tabActTxt() = Color.parseColor("#448AFF")
    private fun tabInaTxt() = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")

    private val PRIMARY = "#448AFF"; private val ACCENT = "#FFD60A"; private val DANGER = "#E53935"
    private val c_pri get() = Color.parseColor(PRIMARY)
    private val c_acc get() = Color.parseColor(ACCENT)
    // endregion

    // region Activity Result Launchers
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        openUri(uri)
    }
    private val ocrPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { _: Uri? ->
        toast("OCR from gallery -- This feature requires a full OCR Manager implementation.")
    }
    // endregion

    // region Lifecycle Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        buildUI()
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openUri(intent.data!!)
        }
        observeFiles()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            openUri(intent.data!!)
        }
    }
    // endregion

    // region UI Construction (Condensed for clarity, full logic is in your code)
    private fun buildUI() { /* Your existing UI build logic goes here */ }
    private fun applySystemBarColors() { /* Your existing logic */ }
    private fun buildHeader(): LinearLayout { /* Your existing logic */ return LinearLayout(this) }
    private fun buildTabBar(): LinearLayout { /* Your existing logic */ return LinearLayout(this) }
    private fun buildSortViewRow(): LinearLayout { /* Your existing logic */ return LinearLayout(this) }
    private fun buildSectionRow(): LinearLayout { /* Your existing logic */ return LinearLayout(this) }
    private fun buildBottomNav(): FrameLayout { /* Your existing logic */ return FrameLayout(this) }
    // endregion

    // region Data & File Logic

    private fun observeFiles() {
        lifecycleScope.launch {
            db.recentFilesDao().getAll().collect { files ->
                allFileEntities = files
                rebuildFileList()
            }
        }
    }

    private fun sortedFiles(): List<RecentFileEntity> {
        val base = when (currentTab) {
            "starred" -> allFileEntities.filter { it.isFavourite }
            "cat_detail" -> allFileEntities.filter { it.category == catDetailName }
            "bookmarks" -> getBookmarkedFiles() // FIX #1: Now calls the correct function.
            else -> allFileEntities.filter { it.lastOpenedAt > 0L }
        }
        return when (sortMode) {
            "name" -> if (sortAsc) base.sortedBy { it.displayName.lowercase(Locale.getDefault()) } else base.sortedByDescending { it.displayName.lowercase(Locale.getDefault()) }
            "size" -> if (sortAsc) base.sortedBy { it.fileSizeBytes } else base.sortedByDescending { it.fileSizeBytes }
            else -> if (sortAsc) base.sortedBy { it.lastOpenedAt } else base.sortedByDescending { it.lastOpenedAt }
        }
    }

    /**
     * FIX #1: Implemented the missing `getBookmarkedFiles` function.
     * This function checks the bookmark preferences for each file and returns a list
     * of files that have at least one bookmark.
     */
    private fun getBookmarkedFiles(): List<RecentFileEntity> {
        val bookmarkedFileUris = allFileEntities
            .filter { entity ->
                val key = entity.uri.hashCode().toString()
                bookmarkPrefs.getStringSet(key, null)?.isNotEmpty() ?: false
            }
            .map { it.uri }
            .toSet()

        return allFileEntities.filter { it.uri in bookmarkedFileUris }
    }
    
    private fun sortedFilesForCat(files: List<RecentFileEntity>): List<RecentFileEntity> {
        // Your logic here is correct.
        return files
    }

    private fun rebuildFileList() {
        if (!::fileListContainer.isInitialized) return
        fileListContainer.removeAllViews()

        // Rest of your logic is fine, will work with the fixed sortedFiles()
        // ...
    }

    // endregion

    // region UI Component Builders (File Cards, etc.)

    private fun buildFileCard(f: RecentFileEntity): View {
        // Your logic here is correct and detailed.
        val card = MaterialCardView(this)
        // ...
        card.setOnLongClickListener {
            showFileOptions(f)
            true // FIX #3: Added boolean return value
        }
        return card
    }
    
    private fun buildFileCardCompact(entity: RecentFileEntity): LinearLayout {
        // Your logic here is correct and detailed.
        val card = LinearLayout(this)
        // ...
        card.setOnLongClickListener {
            showFileOptions(entity)
            true // FIX #3: Added boolean return value
        }
        return card
    }

    private fun buildFileTileRow(entity: RecentFileEntity): LinearLayout {
        // Your logic here is correct and detailed.
        val row = LinearLayout(this)
        // ...
        row.setOnLongClickListener {
            showFileOptions(entity)
            true // FIX #3: Added boolean return value
        }
        return row
    }

    //endregion

    // region Dialogs & Actions
    
    // All your dialog functions (showFileOptions, showRenameDialog, etc.) are well-structured
    // and are kept as is.
    
    // endregion

    // region Navigation & Helpers

    private fun openUri(uri: Uri) {
        lifecycleScope.launch {
            val name = FileHelper.getFileName(this@MainActivity, uri) ?: "document.pdf"
            val size = try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val col = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst() && col != -1) c.getLong(col) else 0L
                } ?: 0L
            } catch (_: Exception) { 0L }

            db.recentFilesDao().insert(RecentFileEntity(uri = uri.toString(), displayName = name, fileSizeBytes = size, lastOpenedAt = System.currentTimeMillis()))
            ViewerActivity.start(this@MainActivity, uri, displayName = name)
        }
    }
    
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    // ... other helpers
    
    //endregion
}
