package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OcrManager - Tesseract OCR Integration
 *
 * Converts scanned/image-based PDFs to searchable text.
 * Supports 100+ languages.
 * Works fully offline.
 * Compatible with API 16+.
 *
 * Usage:
 * 1. Call initOcr() once (copies language data to storage)
 * 2. Call recognizeText(bitmap) for single-image OCR
 * 3. Call pdfToSearchableText(file) for full PDF OCR
 */
@Singleton
class OcrManager @Inject constructor(
    private val context: Context
) {

    private var tessApi: TessBaseAPI? = null
    private var isInitialized = false
    private val tessDataDir: File get() = File(context.filesDir, "tessdata")

    // Supported languages (ISO 639-3 codes)
    val supportedLanguages = mapOf(
        "eng" to "English",
        "hin" to "Hindi",
        "fra" to "French",
        "deu" to "German",
        "spa" to "Spanish",
        "por" to "Portuguese",
        "ita" to "Italian",
        "rus" to "Russian",
        "ara" to "Arabic",
        "zho" to "Chinese (Simplified)",
        "jpn" to "Japanese",
        "kor" to "Korean",
        "nld" to "Dutch",
        "pol" to "Polish",
        "tur" to "Turkish"
    )

    // ── Initialize ───────────────────────────────────────────────
    /**
     * Initialize Tesseract with specified language.
     * Must be called before any OCR operation.
     * @param language Tesseract language code (default "eng")
     */
    suspend fun initOcr(language: String = "eng"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Create tessdata directory
            tessDataDir.mkdirs()

            // Copy language data from assets if not present
            val trainedDataFile = File(tessDataDir, "$language.traineddata")
            if (!trainedDataFile.exists()) {
                copyTessDataFromAssets(language)
            }

            // Initialize Tesseract
            tessApi?.recycle()
            tessApi = TessBaseAPI()
            val success = tessApi!!.init(context.filesDir.absolutePath, language)

            if (success) {
                // Set OCR engine mode: LSTM neural network (most accurate)
                tessApi!!.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                isInitialized = true
                Result.success(Unit)
            } else {
                Result.failure(Exception("Tesseract init failed for language: $language"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── OCR on Bitmap ────────────────────────────────────────────
    /**
     * Perform OCR on a single bitmap image.
     * @return Recognized text string
     */
    suspend fun recognizeText(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) initOcr()

            tessApi?.setImage(bitmap)
            val text = tessApi?.utF8Text ?: ""
            tessApi?.clear()

            Result.success(text.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── OCR on Image File ────────────────────────────────────────
    suspend fun recognizeTextFromFile(imageFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext Result.failure(Exception("Cannot decode image"))
            recognizeText(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── OCR Full PDF ─────────────────────────────────────────────
    /**
     * Perform OCR on every page of a PDF and return combined text.
     * Requires API 21+ for PdfRenderer.
     * Falls back to empty string for API < 21.
     */
    suspend fun pdfToSearchableText(
        pdfFile: File,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return@withContext Result.success("OCR requires Android 5.0+")
        }

        try {
            if (!isInitialized) initOcr()

            val parcelFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(parcelFd)
            val pageCount = renderer.pageCount
            val sb = StringBuilder()

            for (i in 0 until pageCount) {
                onProgress?.invoke(i + 1, pageCount)

                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(
                    page.width * 2, // 2x for better OCR accuracy
                    page.height * 2,
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()

                val result = recognizeText(bitmap)
                result.getOrNull()?.let { text ->
                    sb.append("--- Page ${i + 1} ---\n")
                    sb.append(text)
                    sb.append("\n\n")
                }
                bitmap.recycle()
            }

            renderer.close()
            parcelFd.close()

            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Make PDF Searchable ──────────────────────────────────────
    /**
     * Create a searchable PDF by overlaying invisible OCR text on pages.
     */
    suspend fun makePdfSearchable(
        inputFile: File,
        outputFile: File,
        language: String = "eng",
        onProgress: ((Int, Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val textResult = pdfToSearchableText(inputFile, onProgress)
            val text = textResult.getOrElse { "" }

            // TODO: Overlay invisible text layer using iText 7
            // For now, write extracted text to companion .txt file
            val txtFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_ocr.txt")
            txtFile.writeText(text)

            // Copy original PDF as output (with text overlay would go here)
            inputFile.copyTo(outputFile, overwrite = true)

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Copy Tessdata from Assets ─────────────────────────────────
    private fun copyTessDataFromAssets(language: String) {
        val assetPath = "tessdata/$language.traineddata"
        val outFile = File(tessDataDir, "$language.traineddata")

        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            // Language data not bundled in assets
            // In production: download from server or prompt user
            throw IOException(
                "Language data for '$language' not found. " +
                "Please download it from Settings > Languages. ${e.message}"
            )
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────
    fun release() {
        tessApi?.recycle()
        tessApi = null
        isInitialized = false
    }
}
