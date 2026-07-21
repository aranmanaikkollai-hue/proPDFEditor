package com.propdf.editor

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.core.CrashGuard
import com.propdf.editor.core.dispatch.ThreadPoolManager
import com.propdf.editor.data.local.RecentFile
import com.propdf.editor.data.local.RecentFilesDao
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import javax.inject.Inject

/**
 * Optimized MainActivity with:
 * - Fast startup: deferred heavy loading
 * - Lazy loading: files loaded incrementally, not all at once
 * - ANR-safe search with debounced flow
 * - Memory-efficient RecyclerView with view recycling
 * - Proper lifecycle management to prevent leaks
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var recentDao: RecentFilesDao

    private val pdfFiles = mutableListOf<FileItem>()
    private var currentFilter = ""
    private var sortMode = SortMode.DATE_DESC
    private var viewMode = ViewMode.GRID
    private var isLoading = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private lateinit var searchInput: EditText
    private lateinit var tvEmpty: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var bottomNav: BottomNavigationView

    // Debounced search flow
    private val searchFlow = MutableStateFlow("")
    private val loadMutex = Mutex()

    enum class SortMode { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }
    enum class ViewMode { LIST, GRID }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) deferredLoadFiles() else {
            tvEmpty.text = "Storage permission required"
            tvEmpty.visibility = View.VISIBLE
        }
    }

    private val openPdf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val name = FileHelper.getFileName(this, uri) ?: "document.pdf"
        lifecycleScope.launch {
            val file = withContext(ThreadPoolManager.IoDispatcher) { copyUriToCache(uri, name) }
            if (file != null) {
                addRecent(file, name)
                ViewerActivity.start(this@MainActivity, Uri.fromFile(file), displayName = name)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fast UI init
        initViews()
        setupSearch()
        setupBottomNav()

        // Defer heavy loading
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
            deferredLoadFiles()
        } else {
            permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh recent files when returning
        lifecycleScope.launch { refreshRecentFiles() }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        searchInput = findViewById(R.id.searchInput)
        tvEmpty = findViewById(R.id.tvEmpty)
        fab = findViewById(R.id.fab)
        bottomNav = findViewById(R.id.bottomNav)

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        adapter = FileAdapter(pdfFiles, onClick = { openFile(it) }, onLongClick = { showFileOptions(it) })
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)

        fab.setOnClickListener { openPdf.launch(arrayOf("application/pdf")) }
    }

    // ─── Search (ANR-safe with debounce) ─────────────────────────
    private fun setupSearch() {
        lifecycleScope.launch {
            searchFlow
                .debounce(300)
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { query ->
                    currentFilter = query
                    applyFilterAndSort()
                }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchFlow.value = s?.toString()?.trim() ?: ""
            }
        })
    }

    // ─── Lazy File Loading (Incremental) ─────────────────────────
    private fun deferredLoadFiles() {
        CrashGuard.safeLaunch(lifecycleScope, ThreadPoolManager.IoDispatcher,
            timeoutMs = 30000L,
            onError = { 
                tvEmpty.text = "Error loading files"
                tvEmpty.visibility = View.VISIBLE
            }
        ) {
            loadMutex.withLock {
                if (isLoading) return@safeLaunch
                isLoading = true

                withContext(Dispatchers.Main) {
                    tvEmpty.text = "Loading..."
                    tvEmpty.visibility = View.VISIBLE
                }

                // Load in batches to prevent UI freezing
                val allFiles = mutableListOf<FileItem>()

                // Batch 1: Recent files (fast, from DB)
                val recent = withContext(Dispatchers.IO) { recentDao.getAll() }
                recent.forEach { r ->
                    val f = File(r.path)
                    if (f.exists()) {
                        allFiles.add(FileItem(f, r.name, f.lastModified(), f.length(), true))
                    }
                }

                withContext(Dispatchers.Main) {
                    pdfFiles.clear()
                    pdfFiles.addAll(allFiles)
                    adapter.notifyDataSetChanged()
                }

                // Batch 2: Storage scan (slow, incremental)
                val storageFiles = scanStoragePdfFiles()

                withContext(Dispatchers.Main) {
                    val existingPaths = pdfFiles.map { it.file.absolutePath }.toSet()
                    val newFiles = storageFiles.filter { it.file.absolutePath !in existingPaths }
                    if (newFiles.isNotEmpty()) {
                        val startIdx = pdfFiles.size
                        pdfFiles.addAll(newFiles)
                        adapter.notifyItemRangeInserted(startIdx, newFiles.size)
                    }
                    applyFilterAndSort()
                    isLoading = false
                }
            }
        }
    }

    private suspend fun scanStoragePdfFiles(): List<FileItem> = withContext(ThreadPoolManager.IoDispatcher) {
        val results = mutableListOf<FileItem>()
        try {
            // Query MediaStore for PDFs (faster than recursive file scan)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA
            )
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
            val selectionArgs = arrayOf("application/pdf")

            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 200"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol)
                    val file = File(path)
                    if (file.exists() && file.length() > 0) {
                        results.add(FileItem(
                            file = file,
                            name = cursor.getString(nameCol) ?: file.name,
                            date = cursor.getLong(dateCol) * 1000,
                            size = cursor.getLong(sizeCol),
                            isRecent = false
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback: limited directory scan
            val dirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            )
            dirs.forEach { dir ->
                if (dir?.exists() == true) {
                    dir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { f ->
                        results.add(FileItem(f, f.name, f.lastModified(), f.length(), false))
                    }
                }
            }
        }
        results
    }

    private suspend fun refreshRecentFiles() = withContext(Dispatchers.IO) {
        val recent = recentDao.getAll()
        withContext(Dispatchers.Main) {
            // Update recent status without full reload
            val updated = pdfFiles.map { item ->
                val isRecent = recent.any { it.path == item.file.absolutePath }
                item.copy(isRecent = isRecent)
            }
            pdfFiles.clear()
            pdfFiles.addAll(updated)
            adapter.notifyDataSetChanged()
        }
    }

    private fun applyFilterAndSort() {
        val filtered = if (currentFilter.isEmpty()) {
            pdfFiles.toList()
        } else {
            pdfFiles.filter { it.name.contains(currentFilter, true) }
        }

        val sorted = when (sortMode) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortMode.DATE_ASC -> filtered.sortedBy { it.date }
            SortMode.DATE_DESC -> filtered.sortedByDescending { it.date }
            SortMode.SIZE_ASC -> filtered.sortedBy { it.size }
            SortMode.SIZE_DESC -> filtered.sortedByDescending { it.size }
        }

        adapter.updateData(sorted)
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    // ─── Actions ───────────────────────────────────────────────────
    private fun openFile(item: FileItem) {
        if (!item.file.exists()) {
            toast("File not found")
            pdfFiles.remove(item)
            adapter.notifyDataSetChanged()
            return
        }
        addRecent(item.file, item.name)
        ViewerActivity.start(this, Uri.fromFile(item.file), displayName = item.name)
    }

    private fun showFileOptions(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(arrayOf("Open", "Share", "Delete", "Properties")) { _, which ->
                when (which) {
                    0 -> openFile(item)
                    1 -> shareFile(item.file)
                    2 -> deleteFile(item)
                    3 -> showProperties(item)
                }
            }.show()
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.provider", file
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share PDF"))
        } catch (e: Exception) { toast("Share error: ${e.message}") }
    }

    private fun deleteFile(item: FileItem) {
        AlertDialog.Builder(this).setTitle("Delete?")
            .setMessage("Remove ${item.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        recentDao.deleteByPath(item.file.absolutePath)
                        item.file.delete()
                    }
                    pdfFiles.remove(item)
                    adapter.notifyDataSetChanged()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showProperties(item: FileItem) {
        val sizeStr = when {
            item.size > 1024 * 1024 -> "%.2f MB".format(item.size / (1024.0 * 1024))
            item.size > 1024 -> "%.2f KB".format(item.size / 1024.0)
            else -> "$item.size bytes"
        }
        AlertDialog.Builder(this).setTitle("Properties")
            .setMessage("Name: ${item.name}\nSize: $sizeStr\nModified: ${Date(item.date)}\nPath: ${item.file.absolutePath}")
            .setPositiveButton("OK", null).show()
    }

    private fun addRecent(file: File, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            recentDao.insert(RecentFile(
                path = file.absolutePath,
                name = name,
                date = System.currentTimeMillis()
            ))
        }
    }

    private fun copyUriToCache(uri: Uri, name: String): File? {
        return try {
            val dest = File(cacheDir, "main_${System.currentTimeMillis()}_$name")
            contentResolver.openInputStream(uri)?.use { FileOutputStream(dest).use { o -> it.copyTo(o) } }
            if (dest.length() > 0) dest else null
        } catch (_: Exception) { null }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { /* Already home */ true }
                R.id.nav_scanner -> { startActivity(Intent(this, DocumentScannerActivity::class.java)); true }
                R.id.nav_tools -> { startActivity(Intent(this, ToolsActivity::class.java)); true }
                R.id.nav_settings -> { showSettings(); true }
                else -> false
            }
        }
    }

    private fun showSettings() {
        val modes = arrayOf("System default", "Light", "Dark")
        AlertDialog.Builder(this).setTitle("Theme")
            .setItems(modes) { _, which ->
                val mode = when (which) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                getSharedPreferences("propdf_prefs", MODE_PRIVATE).edit()
                    .putInt("theme_mode", which).apply()
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ─── Data ──────────────────────────────────────────────────────
    data class FileItem(
        val file: File,
        val name: String,
        val date: Long,
        val size: Long,
        val isRecent: Boolean
    )

    inner class FileAdapter(
        private var items: List<FileItem>,
        private val onClick: (FileItem) -> Unit,
        private val onLongClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        fun updateData(newItems: List<FileItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
            return VH(view)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName = itemView.findViewById<TextView>(R.id.tvName)
            private val tvMeta = itemView.findViewById<TextView>(R.id.tvMeta)
            private val ivIcon = itemView.findViewById<ImageView>(R.id.ivIcon)

            fun bind(item: FileItem) {
                tvName.text = item.name
                val sizeStr = when {
                    item.size > 1024 * 1024 -> "%.1f MB".format(item.size / (1024.0 * 1024))
                    item.size > 1024 -> "%.1f KB".format(item.size / 1024.0)
                    else -> "${item.size} B"
                }
                tvMeta.text = "$sizeStr • ${android.text.format.DateUtils.getRelativeTimeSpanString(item.date)}"
                ivIcon.setImageResource(if (item.isRecent) android.R.drawable.ic_menu_recent_history else android.R.drawable.ic_menu_save)

                itemView.setOnClickListener { onClick(item) }
                itemView.setOnLongClickListener { onLongClick(item); true }
            }
        }
    }
}
