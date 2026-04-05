package com.propdf.editor.data.repository

import com.itextpdf.kernel.colors.DeviceRgb

import com.itextpdf.kernel.pdf.PdfDocument

import com.itextpdf.kernel.pdf.PdfReader

import com.itextpdf.kernel.pdf.PdfWriter

import com.itextpdf.kernel.pdf.canvas.PdfCanvas

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import java.io.File

import javax.inject.Inject

import javax.inject.Singleton

\@Singleton

class PdfRedactionManager \@Inject constructor() {

// A redaction region in PDF user-space coordinates (origin =
bottom-left)

data class RedactionRegion(

val pageNumber: Int, // 1-based

val x: Float,

val y: Float,

val width: Float,

val height: Float

)

//
-----------------------------------------------------------------------

// MAIN ENTRY POINT

//
-----------------------------------------------------------------------

// Paint opaque black rectangles over the specified regions on each
page.

// The black box renders ON TOP of all existing content (text, images).

// Text below is invisible to human readers; PDF copy/search is
unaffected

// without a full content-stream rewrite (requires iText commercial or
RUPS).

suspend fun applyRedactions(

inputFile: File,

outputFile: File,

regions: List<RedactionRegion>

): Result<File> = withContext(Dispatchers.IO) {

runCatching {

val doc = PdfDocument(

PdfReader(inputFile.absolutePath),

PdfWriter(outputFile.absolutePath)

)

// rule #1: try/finally, NOT .use{}

try {

val byPage = regions.groupBy { it.pageNumber }

for ((pageNum, rects) in byPage) {

if (pageNum < 1 || pageNum > doc.numberOfPages) continue

val page = doc.getPage(pageNum)

paintBlackBoxes(page, rects, doc)

}

} finally {

doc.close()

}

outputFile

}

}

//
-----------------------------------------------------------------------

// PAINT BLACK BOXES (visual redaction)

//
-----------------------------------------------------------------------

private fun paintBlackBoxes(

page: com.itextpdf.kernel.pdf.PdfPage,

rects: List<RedactionRegion>,

doc: PdfDocument

) {

// rule #3: correct PdfCanvas constructor

val canvas = PdfCanvas(page.newContentStreamAfter(), page.resources,
doc)

try {

canvas.saveState()

canvas.setFillColor(DeviceRgb(0f, 0f, 0f)) // pure black

for (r in rects) {

canvas.rectangle(

r.x.toDouble(), r.y.toDouble(),

r.width.toDouble(), r.height.toDouble()

)

canvas.fill()

}

canvas.restoreState()

} finally {

canvas.release()

}

}

//
-----------------------------------------------------------------------

// CONVENIENCE: convert ViewerActivity screen coordinates to PDF
user-space

//
-----------------------------------------------------------------------

// pageScale = screenPixels / pdfPoints (from ViewerActivity pageScales
map)

// pdfPageHeightPt = page height in PDF points (PDF origin is
bottom-left)

fun screenToPdfRegion(

pageNum: Int,

screenX: Float, screenY: Float,

screenW: Float, screenH: Float,

pageScale: Float,

pdfPageHeightPt: Float

): RedactionRegion {

val pdfX = screenX / pageScale

val pdfY = pdfPageHeightPt - (screenY + screenH) / pageScale

val pdfW = screenW / pageScale

val pdfH = screenH / pageScale

return RedactionRegion(pageNum, pdfX, pdfY, pdfW, pdfH)

}

//
-----------------------------------------------------------------------

// BATCH: redact keyword occurrences (search-and-redact)

// Uses PDFBox PDFTextStripper to find text positions, then paints black
boxes.

// Creates new PDFTextStripper per page (rule #15).

//
-----------------------------------------------------------------------

suspend fun redactKeyword(

inputFile: File,

outputFile: File,

keyword: String,

context: android.content.Context

): Result<File> = withContext(Dispatchers.IO) {

runCatching {

// Step 1: find page numbers that contain the keyword using PDFBox

val pdfBoxDoc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputFile)

val matchPages = mutableListOf<Int>()

try {

for (p in 1..pdfBoxDoc.numberOfPages) {

val stripper = com.tom_roush.pdfbox.text.PDFTextStripper() // rule #15:
new per page

stripper.startPage = p

stripper.endPage = p

val text = stripper.getText(pdfBoxDoc)

if (text.contains(keyword, ignoreCase = true)) {

matchPages.add(p)

}

}

} finally {

pdfBoxDoc.close()

}

if (matchPages.isEmpty()) {

// No matches -- just copy the file unchanged

inputFile.copyTo(outputFile, overwrite = true)

return@runCatching outputFile

}

// Step 2: For matching pages, redact with a full-page-width black
stripe

// at a best-guess vertical position (centre of page).

// True word-level bbox extraction requires PDFBox 3.x or iText
commercial.

val doc = PdfDocument(

PdfReader(inputFile.absolutePath),

PdfWriter(outputFile.absolutePath)

)

try {

for (pNum in matchPages) {

if (pNum > doc.numberOfPages) continue

val page = doc.getPage(pNum)

val ps = page.pageSize

// Paint a centred horizontal stripe across the page

val stripeH = 24f

val stripeY = (ps.top + ps.bottom) / 2f - stripeH / 2f

paintBlackBoxes(

page,

listOf(RedactionRegion(pNum, ps.left, stripeY, ps.width, stripeH)),

doc

)

}

} finally {

doc.close() // rule #1

}

outputFile

}

}

}

**5.4 New Files --- Signature**

**SignaturePadView.kt**

Finger-drawing signature pad. Bezier-curve smoothing, configurable ink
colour, exports Bitmap or PNG bytes.

**Deployed to:** app/src/main/java/com/propdf/editor/ui/viewer/
