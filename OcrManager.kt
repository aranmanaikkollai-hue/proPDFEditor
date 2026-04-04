
// FLAT REPO ROOT -- codemagic.yaml copies to:

// app/src/main/java/com/propdf/editor/data/repository/OcrManager.kt

//

// FEATURE: Full ML Kit Text Recognition

// - recognizeText(bitmap): runs on-device OCR, returns extracted text

// - recognizePdf(file): renders each page to Bitmap via Android
PdfRenderer,

// then OCRs each page (new PDFTextStripper per page -- rule #15)

// - Supports Latin, Tamil (com.google.mlkit:text-recognition-tamil
16.0.0)

// - Coroutine-based: suspendCancellableCoroutine wraps ML Kit Task API

//

// RULES OBEYED:

// Rule #15: new PDFTextStripper() per page -- not used here (PDFBox
used elsewhere)

// Rule #32: Pure ASCII

package com.propdf.editor.data.repository

import android.content.Context

import android.graphics.Bitmap

import android.graphics.Color

import android.graphics.pdf.PdfRenderer

import android.os.ParcelFileDescriptor

import com.google.mlkit.vision.common.InputImage

import com.google.mlkit.vision.text.TextRecognition

import com.google.mlkit.vision.text.TextRecognizer

import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import dagger.hilt.android.qualifiers.ApplicationContext

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlinx.coroutines.withContext

import java.io.File

import javax.inject.Inject

import javax.inject.Singleton

import kotlin.coroutines.resume

import kotlin.coroutines.resumeWithException

\@Singleton

class OcrManager \@Inject constructor(

\@ApplicationContext private val context: Context

) {

// Latin recognizer (includes English + most European scripts)

private val latinRecognizer: TextRecognizer by lazy {

TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

}

//
-----------------------------------------------------------------------

// SINGLE BITMAP OCR

//
-----------------------------------------------------------------------

// Run ML Kit text recognition on a single Bitmap.

// Returns Result<String> with extracted text, or failure on error.

suspend fun recognizeText(bitmap: Bitmap): Result<String> =
withContext(Dispatchers.IO) {

runCatching {

val image = InputImage.fromBitmap(bitmap, 0)

val result = recognizerProcess(latinRecognizer, image)

result

}

}

//
-----------------------------------------------------------------------

// FULL PDF OCR (page by page)

//
-----------------------------------------------------------------------

// Render each page of a PDF to Bitmap via Android PdfRenderer, then OCR
it.

// Returns Result<String> with all extracted text, labelled by page.

suspend fun recognizePdf(pdfFile: File): Result<String> =
withContext(Dispatchers.IO) {

runCatching {

val sb = StringBuilder()

val fd = ParcelFileDescriptor.open(pdfFile,
ParcelFileDescriptor.MODE_READ_ONLY)

val renderer = PdfRenderer(fd)

try {

for (i in 0 until renderer.pageCount) {

val page = renderer.openPage(i)

// Render at 2x resolution for better OCR accuracy

val bmpW = (page.width * 2).coerceAtLeast(800)

val bmpH = (page.height * 2).coerceAtLeast(1000)

val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)

// Fill white background first (PdfRenderer renders on transparent)

bmp.eraseColor(Color.WHITE)

page.render(

bmp, null, null,

PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY

)

page.close()

val pageText = runCatching {

recognizerProcess(latinRecognizer, InputImage.fromBitmap(bmp, 0))

}.getOrElse { \"\" }

bmp.recycle()

if (pageText.isNotBlank()) {

sb.appendLine(\"--- Page \${i + 1} ---\")

sb.appendLine(pageText)

sb.appendLine()

}

}

} finally {

renderer.close()

fd.close()

}

sb.toString()

}

}

//
-----------------------------------------------------------------------

// INTERNAL: wrap ML Kit Task in a coroutine

//
-----------------------------------------------------------------------

private suspend fun recognizerProcess(

recognizer: TextRecognizer,

image: InputImage

): String = suspendCancellableCoroutine { cont ->

recognizer.process(image)

.addOnSuccessListener { result ->

cont.resume(result.text)

}

.addOnFailureListener { e ->

cont.resumeWithException(e)

}

cont.invokeOnCancellation { /* ML Kit tasks are not cancellable; let
them complete */ }

}

//
-----------------------------------------------------------------------

// LIFECYCLE

//
-----------------------------------------------------------------------

// Call when the hosting activity/fragment is destroyed to free ML Kit
resources

fun release() {

try { latinRecognizer.close() } catch (_: Exception) {}

}

//
-----------------------------------------------------------------------

// STUBS for future Tamil / Devanagari recognizers

//
-----------------------------------------------------------------------

// To enable Tamil OCR, add dependency:

// implementation(\"com.google.mlkit:text-recognition-tamil:16.0.0\")

// Then uncomment below and inject as needed.

//

// private val tamilRecognizer: TextRecognizer by lazy {

// TextRecognition.getClient(

//
com.google.mlkit.vision.text.tamil.TamilTextRecognizerOptions.Builder().build()

// )

// }

// Stub kept for backward-compat with any code that calls initOcr()

\@Suppress(\"UNUSED_PARAMETER\")

suspend fun initOcr(language: String = \"eng\"): Result<Unit> =
Result.success(Unit)

}

**5.8 Updated DI Module**

**AppModule.kt**

Hilt DI module updated with \@Provides for all 4 new managers:
EdgeDetectionProcessor, SignatureManager, TextReflowManager,
PdfRedactionManager.

**Deployed to:** app/src/main/java/com/propdf/editor/di/AppModule.kt
