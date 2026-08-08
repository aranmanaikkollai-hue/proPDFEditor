package com.propdfeditor.startup

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary startup diagnostics.
 * Remove or disable for release builds after crash is resolved.
 */
object StartupDiagnostics {
    private const val TAG = "StartupDiagnostics"
    private const val DIAG_FILE = "startup_diag.txt"

    fun log(stage: String, context: Context? = null) {
        val msg = "${timestamp()} | $stage"
        Log.d(TAG, msg)
        context?.let { appendToFile(it, msg) }
    }

    fun logException(stage: String, throwable: Throwable, context: Context? = null) {
        val msg = "${timestamp()} | EXCEPTION at $stage: ${throwable.javaClass.name}: ${throwable.message}"
        Log.e(TAG, msg, throwable)
        context?.let {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            appendToFile(it, "$msg\n${sw.toString().take(2000)}")
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun appendToFile(context: Context, text: String) {
        try {
            val file = File(context.cacheDir, DIAG_FILE)
            file.appendText("$text\n")
        } catch (e: Exception) {
            // Ignore diagnostic write failures
        }
    }
}
