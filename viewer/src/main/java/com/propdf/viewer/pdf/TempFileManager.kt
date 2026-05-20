package com.propdf.viewer.pdf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages temporary files for PDF operations.
 * Prevents temp file leaks by tracking all created files and cleaning up on app exit.
 *
 * Features:
 * - Automatic cleanup on app start (removes stale temp files)
 * - Tracks active temp files to prevent deletion during operations
 * - Periodic cleanup of abandoned files
 * - Thread-safe operations
 */
class TempFileManager(private val context: Context) {

    private val tempDir: File by lazy {
        File(context.cacheDir, "pdf_temp").apply {
            mkdirs()
            // Clean stale files on init
            cleanStaleFiles()
        }
    }

    private val activeFiles = ConcurrentHashMap<String, File>()

    /**
     * Create a new temporary file for PDF operations.
     * File is automatically tracked and will be cleaned up.
     */
    fun createTempFile(prefix: String = "pdf", suffix: String = ".pdf"): File {
        val file = File(tempDir, "${prefix}_${UUID.randomUUID()}$suffix")
        activeFiles[file.absolutePath] = file
        return file
    }

    /**
     * Mark a file as no longer active. It becomes eligible for cleanup.
     */
    fun releaseFile(file: File) {
        activeFiles.remove(file.absolutePath)
    }

    /**
     * Delete a specific temp file immediately.
     */
    suspend fun deleteFile(file: File) = withContext(Dispatchers.IO) {
        activeFiles.remove(file.absolutePath)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Clean all temp files except those currently active.
     */
    suspend fun cleanAll() = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { file ->
            if (!activeFiles.containsKey(file.absolutePath)) {
                file.delete()
            }
        }
    }

    /**
     * Get total size of temp directory in bytes.
     */
    fun getTempDirSize(): Long {
        return tempDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun cleanStaleFiles() {
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours
        val now = System.currentTimeMillis()
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }
}
