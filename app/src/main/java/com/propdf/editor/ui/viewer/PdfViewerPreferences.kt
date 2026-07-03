package com.propdf.editor.ui.viewer

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists viewer state that should survive app restarts:
 *  - Per-document: zoom level, last page read, bookmarked pages, rotation.
 *  - Global: preferred view mode, color mode, keep-screen-on, auto-brightness.
 *
 * Documents are keyed by a stable string derived from their path/URI, so the same PDF
 * reopened later resumes where it left off ("zoom memory").
 */
class PdfViewerPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pdf_viewer_prefs", Context.MODE_PRIVATE)

    private fun docKey(documentKey: String, suffix: String) = "doc:${documentKey.hashCode()}:$suffix"

    // ---- Per-document ----

    fun getZoom(documentKey: String): Float =
        prefs.getFloat(docKey(documentKey, "zoom"), 1f)

    fun setZoom(documentKey: String, zoom: Float) {
        prefs.edit().putFloat(docKey(documentKey, "zoom"), zoom).apply()
    }

    fun getLastPage(documentKey: String): Int =
        prefs.getInt(docKey(documentKey, "last_page"), 0)

    fun setLastPage(documentKey: String, page: Int) {
        prefs.edit().putInt(docKey(documentKey, "last_page"), page).apply()
    }

    fun getRotation(documentKey: String): Int =
        prefs.getInt(docKey(documentKey, "rotation"), 0)

    fun setRotation(documentKey: String, degrees: Int) {
        prefs.edit().putInt(docKey(documentKey, "rotation"), degrees).apply()
    }

    fun getBookmarks(documentKey: String): Set<Int> =
        prefs.getStringSet(docKey(documentKey, "bookmarks"), emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

    fun setBookmarks(documentKey: String, pages: Set<Int>) {
        prefs.edit()
            .putStringSet(docKey(documentKey, "bookmarks"), pages.map { it.toString() }.toSet())
            .apply()
    }

    // ---- Global ----

    fun getViewMode(): PdfViewMode =
        runCatching { PdfViewMode.valueOf(prefs.getString("view_mode", null) ?: "") }
            .getOrDefault(PdfViewMode.VERTICAL_CONTINUOUS)

    fun setViewMode(mode: PdfViewMode) {
        prefs.edit().putString("view_mode", mode.name).apply()
    }

    fun getColorMode(): PdfColorMode =
        runCatching { PdfColorMode.valueOf(prefs.getString("color_mode", null) ?: "") }
            .getOrDefault(PdfColorMode.NORMAL)

    fun setColorMode(mode: PdfColorMode) {
        prefs.edit().putString("color_mode", mode.name).apply()
    }

    fun getKeepScreenOn(): Boolean = prefs.getBoolean("keep_screen_on", false)

    fun setKeepScreenOn(value: Boolean) {
        prefs.edit().putBoolean("keep_screen_on", value).apply()
    }

    fun getAutoBrightness(): Boolean = prefs.getBoolean("auto_brightness", true)

    fun setAutoBrightness(value: Boolean) {
        prefs.edit().putBoolean("auto_brightness", value).apply()
    }

    fun getManualBrightness(): Float = prefs.getFloat("manual_brightness", 0.5f)

    fun setManualBrightness(value: Float) {
        prefs.edit().putFloat("manual_brightness", value).apply()
    }
}
