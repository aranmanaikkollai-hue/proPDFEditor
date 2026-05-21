package com.propdf.scanner.engine.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR Engine stub.
 * 
 * To enable full OCR, add to scanner/build.gradle:
 *   implementation 'com.google.mlkit:text-recognition:16.0.0'
 * 
 * Then replace this stub with the full ML Kit implementation.
 */
class MlKitOcrEngine(context: Context) {

    /**
     * Recognize text from a bitmap.
     * Returns empty result if ML Kit is not available.
     */
    suspend fun recognizeText(bitmap: Bitmap): Result<OcrResult> = withContext(Dispatchers.Default) {
        try {
            // TODO: Replace with ML Kit TextRecognition when dependency is added
            // val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            // val image = InputImage.fromBitmap(bitmap, 0)
            // val visionText = recognizer.process(image).await()

            Result.success(OcrResult(
                fullText = "",
                blocks = emptyList(),
                language = "en"
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extract raw text only (fast path).
     */
    suspend fun extractText(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        try {
            // TODO: Replace with ML Kit when dependency is available
            Result.success("")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Release resources.
     */
    fun close() {
        // Nothing to release in stub
    }

    private fun detectLanguage(text: String): String {
        return when {
            text.any { it in '\u0600'..'\u06FF' } -> "ar"
            text.any { it in '\u0400'..'\u04FF' } -> "ru"
            text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
            text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' } -> "ja"
            else -> "en"
        }
    }
}

data class OcrResult(
    val fullText: String,
    val blocks: List<OcrBlock>,
    val language: String
)

data class OcrBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect?,
    val lines: List<OcrLine>
)

data class OcrLine(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect?,
    val words: List<OcrWord>
)

data class OcrWord(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect?
)
