package com.propdf.scanner.engine.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * OCR Engine backed by ML Kit on-device text recognition.
 */
@Singleton
class MlKitOcrEngine @Inject constructor(@ApplicationContext _context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * Recognize text from a bitmap, including block/line/word structure.
     */
    suspend fun recognizeText(_bitmap: Bitmap): Result<OcrResult> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(_bitmap, 0)
            val visionText = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            } ?: return@withContext Result.success(OcrResult(fullText = "", blocks = emptyList(), language = "en"))

            val blocks = visionText.textBlocks.map { block ->
                OcrBlock(
                    text = block.text,
                    confidence = 1f,
                    boundingBox = block.boundingBox,
                    lines = block.lines.map { line ->
                        OcrLine(
                            text = line.text,
                            confidence = 1f,
                            boundingBox = line.boundingBox,
                            words = line.elements.map { element ->
                                OcrWord(
                                    text = element.text,
                                    confidence = 1f,
                                    boundingBox = element.boundingBox
                                )
                            }
                        )
                    }
                )
            }
            Result.success(
                OcrResult(
                    fullText = visionText.text,
                    blocks = blocks,
                    language = detectLanguage(visionText.text)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extract raw text only (fast path).
     */
    suspend fun extractText(_bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(_bitmap, 0)
            val text = suspendCancellableCoroutine<String> { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it.text) }
                    .addOnFailureListener { continuation.resume("") }
            }
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Release resources.
     */
    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
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
