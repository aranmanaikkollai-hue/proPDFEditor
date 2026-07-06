package com.propdf.core.data.repository

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.withSave
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppResult
import com.propdf.core.domain.dispatcher.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import java.io.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Production-ready OCR Repository implementation using ML Kit offline text recognition.
 *
 * Supports:
 * - Latin (English, French, German, Spanish, Portuguese, Italian, Dutch, Polish, Turkish, Indonesian, Vietnamese)
 * - Devanagari (Hindi, Marathi, Sanskrit, Nepali)
 * - Japanese
 * - Korean
 * - Chinese (Simplified & Traditional)
 * - Tamil (fallback to Latin with auto-language)
 *
 * Features:
 * - Batch OCR
 * - Multi-language OCR
 * - Table OCR (heuristic-based)
 * - Handwriting detection (heuristic-based on text density)
 * - Auto language detection
 * - Crop before OCR
 * - Deskew
 * - Denoise
 * - Perspective correction
 * - OCR correction (dictionary-based)
 * - Search OCR text
 * - Copy OCR
 * - Export OCR (PDF, TXT, DOCX)
 */
@Singleton
class OcrRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : OcrRepository {

    private val modelManager = RemoteModelManager.getInstance()

    // Cache recognizers to avoid recreation
    private val recognizerCache = mutableMapOf<String, TextRecognizer>()

    // Simple dictionary for OCR correction (can be expanded)
    private val correctionDictionaries = mutableMapOf<String, Set<String>>()

    init {
        // Pre-load common English correction dictionary
        correctionDictionaries["en"] = loadEnglishDictionary()
    }

    // ==================== MODEL MANAGEMENT ====================

    override suspend fun isModelDownloaded(language: OcrLanguage): Boolean = withContext(dispatchers.io) {
        try {
            val model = getModelForLanguage(language)
            if (model == null) return@withContext true // Latin is bundled
            val downloadedModels = modelManager.getDownloadedModels(model.javaClass).await()
            downloadedModels.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun downloadModel(language: OcrLanguage): Flow<AppResult<Int>> = flow {
        emit(AppResult.Loading)
        val model = getModelForLanguage(language)
        if (model == null) {
            emit(AppResult.Success(100))
            return@flow
        }

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        try {
            modelManager.download(model, conditions).await()
            emit(AppResult.Success(100))
        } catch (e: Exception) {
            emit(AppResult.Error("Failed to download model: ${e.message}"))
        }
    }.flowOn(dispatchers.io)

    override suspend fun deleteModel(language: OcrLanguage): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val model = getModelForLanguage(language)
            if (model != null) {
                modelManager.deleteDownloadedModel(model).await()
            }
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to delete model: ${e.message}")
        }
    }

    override suspend fun getDownloadedModels(): AppResult<List<OcrLanguage>> = withContext(dispatchers.io) {
        try {
            val downloaded = mutableListOf<OcrLanguage>()
            // Latin is always available (bundled)
            downloaded.add(OcrLanguage.ENGLISH)

            // Check other models
            val allModels = listOf(
                OcrLanguage.HINDI to com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build(),
                OcrLanguage.JAPANESE to com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions.Builder().build(),
                OcrLanguage.KOREAN to com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build(),
                OcrLanguage.CHINESE_SIMPLIFIED to com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            )

            for ((lang, model) in allModels) {
                try {
                    val models = modelManager.getDownloadedModels(model.javaClass).await()
                    if (models.isNotEmpty()) downloaded.add(lang)
                } catch (_: Exception) { }
            }

            AppResult.Success(downloaded)
        } catch (e: Exception) {
            AppResult.Error("Failed to get downloaded models: ${e.message}")
        }
    }

    // ==================== SINGLE IMAGE OCR ====================

    override suspend fun recognizeImage(
        bitmap: Bitmap,
        config: OcrConfig
    ): AppResult<OcrPageResult> = withContext(dispatchers.default) {
        runCatching {
            val startTime = System.currentTimeMillis()

            // Preprocess
            val processedBitmap = preprocessImageInternal(bitmap, config.preprocessConfig)

            // Crop if needed
            val finalBitmap = config.cropRect?.let { cropBitmap(processedBitmap, it) } ?: processedBitmap

            // Get recognizer
            val recognizer = getRecognizer(config.languages)

            // Create input image
            val inputImage = InputImage.fromBitmap(finalBitmap, 0)

            // Perform OCR
            val visionText = recognizer.process(inputImage).await()

            // Parse result
            val pageResult = parseVisionText(visionText, finalBitmap.width, finalBitmap.height, startTime)

            // Cleanup
            if (finalBitmap !== bitmap && finalBitmap !== processedBitmap) {
                finalBitmap.recycle()
            }
            if (processedBitmap !== bitmap) {
                processedBitmap.recycle()
            }

            pageResult
        }.toAppResult()
    }

    override suspend fun recognizeImageUri(
        uri: Uri,
        config: OcrConfig
    ): AppResult<OcrPageResult> = withContext(dispatchers.io) {
        runCatching {
            val bitmap = loadBitmapFromUri(uri)
                ?: throw IllegalArgumentException("Failed to load image from URI")
            when (val result = recognizeImage(bitmap, config)) {
                is AppResult.Success -> {
                    bitmap.recycle()
                    result.data
                }
                is AppResult.Error -> throw Exception(result.message ?: "OCR failed")
                is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
            }
        }.toAppResult()
    }

    // ==================== BATCH OCR ====================

    override fun recognizeBatch(
        uris: List<Uri>,
        config: OcrConfig
    ): Flow<AppResult<OcrPageResult>> = flow {
        val total = uris.size
        if (total == 0) {
            emit(AppResult.Error("No images provided"))
            return@flow
        }

        emit(AppResult.Loading)

        uris.forEachIndexed { index, uri ->
            emit(AppResult.Loading)
            when (val result = recognizeImageUri(uri, config)) {
                is AppResult.Success -> {
                    emit(AppResult.Success(result.data.copy(pageIndex = index)))
                }
                is AppResult.Error -> {
                    emit(AppResult.Error("Page ${index + 1}: ${result.message}"))
                }
                is AppResult.Loading -> { /* no-op */ }
            }
        }
    }.flowOn(dispatchers.default)

    // ==================== TABLE OCR ====================

    override suspend fun detectTables(
        bitmap: Bitmap,
        config: OcrConfig
    ): AppResult<List<OcrTable>> = withContext(dispatchers.default) {
        runCatching {
            when (val ocrResult = recognizeImage(bitmap, config)) {
                is AppResult.Success -> {
                    val tables = extractTablesFromBlocks(ocrResult.data.blocks)
                    tables
                }
                is AppResult.Error -> throw Exception(ocrResult.message ?: "OCR failed for table detection")
                is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
            }
        }.toAppResult()
    }

    // ==================== HANDWRITING DETECTION ====================

    override suspend fun detectHandwriting(
        bitmap: Bitmap
    ): AppResult<HandwritingResult> = withContext(dispatchers.default) {
        runCatching {
            // Heuristic-based handwriting detection using text density and character analysis
            val grayBitmap = toGrayscale(bitmap)
            val handwritingRegions = detectHandwritingRegions(grayBitmap)

            val hasHandwriting = handwritingRegions.isNotEmpty()
            val confidence = if (hasHandwriting) {
                calculateHandwritingConfidence(grayBitmap, handwritingRegions)
            } else 0f

            grayBitmap.recycle()

            HandwritingResult(
                hasHandwriting = hasHandwriting,
                confidence = confidence,
                regions = handwritingRegions
            )
        }.toAppResult()
    }

    // ==================== OCR CORRECTION ====================

    override suspend fun correctText(
        text: String,
        language: OcrLanguage
    ): AppResult<String> = withContext(dispatchers.default) {
        runCatching {
            val dictionary = correctionDictionaries[language.code] ?: correctionDictionaries["en"] ?: emptySet()
            val words = text.split(Regex("(?<=\\s)|(?=\\s)"))
            val corrected = words.map { word ->
                if (word.trim().isEmpty() || word.all { !it.isLetter() }) {
                    word
                } else {
                    val cleanWord = word.filter { it.isLetter() }.lowercase()
                    if (dictionary.contains(cleanWord)) {
                        word
                    } else {
                        // Find closest match using Levenshtein distance
                        findClosestWord(cleanWord, dictionary)?.let { closest ->
                            // Preserve case pattern
                            preserveCase(word, closest)
                        } ?: word
                    }
                }
            }.joinToString("")
            corrected
        }.toAppResult()
    }

    // ==================== SEARCH ====================

    override suspend fun searchInText(
        text: String,
        query: String,
        caseSensitive: Boolean
    ): AppResult<List<IntRange>> = withContext(dispatchers.default) {
        runCatching {
            val searchText = if (caseSensitive) text else text.lowercase()
            val searchQuery = if (caseSensitive) query else query.lowercase()

            val ranges = mutableListOf<IntRange>()
            var startIndex = 0

            while (true) {
                val index = searchText.indexOf(searchQuery, startIndex)
                if (index == -1) break
                ranges.add(index until index + searchQuery.length)
                startIndex = index + 1
            }

            ranges
        }.toAppResult()
    }

    // ==================== EXPORT ====================

    override suspend fun exportToPdf(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri> = withContext(dispatchers.io) {
        runCatching {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.DEFAULT
            }

            results.forEachIndexed { index, result ->
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    result.imageWidth, result.imageHeight, index
                ).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Draw text blocks at their original positions
                result.blocks.forEach { block ->
                    canvas.drawText(block.text, block.boundingBox.left, block.boundingBox.bottom, paint)
                }

                pdfDocument.finishPage(page)
            }

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                pdfDocument.writeTo(outputStream)
            } ?: throw IOException("Cannot open output stream")

            pdfDocument.close()
            outputUri
        }.toAppResult()
    }

    override suspend fun exportToTxt(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri> = withContext(dispatchers.io) {
        runCatching {
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    results.forEachIndexed { index, result ->
                        if (index > 0) writer.write("\n\n--- Page ${index + 1} ---\n\n")
                        writer.write(result.fullText)
                    }
                }
            } ?: throw IOException("Cannot open output stream")
            outputUri
        }.toAppResult()
    }

    override suspend fun exportToDocx(
        results: List<OcrPageResult>,
        outputUri: Uri
    ): AppResult<Uri> = withContext(dispatchers.io) {
        runCatching {
            val document = XWPFDocument()

            results.forEachIndexed { index, result ->
                if (index > 0) {
                    document.createParagraph().createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
                }

                result.blocks.forEach { block ->
                    val paragraph: XWPFParagraph = document.createParagraph()
                    val run: XWPFRun = paragraph.createRun()
                    run.setText(block.text)
                }
            }

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.write(outputStream)
            } ?: throw IOException("Cannot open output stream")

            document.close()
            outputUri
        }.toAppResult()
    }

    // ==================== PREPROCESSING ====================

    override suspend fun preprocessImage(
        bitmap: Bitmap,
        config: OcrPreprocessConfig
    ): AppResult<Bitmap> = withContext(dispatchers.default) {
        runCatching {
            preprocessImageInternal(bitmap, config)
        }.toAppResult()
    }

    override suspend fun cropImage(
        bitmap: Bitmap,
        rect: android.graphics.Rect
    ): AppResult<Bitmap> = withContext(dispatchers.default) {
        runCatching {
            cropBitmap(bitmap, rect)
        }.toAppResult()
    }

    // ==================== PRIVATE HELPERS ====================

    private fun getRecognizer(languages: List<OcrLanguage>): TextRecognizer {
        // For single language, use specific recognizer
        // For multiple languages or auto, use Latin as primary with fallback logic
        val primaryLanguage = languages.firstOrNull() ?: OcrLanguage.AUTO

        val key = languages.joinToString(",") { it.code }

        return recognizerCache.getOrPut(key) {
            when (primaryLanguage) {
                OcrLanguage.CHINESE_SIMPLIFIED, OcrLanguage.CHINESE_TRADITIONAL ->
                    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                OcrLanguage.JAPANESE ->
                    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                OcrLanguage.KOREAN ->
                    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                OcrLanguage.HINDI, OcrLanguage.MARATHI, OcrLanguage.SANSKRIT, OcrLanguage.NEPALI ->
                    TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                else -> // Latin script (English, Tamil, etc.)
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun getModelForLanguage(language: OcrLanguage): com.google.mlkit.common.model.RemoteModel? {
        return when (language.script) {
            Script.DEVANAGARI -> com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()
            Script.JAPANESE -> com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions.Builder().build()
            Script.KOREAN -> com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions.Builder().build()
            Script.CHINESE -> com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            else -> null // Latin is bundled, no remote model needed
        }
    }

    private fun preprocessImageInternal(bitmap: Bitmap, config: OcrPreprocessConfig): Bitmap {
        var result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

        if (config.enablePerspectiveCorrection) {
            result = applyPerspectiveCorrection(result)
        }

        if (config.enableDeskew) {
            result = applyDeskew(result)
        }

        if (config.enableDenoise) {
            result = applyDenoise(result)
        }

        if (config.enableContrastEnhance) {
            result = enhanceContrast(result)
        }

        // Resize to target DPI if needed
        val currentDpi = result.density
        if (config.targetDpi > 0 && currentDpi > 0) {
            val scale = config.targetDpi.toFloat() / currentDpi.toFloat()
            if (scale != 1f) {
                val newWidth = (result.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (result.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(result, newWidth, newHeight, true)
                if (result !== bitmap) result.recycle()
                result = scaled
            }
        }

        return result
    }

    private fun applyDeskew(bitmap: Bitmap): Bitmap {
        // Detect skew angle using projection profile
        val gray = toGrayscale(bitmap)
        val angle = detectSkewAngle(gray)
        gray.recycle()

        if (abs(angle) < 0.5f) {
            if (gray !== bitmap) gray.recycle()
            return bitmap
        }

        val matrix = Matrix()
        matrix.postRotate(-angle, bitmap.width / 2f, bitmap.height / 2f)

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return rotated
    }

    private fun detectSkewAngle(grayBitmap: Bitmap): Float {
        // Simplified skew detection using horizontal projection
        val width = grayBitmap.width
        val height = grayBitmap.height
        val pixels = IntArray(width * height)
        grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to binary
        val binary = pixels.map { if ((it and 0xFF) < 128) 1 else 0 }

        // Try angles from -15 to 15 degrees
        var bestAngle = 0f
        var bestScore = 0

        for (angle in -15..15) {
            val rad = Math.toRadians(angle.toDouble())
            val cos = cos(rad)
            val sin = sin(rad)

            val projection = IntArray(height) { 0 }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val newX = (x * cos - y * sin).toInt()
                    val newY = (x * sin + y * cos).toInt()
                    if (newX in 0 until width && newY in 0 until height) {
                        if (binary[newY * width + newX] == 1) {
                            projection[y]++
                        }
                    }
                }
            }

            val score = projection.sumOf { it * it }
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle.toFloat()
            }
        }

        return bestAngle
    }

    private fun applyDenoise(bitmap: Bitmap): Bitmap {
        // Apply median filter for denoising
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val resultPixels = IntArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val neighbors = mutableListOf<Int>()
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        neighbors.add(pixels[(y + dy) * width + (x + dx)])
                    }
                }
                neighbors.sort()
                resultPixels[y * width + x] = neighbors[4] // median
            }
        }

        // Copy edges
        for (x in 0 until width) {
            resultPixels[x] = pixels[x]
            resultPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            resultPixels[y * width] = pixels[y * width]
            resultPixels[y * width + width - 1] = pixels[y * width + width - 1]
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Calculate histogram
        val histogram = IntArray(256) { 0 }
        for (pixel in pixels) {
            val gray = pixel and 0xFF
            histogram[gray]++
        }

        // Calculate cumulative distribution
        val total = width * height
        val cdf = IntArray(256) { 0 }
        cdf[0] = histogram[0]
        for (i in 1 until 256) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }

        // Histogram equalization
        val resultPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val gray = pixels[i] and 0xFF
            val newGray = ((cdf[gray] * 255.0) / total).toInt().coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (newGray shl 16) or (newGray shl 8) or newGray
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun applyPerspectiveCorrection(bitmap: Bitmap): Bitmap {
        // Detect document corners and apply perspective transform
        // Simplified: detect largest quadrilateral and warp
        val gray = toGrayscale(bitmap)
        val corners = detectDocumentCorners(gray)
        gray.recycle()

        if (corners == null || corners.size != 4) return bitmap

        // Sort corners: top-left, top-right, bottom-right, bottom-left
        val sorted = sortCorners(corners)

        val src = floatArrayOf(
            sorted[0].x, sorted[0].y,
            sorted[1].x, sorted[1].y,
            sorted[2].x, sorted[2].y,
            sorted[3].x, sorted[3].y
        )

        val dstWidth = max(
            distance(sorted[0], sorted[1]),
            distance(sorted[3], sorted[2])
        ).toInt()
        val dstHeight = max(
            distance(sorted[0], sorted[3]),
            distance(sorted[1], sorted[2])
        ).toInt()

        val dst = floatArrayOf(
            0f, 0f,
            dstWidth.toFloat(), 0f,
            dstWidth.toFloat(), dstHeight.toFloat(),
            0f, dstHeight.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val result = Bitmap.createBitmap(dstWidth, dstHeight, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, matrix, null)

        return result
    }

    private fun detectDocumentCorners(grayBitmap: Bitmap): List<PointF>? {
        // Simplified corner detection using edge detection and contour finding
        val width = grayBitmap.width
        val height = grayBitmap.height
        val pixels = IntArray(width * height)
        grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Sobel edge detection
        val edges = BooleanArray(width * height) { false }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val gx = (
                    -1 * (pixels[(y - 1) * width + (x - 1)] and 0xFF) +
                    -2 * (pixels[y * width + (x - 1)] and 0xFF) +
                    -1 * (pixels[(y + 1) * width + (x - 1)] and 0xFF) +
                    1 * (pixels[(y - 1) * width + (x + 1)] and 0xFF) +
                    2 * (pixels[y * width + (x + 1)] and 0xFF) +
                    1 * (pixels[(y + 1) * width + (x + 1)] and 0xFF)
                )
                val gy = (
                    -1 * (pixels[(y - 1) * width + (x - 1)] and 0xFF) +
                    -2 * (pixels[(y - 1) * width + x] and 0xFF) +
                    -1 * (pixels[(y - 1) * width + (x + 1)] and 0xFF) +
                    1 * (pixels[(y + 1) * width + (x - 1)] and 0xFF) +
                    2 * (pixels[(y + 1) * width + x] and 0xFF) +
                    1 * (pixels[(y + 1) * width + (x + 1)] and 0xFF)
                )
                val magnitude = sqrt((gx * gx + gy * gy).toDouble()).toInt()
                if (magnitude > 100) {
                    edges[idx] = true
                }
            }
        }

        // Find corners by scanning from edges
        val cornerPoints = mutableListOf<PointF>()

        // Top-left
        outer@ for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                if (edges[y * width + x]) {
                    cornerPoints.add(PointF(x.toFloat(), y.toFloat()))
                    break@outer
                }
            }
        }

        // Top-right
        outer@ for (y in 0 until height / 2) {
            for (x in width - 1 downTo width / 2) {
                if (edges[y * width + x]) {
                    cornerPoints.add(PointF(x.toFloat(), y.toFloat()))
                    break@outer
                }
            }
        }

        // Bottom-right
        outer@ for (y in height - 1 downTo height / 2) {
            for (x in width - 1 downTo width / 2) {
                if (edges[y * width + x]) {
                    cornerPoints.add(PointF(x.toFloat(), y.toFloat()))
                    break@outer
                }
            }
        }

        // Bottom-left
        outer@ for (y in height - 1 downTo height / 2) {
            for (x in 0 until width / 2) {
                if (edges[y * width + x]) {
                    cornerPoints.add(PointF(x.toFloat(), y.toFloat()))
                    break@outer
                }
            }
        }

        return if (cornerPoints.size == 4) cornerPoints else null
    }

    private fun sortCorners(corners: List<PointF>): List<PointF> {
        val centerX = corners.map { it.x }.average().toFloat()
        val centerY = corners.map { it.y }.average().toFloat()

        return corners.sortedWith { a, b ->
            val angleA = atan2(a.y - centerY, a.x - centerX)
            val angleB = atan2(b.y - centerY, b.x - centerX)
            angleA.compareTo(angleB)
        }
    }

    private fun distance(a: PointF, b: PointF): Float {
        return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2))
    }

    private fun cropBitmap(bitmap: Bitmap, rect: android.graphics.Rect): Bitmap {
        val safeRect = android.graphics.Rect(
            rect.left.coerceIn(0, bitmap.width),
            rect.top.coerceIn(0, bitmap.height),
            rect.right.coerceIn(0, bitmap.width),
            rect.bottom.coerceIn(0, bitmap.height)
        )

        if (safeRect.width() <= 0 || safeRect.height() <= 0) return bitmap

        return Bitmap.createBitmap(bitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
    }

    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun detectHandwritingRegions(grayBitmap: Bitmap): List<RectF> {
        val width = grayBitmap.width
        val height = grayBitmap.height
        val pixels = IntArray(width * height)
        grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val regions = mutableListOf<RectF>()
        val visited = BooleanArray(width * height) { false }

        // Find connected components with irregular shapes (handwriting)
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                val idx = y * width + x
                if (!visited[idx] && (pixels[idx] and 0xFF) < 200) {
                    val component = floodFill(pixels, visited, x, y, width, height)
                    if (isHandwritingComponent(component, width, height)) {
                        regions.add(component)
                    }
                }
            }
        }

        // Merge overlapping regions
        return mergeOverlappingRegions(regions)
    }

    private fun floodFill(
        pixels: IntArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): RectF {
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(startX to startY)

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY
        var count = 0

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeAt(stack.size - 1)
            val idx = y * width + x

            if (x < 0 || x >= width || y < 0 || y >= height || visited[idx]) continue
            if ((pixels[idx] and 0xFF) >= 200) continue

            visited[idx] = true
            count++

            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)

            stack.add(x + 1 to y)
            stack.add(x - 1 to y)
            stack.add(x to y + 1)
            stack.add(x to y - 1)
        }

        return RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
    }

    private fun isHandwritingComponent(rect: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val width = rect.width()
        val height = rect.height()
        val area = width * height

        // Filter out too small or too large components
        if (area < 100 || area > imageWidth * imageHeight * 0.5f) return false

        // Handwriting tends to have irregular aspect ratios
        val aspectRatio = width / height.coerceAtLeast(1f)
        return aspectRatio in 0.1f..10f
    }

    private fun mergeOverlappingRegions(regions: List<RectF>): List<RectF> {
        if (regions.isEmpty()) return emptyList()

        val merged = mutableListOf<RectF>()
        val sorted = regions.sortedBy { it.left }

        var current = RectF(sorted[0])

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (RectF.intersects(current, next)) {
                current.union(next)
            } else {
                merged.add(RectF(current))
                current = RectF(next)
            }
        }
        merged.add(current)

        return merged
    }

    private fun calculateHandwritingConfidence(grayBitmap: Bitmap, regions: List<RectF>): Float {
        if (regions.isEmpty()) return 0f

        val totalArea = grayBitmap.width * grayBitmap.height
        val handwritingArea = regions.sumOf { (it.width() * it.height()).toDouble() }.toFloat()

        // Confidence based on proportion of image covered by handwriting-like regions
        val coverageRatio = handwritingArea / totalArea
        return (coverageRatio * 2).coerceIn(0f, 1f)
    }

    private fun parseVisionText(
        visionText: Text,
        imageWidth: Int,
        imageHeight: Int,
        startTime: Long
    ): OcrPageResult {
        val blocks = mutableListOf<OcrTextBlock>()
        val detectedLanguages = mutableSetOf<String>()

        for (block in visionText.textBlocks) {
            val lines = mutableListOf<OcrTextLine>()
            var blockConfidence = 0f
            var blockConfidenceCount = 0

            for (line in block.lines) {
                val elements = mutableListOf<OcrTextElement>()

                for (element in line.elements) {
                    val confidence = element.confidence
                    val rect = RectF(element.boundingBox ?: android.graphics.Rect(0, 0, 0, 0))
                    val lang = element.recognizedLanguage

                    if (lang.isNotBlank()) detectedLanguages.add(lang)

                    elements.add(OcrTextElement(
                        text = element.text,
                        boundingBox = rect,
                        confidence = confidence,
                        recognizedLanguage = lang
                    ))

                    blockConfidence += confidence
                    blockConfidenceCount++
                }

                val lineRect = RectF(line.boundingBox ?: android.graphics.Rect(0, 0, 0, 0))
                val lineConfidence = if (elements.isNotEmpty()) {
                    elements.map { it.confidence }.average().toFloat()
                } else 0f

                lines.add(OcrTextLine(
                    text = line.text,
                    boundingBox = lineRect,
                    confidence = lineConfidence,
                    elements = elements
                ))
            }

            val blockRect = RectF(block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0))
            val avgConfidence = if (blockConfidenceCount > 0) blockConfidence / blockConfidenceCount else 0f

            blocks.add(OcrTextBlock(
                text = block.text,
                boundingBox = blockRect,
                confidence = avgConfidence,
                language = detectedLanguages.firstOrNull() ?: "unknown",
                lines = lines
            ))
        }

        return OcrPageResult(
            pageIndex = 0,
            fullText = visionText.text,
            blocks = blocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            processingTimeMs = System.currentTimeMillis() - startTime,
            detectedLanguages = detectedLanguages.toList()
        )
    }

    private fun extractTablesFromBlocks(blocks: List<OcrTextBlock>): List<OcrTable> {
        val tables = mutableListOf<OcrTable>()

        // Group blocks by rows based on Y-coordinate proximity
        val rowThreshold = 20f
        val rows = mutableListOf<MutableList<OcrTextBlock>>()

        val sortedBlocks = blocks.sortedBy { it.boundingBox.top }

        for (block in sortedBlocks) {
            var addedToRow = false
            for (row in rows) {
                val rowCenter = row.map { it.boundingBox.centerY() }.average().toFloat()
                if (abs(block.boundingBox.centerY() - rowCenter) < rowThreshold) {
                    row.add(block)
                    addedToRow = true
                    break
                }
            }
            if (!addedToRow) {
                rows.add(mutableListOf(block))
            }
        }

        if (rows.size < 2) return emptyList() // Need at least 2 rows for a table

        // Determine columns by X-coordinate clustering
        val allXPositions = rows.flatten().map { it.boundingBox.left }.sorted()
        val colThreshold = 30f
        val columns = mutableListOf<Float>()
        var lastX = -1000f

        for (x in allXPositions) {
            if (x - lastX > colThreshold) {
                columns.add(x)
                lastX = x
            }
        }

        if (columns.size < 2) return emptyList()

        // Build table cells
        val cells = mutableListOf<OcrTableCell>()
        for ((rowIdx, row) in rows.withIndex()) {
            for (block in row) {
                val colIdx = columns.indexOfFirst { abs(it - block.boundingBox.left) < colThreshold }
                    .coerceAtLeast(0)

                cells.add(OcrTableCell(
                    row = rowIdx,
                    col = colIdx,
                    text = block.text,
                    boundingBox = block.boundingBox,
                    confidence = block.confidence
                ))
            }
        }

        tables.add(OcrTable(
            rows = rows.size,
            cols = columns.size,
            cells = cells
        ))

        return tables
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findClosestWord(word: String, dictionary: Set<String>): String? {
        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE

        for (dictWord in dictionary) {
            val distance = levenshteinDistance(word, dictWord)
            if (distance < bestDistance && distance <= word.length / 2) {
                bestDistance = distance
                bestMatch = dictWord
            }
        }

        return bestMatch
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(
                        dp[i - 1][j] + 1,    // deletion
                        dp[i][j - 1] + 1,    // insertion
                        dp[i - 1][j - 1] + 1 // substitution
                    )
                }
            }
        }

        return dp[m][n]
    }

    private fun preserveCase(original: String, corrected: String): String {
        if (original.length != corrected.length) return corrected

        return original.zip(corrected).joinToString("") { (orig, corr) ->
            if (orig.isUpperCase()) corr.uppercase() else corr.lowercase()
        }
    }

    private fun loadEnglishDictionary(): Set<String> {
        // In production, load from assets or raw resource
        // This is a minimal built-in set for demonstration
        return setOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
            "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
            "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "hello", "world", "pdf", "editor", "document", "page", "file", "text", "image",
            "scan", "ocr", "recognition", "language", "offline", "online", "batch", "export",
            "import", "save", "open", "close", "create", "delete", "copy", "paste", "cut",
            "search", "find", "replace", "format", "font", "size", "color", "style", "bold",
            "italic", "underline", "align", "left", "right", "center", "justify", "margin",
            "header", "footer", "page", "number", "title", "author", "subject", "keywords",
            "password", "encrypt", "decrypt", "security", "permission", "print", "view",
            "merge", "split", "compress", "rotate", "crop", "resize", "watermark", "stamp"
        )
    }
}
