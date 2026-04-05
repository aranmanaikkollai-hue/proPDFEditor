package com.propdf.editor.data.repository

import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.util.Base64

import com.itextpdf.io.image.ImageDataFactory

import com.itextpdf.kernel.pdf.*

import com.itextpdf.kernel.pdf.canvas.PdfCanvas

import com.itextpdf.kernel.pdf.xobject.PdfImageXObject

import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream

import java.io.File

import javax.inject.Inject

import javax.inject.Singleton

\@Singleton

class SignatureManager \@Inject constructor(

\@ApplicationContext private val context: Context

) {

companion object {

private const val PREFS_NAME = \"propdf_signatures\"

private const val KEY_DEFAULT_SIG = \"default_signature_png_b64\"

private const val KEY_SIG_COUNT = \"signature_count\"

}

private val prefs by lazy {

context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

}

//
-----------------------------------------------------------------------

// SAVE / LOAD SIGNATURES

//
-----------------------------------------------------------------------

// Save a signature bitmap to SharedPreferences under a named slot.

// Slot \"default\" is used by the quick-sign toolbar button.

suspend fun saveSignature(bmp: Bitmap, slot: String = \"default\"):
Boolean =

withContext(Dispatchers.Default) {

try {

val baos = ByteArrayOutputStream()

bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)

val b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

prefs.edit().putString(\"sig_\$slot\", b64).apply()

if (slot == \"default\") {

prefs.edit().putString(KEY_DEFAULT_SIG, b64).apply()

}

true

} catch (_: Exception) { false }

}

// Load a saved signature. Returns null if none saved for this slot.

suspend fun loadSignature(slot: String = \"default\"): Bitmap? =

withContext(Dispatchers.Default) {

try {

val b64 = prefs.getString(\"sig_\$slot\", null) ?: return@withContext
null

val bytes = Base64.decode(b64, Base64.DEFAULT)

BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

} catch (_: Exception) { null }

}

fun hasSignature(slot: String = \"default\"): Boolean =

prefs.contains(\"sig_\$slot\")

fun deleteSignature(slot: String = \"default\") {

prefs.edit().remove(\"sig_\$slot\").apply()

}

fun listSlots(): List<String> {

return prefs.all.keys

.filter { it.startsWith(\"sig_\") }

.map { it.removePrefix(\"sig_\") }

}

//
-----------------------------------------------------------------------

// INSERT SIGNATURE INTO PDF

//
-----------------------------------------------------------------------

// Embed the signature bitmap at a specific location on a PDF page.

// x, y: bottom-left corner in PDF user-space points (origin =
bottom-left)

// widthPt, heightPt: desired size in PDF points

suspend fun insertSignatureIntoPdf(

inputFile: File,

outputFile: File,

pageNumber: Int, // 1-based

xPt: Float,

yPt: Float,

widthPt: Float,

heightPt: Float,

slot: String = \"default\"

): Result<File> = withContext(Dispatchers.IO) {

runCatching {

val bmp = loadSignature(slot)

?: throw IllegalStateException(\"No signature saved in slot: \$slot\")

// Encode bitmap to PNG bytes for iText

val baos = ByteArrayOutputStream()

bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)

bmp.recycle()

val pngBytes = baos.toByteArray()

val imgData = ImageDataFactory.create(pngBytes)

val imgXObj = PdfImageXObject(imgData)

val doc = PdfDocument(

PdfReader(inputFile.absolutePath),

PdfWriter(outputFile.absolutePath)

)

try {

val pageCount = doc.numberOfPages

val pn = pageNumber.coerceIn(1, pageCount)

val page = doc.getPage(pn)

val canvas = PdfCanvas(

page.newContentStreamAfter(), // rule #3

page.resources,

doc

)

try {

canvas.saveState()

// concatMatrix(scaleX, skewB, skewC, scaleY, transX, transY) -- rule
#4

canvas.concatMatrix(

widthPt.toDouble(), 0.0, 0.0,

heightPt.toDouble(),

xPt.toDouble(), yPt.toDouble()

)

canvas.addXObjectAt(imgXObj, 0f, 0f)

canvas.restoreState()

} finally {

canvas.release()

}

} finally {

doc.close() // rule #1

}

outputFile

}

}

//
-----------------------------------------------------------------------

// CONVENIENCE: convert screen (ViewerActivity) coords to PDF user-space

//
-----------------------------------------------------------------------

// pageScale is screenPixels/pdfPoints (stored in
ViewerActivity.pageScales map)

// pdfPageHeightPt is the height of the page in PDF points

fun screenToPdfCoords(

screenX: Float, screenY: Float,

sigWidthPx: Float, sigHeightPx: Float,

pageScale: Float,

pdfPageHeightPt: Float

): FloatArray {

val pdfX = screenX / pageScale

val pdfY = pdfPageHeightPt - (screenY / pageScale) - (sigHeightPx /
pageScale)

val pdfW = sigWidthPx / pageScale

val pdfH = sigHeightPx / pageScale

return floatArrayOf(pdfX, pdfY, pdfW, pdfH)

}

}

**5.5 New Files --- Text Reflow**

**TextReflowManager.kt**

PDFBox text extraction. New PDFTextStripper per page (rule #15). Returns
List<ReflowPage> for display.

**Deployed to:** app/src/main/java/com/propdf/editor/data/repository/
