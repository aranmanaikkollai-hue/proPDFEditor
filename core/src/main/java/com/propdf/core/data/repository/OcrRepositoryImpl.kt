package com.propdf.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.propdf.core.data.database.dao.OcrRecordDao
import com.propdf.core.data.database.entity.OcrRecordEntity
import com.propdf.core.domain.model.HandwritingResult
import com.propdf.core.domain.model.OcrConfig
import com.propdf.core.domain.model.OcrLanguage
import com.propdf.core.domain.model.OcrOutputFormat
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.core.domain.model.OcrPreprocessConfig
import com.propdf.core.domain.model.OcrRecord
import com.propdf.core.domain.model.OcrTable
import com.propdf.core.domain.model.OcrTextBlock
import com.propdf.core.domain.model.OcrTextElement
import com.propdf.core.domain.model.OcrTextLine
import com.propdf.core.domain.model.Script
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRecordDao: OcrRecordDao
) : OcrRepository {

    private val defaultRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Selects the ML Kit recognizer matching the script of the first non-AUTO language. */
    private fun recognizerFor(languages: List<OcrLanguage>): TextRecognizer {
        val script = languages.firstOrNull { it != OcrLanguage.AUTO }?.script ?: Script.LATIN
        return when (script) {
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            // Tamil has no dedicated ML Kit model; fall back to Latin (see OcrModels.kt).
            Script.LATIN, Script.TAMIL, Script.AUTO -> defaultRecognizer
        }
    }

    // ---- Legacy single-shot OCR record API ----

    override suspend fun performOcr(uri: Uri, language: String): Result<OcrRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val result = recognizerFor(listOf(OcrLanguage.fromCode(language))).process(image).await()
            val record = OcrRecord(
                id = UUID.randomUUID().toString(),
                sourceUri = uri.toString(),
                extractedText = result.text,
                language = language,
                createdAt = System.currentTimeMillis()
            )
            ocrRecordDao.insert(record.toEntity())
            record
        }
    }

    override suspend fun getRecentOcr(limit: Int): List<OcrRecord> {
        return ocrRecordDao.getRecent(limit).map { it.toDomain() }
    }

    override suspend fun searchOcr(query: String): List<OcrRecord> {
        return ocrRecordDao.search(query).map { it.toDomain() }
    }

    // ---- Batch OCR pipeline API ----

    override suspend fun recognizeImage(bitmap: Bitmap, config: OcrConfig): AppResult<OcrPageResult> =
        withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizerFor(config.languages).process(image).await()
                AppResult.Success(
                    result.toPageResult(
                        pageIndex = 0,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                )
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun recognizeImageUri(uri: Uri, config: OcrConfig): AppResult<OcrPageResult> =
        withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                val image = InputImage.fromFilePath(context, uri)
                val result = recognizerFor(config.languages).process(image).await()
                AppResult.Success(
                    result.toPageResult(
                        pageIndex = 0,
                        imageWidth = image.width,
                        imageHeight = image.height,
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                )
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override fun recognizeBatch(uris: List<Uri>, config: OcrConfig): Flow<AppResult<OcrPageResult>> = flow {
        uris.forEachIndexed { index, uri ->
            when (val result = recognizeImageUri(uri, config)) {
                is AppResult.Success -> emit(AppResult.Success(result.data.copy(pageIndex = index)))
                is AppResult.Error -> emit(result)
                is AppResult.Loading -> emit(result)
            }
        }
    }

    // No offline correction model is bundled; return the text unchanged rather than
    // fabricate a correction the app cannot actually perform.
    override suspend fun correctText(text: String, language: OcrLanguage): AppResult<String> {
        return AppResult.Success(text)
    }

    // ML Kit's bundled text recognizers do not expose handwriting classification;
    // report "not detected" honestly instead of guessing.
    override suspend fun detectHandwriting(bitmap: Bitmap): AppResult<HandwritingResult> {
        return AppResult.Success(HandwritingResult(hasHandwriting = false, confidence = 0f, regions = emptyList()))
    }

    // No table-structure model is bundled; report no tables found.
    override suspend fun detectTables(bitmap: Bitmap, config: OcrConfig): AppResult<List<OcrTable>> {
        return AppResult.Success(emptyList())
    }

    // ML Kit's on-device text recognizers manage their own models transparently
    // (downloaded on first use); there is no public API to drive that separately.
    override fun downloadModel(language: OcrLanguage): Flow<AppResult<Int>> = flow {
        emit(AppResult.Success(100))
    }

    override suspend fun isModelDownloaded(language: OcrLanguage): Boolean = true

    override suspend fun deleteModel(language: OcrLanguage): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun exportToPdf(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val pdf = PdfDocument()
                val paint = Paint().apply { textSize = 12f }
                val pageWidth = 612
                val pageHeight = 792
                val marginLeft = 24f
                val marginRight = 24f
                val topY = 40f
                val bottomLimit = 760f
                val lineHeight = paint.textSize + 4f
                val maxTextWidth = pageWidth - marginLeft - marginRight

                // Word-wraps a single OCR'd line to the page width and starts a new PDF
                // page whenever the current one fills up, instead of the previous
                // behaviour of stopping at 780f and silently dropping the rest of a
                // page's recognized text (a long scanned page's OCR output would just
                // be truncated with no error or indication anything was cut).
                var pageNumber = 0
                var pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
                var canvas = pdfPage.canvas
                var y = topY

                fun newPage() {
                    pdf.finishPage(pdfPage)
                    pdfPage = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
                    canvas = pdfPage.canvas
                    y = topY
                }

                fun wrapLine(line: String): List<String> {
                    if (line.isEmpty()) return listOf("")
                    val words = line.split(" ")
                    val wrapped = mutableListOf<String>()
                    var current = StringBuilder()
                    for (word in words) {
                        val candidate = if (current.isEmpty()) word else "${current} $word"
                        if (paint.measureText(candidate) <= maxTextWidth || current.isEmpty()) {
                            current = StringBuilder(candidate)
                        } else {
                            wrapped.add(current.toString())
                            current = StringBuilder(word)
                        }
                    }
                    if (current.isNotEmpty()) wrapped.add(current.toString())
                    return wrapped
                }

                results.forEachIndexed { index, page ->
                    page.fullText.split("\n").forEach { rawLine ->
                        wrapLine(rawLine).forEach { line ->
                            if (y > bottomLimit) newPage()
                            canvas.drawText(line, marginLeft, y, paint)
                            y += lineHeight
                        }
                    }
                    // Start a fresh PDF page for the next source page's text (but not
                    // after the last one, which is finished once below instead).
                    if (index != results.lastIndex) newPage()
                }
                pdf.finishPage(pdfPage)

                context.contentResolver.openOutputStream(outputUri)?.use { pdf.writeTo(it) }
                    ?: throw AppException.IOError("Could not open output stream for $outputUri")
                pdf.close()
                AppResult.Success(outputUri)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun exportToTxt(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val text = results.joinToString("\n\n---\n\n") { it.fullText }
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw AppException.IOError("Could not open output stream for $outputUri")
                AppResult.Success(outputUri)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    // Minimal but valid OOXML (.docx) package: one paragraph per line, hand-built
    // since no docx-writing library is bundled in this module.
    override suspend fun exportToDocx(results: List<OcrPageResult>, outputUri: Uri): AppResult<Uri> =
        withContext(Dispatchers.IO) {
            try {
                val fullText = results.joinToString("\n\n") { it.fullText }
                val paragraphs = fullText.split("\n").joinToString("") { line ->
                    val escaped = line
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    "<w:p><w:r><w:t xml:space=\"preserve\">$escaped</w:t></w:r></w:p>"
                }
                val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$paragraphs</w:body></w:document>"""
                val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
                val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

                val buffer = ByteArrayOutputStream()
                ZipOutputStream(buffer).use { zip ->
                    fun writeEntry(name: String, content: String) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    writeEntry("[Content_Types].xml", contentTypesXml)
                    writeEntry("_rels/.rels", rootRelsXml)
                    writeEntry("word/document.xml", documentXml)
                }
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    out.write(buffer.toByteArray())
                } ?: throw AppException.IOError("Could not open output stream for $outputUri")
                AppResult.Success(outputUri)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun preprocessImage(bitmap: Bitmap, config: OcrPreprocessConfig): AppResult<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                var output = bitmap
                if (config.enableContrastEnhance) {
                    val contrast = 1.2f
                    val translate = (-.5f * contrast + .5f) * 255f
                    val matrix = ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    val adjusted = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(adjusted)
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    output = adjusted
                }
                // Deskew/perspective-correction/denoise require an image-processing library
                // (e.g. OpenCV) that isn't wired into this repository; left as a no-op here.
                AppResult.Success(output)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun cropImage(bitmap: Bitmap, cropRect: Rect): AppResult<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                val safeRect = Rect(
                    cropRect.left.coerceIn(0, bitmap.width),
                    cropRect.top.coerceIn(0, bitmap.height),
                    cropRect.right.coerceIn(0, bitmap.width),
                    cropRect.bottom.coerceIn(0, bitmap.height)
                )
                val cropped = Bitmap.createBitmap(
                    bitmap, safeRect.left, safeRect.top,
                    safeRect.width(), safeRect.height()
                )
                AppResult.Success(cropped)
            } catch (e: Exception) {
                AppResult.Error(e.toAppException())
            }
        }

    override suspend fun searchInText(text: String, query: String, caseSensitive: Boolean): AppResult<List<IntRange>> {
        if (query.isEmpty()) return AppResult.Success(emptyList())
        val haystack = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) query else query.lowercase()
        val ranges = mutableListOf<IntRange>()
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            ranges.add(index until (index + needle.length))
            index = haystack.indexOf(needle, index + needle.length)
        }
        return AppResult.Success(ranges)
    }

    // ---- Mapping helpers ----

    private fun Text.toPageResult(pageIndex: Int, imageWidth: Int, imageHeight: Int, elapsedMs: Long): OcrPageResult {
        val blocks = textBlocks.map { block ->
            OcrTextBlock(
                text = block.text,
                boundingBox = block.boundingBox?.let { android.graphics.RectF(it) } ?: android.graphics.RectF(),
                confidence = 1f,
                language = block.recognizedLanguage,
                lines = block.lines.map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = line.boundingBox?.let { android.graphics.RectF(it) } ?: android.graphics.RectF(),
                        confidence = 1f,
                        elements = line.elements.map { element ->
                            OcrTextElement(
                                text = element.text,
                                boundingBox = element.boundingBox?.let { android.graphics.RectF(it) } ?: android.graphics.RectF(),
                                confidence = 1f,
                                recognizedLanguage = block.recognizedLanguage
                            )
                        }
                    )
                }
            )
        }
        return OcrPageResult(
            pageIndex = pageIndex,
            fullText = text,
            blocks = blocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            processingTimeMs = elapsedMs,
            detectedLanguages = blocks.map { it.language }.distinct().filter { it.isNotBlank() }
        )
    }

    private fun OcrRecordEntity.toDomain() = OcrRecord(
        id = id,
        sourceUri = sourceUri,
        extractedText = extractedText,
        language = language,
        createdAt = createdAt
    )

    private fun OcrRecord.toEntity() = OcrRecordEntity(
        id = id,
        sourceUri = sourceUri,
        extractedText = extractedText,
        language = language,
        createdAt = createdAt
    )
}
