
// FLAT REPO ROOT -- codemagic.yaml copies to:

// app/src/main/java/com/propdf/editor/utils/AppShortcutsHelper.kt

//

// FIX: Removed references to MainActivity.ACTION_OPEN_PICKER and

// MainActivity.ACTION_OPEN_RECENT which are NOT defined in the existing
MainActivity.

// Action strings are now defined as constants here in the companion
object.

// MainActivity.onNewIntent() must check intent extras to handle them.

//

// FEATURE: Long-press app icon shortcuts (API 25+)

// - \"Scan\" -> opens DocumentScannerActivity

// - \"Open PDF\" -> opens MainActivity with ACTION_OPEN_PICKER

// - \"Recent Files\"-> opens MainActivity on Recent tab

//

// RULES OBEYED:

// Rule #32: Pure ASCII

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

// Action strings used by MainActivity.onNewIntent to identify shortcut
taps

const val ACTION_OPEN_PICKER = \"com.propdf.editor.action.OPEN_PICKER\"

const val ACTION_OPEN_RECENT = \"com.propdf.editor.action.OPEN_RECENT\"

// Call from MainActivity.onCreate() to register shortcuts

fun registerShortcuts(context: Context) {

if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return //
requires API 25

val shortcuts = listOf(

buildShortcut(

context = context,

id = \"shortcut_scan\",

shortLabel = \"Scan\",

longLabel = \"Scan Document\",

iconChar = \'S\',

bgColorHex = \"#1A73E8\",

targetClass = DocumentScannerActivity::class.java,

action = Intent.ACTION_VIEW // scanner needs no extra action

),

buildShortcut(

context = context,

id = \"shortcut_open\",

shortLabel = \"Open PDF\",

longLabel = \"Open PDF File\",

iconChar = \'O\',

bgColorHex = \"#34A853\",

targetClass = MainActivity::class.java,

action = ACTION_OPEN_PICKER // MainActivity checks this in onNewIntent

),

buildShortcut(

context = context,

id = \"shortcut_recent\",

shortLabel = \"Recent\",

longLabel = \"Recent Files\",

iconChar = \'R\',

bgColorHex = \"#FBBC04\",

targetClass = MainActivity::class.java,

action = ACTION_OPEN_RECENT

)

)

ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)

}

fun clearShortcuts(context: Context) {

ShortcutManagerCompat.removeAllDynamicShortcuts(context)

}

//
-----------------------------------------------------------------------

// PRIVATE HELPERS

//
-----------------------------------------------------------------------

private fun <T> buildShortcut(

context: Context,

id: String,

shortLabel: String,

longLabel: String,

iconChar: Char,

bgColorHex: String,

targetClass: Class<T>,

action: String

): ShortcutInfoCompat {

val intent = Intent(context, targetClass).apply {

this.action = action

flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

}

return ShortcutInfoCompat.Builder(context, id)

.setShortLabel(shortLabel)

.setLongLabel(longLabel)

.setIcon(buildLetterIcon(iconChar, bgColorHex))

.setIntent(intent)

.build()

}

// Build a circular letter icon as a Bitmap (no drawable resource
required)

private fun buildLetterIcon(char: Char, bgHex: String): IconCompat {

val size = 96

val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

val cv = Canvas(bmp)

val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = try {

Color.parseColor(bgHex)

} catch (_: Exception) {

Color.parseColor(\"#1A73E8\")

}

style = Paint.Style.FILL

}

cv.drawCircle(size / 2f, size / 2f, size / 2f, bg)

val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.WHITE

textSize = size * 0.45f

typeface = Typeface.DEFAULT_BOLD

textAlign = Paint.Align.CENTER

}

val textY = size / 2f - (txt.descent() + txt.ascent()) / 2f

cv.drawText(char.toString(), size / 2f, textY, txt)

return IconCompat.createWithBitmap(bmp)

}

}

**5.7 New Files --- Zoom & OCR**

**ZoomPageFrame.kt**

Fixed pinch-to-zoom FrameLayout. Pivot-aware translation prevents
content jumping. ScaleGestureDetector + GestureDetector (double-tap
reset). clampTranslation() prevents over-pan.

**Deployed to:** app/src/main/java/com/propdf/editor/ui/viewer/
