// FILE: AppShortcutsHelper.kt
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/utils/AppShortcutsHelper.kt
//
// FEATURE: App long-press shortcuts (ShortcutManagerCompat API 25+)
//   - "Scan Document" -> opens DocumentScannerActivity
//   - "Open PDF"      -> opens MainActivity with file picker intent-flag
//   - "Recent Files"  -> opens MainActivity on Recent tab
//
// RULES OBEYED:
//   - No JitPack
//   - Pure ASCII
//   - Uses ShortcutManagerCompat for backward-compat (API 21+)
//   - Shortcut icons built from BitmapFactory (no vector drawables required)
//   - All floats use f suffix

package com.propdf.editor.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.propdf.editor.ui.MainActivity
import com.propdf.editor.ui.scanner.DocumentScannerActivity

object AppShortcutsHelper {

    // Call once from MainActivity.onCreate() or Application.onCreate()
    fun registerShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return  // API 25 check

        val shortcuts = listOf(
            buildShortcut(
                context = context,
                id = "shortcut_scan",
                shortLabel = "Scan",
                longLabel = "Scan Document",
                iconChar = 'S',
                bgColorHex = "#1A73E8",
                activityClass = DocumentScannerActivity::class.java,
                extraAction = null
            ),
            buildShortcut(
                context = context,
                id = "shortcut_open",
                shortLabel = "Open PDF",
                longLabel = "Open PDF File",
                iconChar = 'O',
                bgColorHex = "#34A853",
                activityClass = MainActivity::class.java,
                extraAction = MainActivity.ACTION_OPEN_PICKER
            ),
            buildShortcut(
                context = context,
                id = "shortcut_recent",
                shortLabel = "Recent",
                longLabel = "Recent Files",
                iconChar = 'R',
                bgColorHex = "#FBBC04",
                activityClass = MainActivity::class.java,
                extraAction = MainActivity.ACTION_OPEN_RECENT
            )
        )

        // ShortcutManagerCompat handles API 25+ dynamic shortcuts
        // and also works on launcher-side pinned shortcuts (API 26+)
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    // Remove all dynamic shortcuts (call on logout / privacy clear)
    fun clearShortcuts(context: Context) {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    // -----------------------------------------------------------------------
    // PRIVATE HELPERS
    // -----------------------------------------------------------------------

    private fun <T> buildShortcut(
        context: Context,
        id: String,
        shortLabel: String,
        longLabel: String,
        iconChar: Char,
        bgColorHex: String,
        activityClass: Class<T>,
        extraAction: String?
    ): ShortcutInfoCompat {
        val icon = buildLetterIcon(context, iconChar, bgColorHex)

        val intent = Intent(context, activityClass).apply {
            action = extraAction ?: Intent.ACTION_VIEW
            flags  = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            extraAction?.let { putExtra("shortcut_action", it) }
        }

        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    // Build a circular letter icon as Bitmap (no drawable resource required)
    private fun buildLetterIcon(context: Context, char: Char, bgHex: String): IconCompat {
        val size = 96  // px -- launcher scales it
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = try { Color.parseColor(bgHex) } catch (_: Exception) { Color.parseColor("#1A73E8") }
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.45f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(char.toString(), size / 2f, textY, textPaint)

        return IconCompat.createWithBitmap(bmp)
    }
}
