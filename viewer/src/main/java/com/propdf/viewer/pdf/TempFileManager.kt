package com.propdf.viewer.pdf

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class TempFileManager(private val context: Context) {

    val tempDir: File by lazy {
        File(context.cacheDir, "pdf_temp").apply { mkdirs() }
    }

    fun createTempFile(prefix: String = "pdf_", suffix: String = ".pdf"): File {
        return File.createTempFile(prefix, suffix, tempDir)
    }

    fun cleanAll() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    fun cleanStale(maxAgeMs: Long = TimeUnit.HOURS.toMillis(24)) {
        val now = System.currentTimeMillis()
        tempDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
            }
        }
    }
}
