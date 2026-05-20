package com.propdf.viewer.pdf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TempFileManager(private val context: Context) {

    private val tempDir: File by lazy {
        File(context.cacheDir, "pdf_temp").apply {
            mkdirs()
            cleanStaleFiles()
        }
    }

    private val activeFiles = ConcurrentHashMap<String, File>()

    fun createTempFile(prefix: String = "pdf", suffix: String = ".pdf"): File {
        val file = File(tempDir, "${prefix}_${UUID.randomUUID()}$suffix")
        activeFiles[file.absolutePath] = file
        return file
    }

    fun releaseFile(file: File) {
        activeFiles.remove(file.absolutePath)
    }

    suspend fun deleteFile(file: File) = withContext(Dispatchers.IO) {
        activeFiles.remove(file.absolutePath)
        if (file.exists()) file.delete()
    }

    suspend fun cleanAll() = withContext(Dispatchers.IO) {
        tempDir.listFiles()?.forEach { file ->
            if (!activeFiles.containsKey(file.absolutePath)) {
                file.delete()
            }
        }
    }

    fun getTempDirSize(): Long {
        return tempDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun cleanStaleFiles() {
        val maxAgeMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }
}
