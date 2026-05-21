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
import kotlin.coroutines.resume

class MlKitOcrEngine(context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): Result<OcrResult> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = suspendCancellableCoroutine { continuation ->
                val task = recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
                continuation.invokeOnCancellation { task.cancel() }
            }

            if (visionText == null) {
                return@withContext Result.failure(Exception("OCR processing failed"))
            }

            val blocks = mutableListOf<OcrBlock>()
            visionText.textBlocks.forEach { block ->
                val lines = mutableListOf<OcrLine>()
                block.lines.forEach { line ->
                    val words = mutableListOf<OcrWord>()
                    line.elements.forEach { element ->
                        words.add(OcrWord(
                            text = element.text,
                            confidence = element.confidence,
                            boundingBox = element.boundingBox
                        ))
                    }
                    lines.add(OcrLine(
                        text = line.text,
                        confidence = line.confidence,
                        boundingBox = line.boundingBox,
                        words = words
                    ))
                }
                blocks.add(OcrBlock(
                    text = block.text,
                    confidence = block.confidence,
                    boundingBox = block.boundingBox,
                    lines = lines
                ))
            }

            Result.success(OcrResult(
                fullText = visionText.text,
                blocks = blocks,
                language = detectLanguage(visionText.text)
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun extractText(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = suspendCancellableCoroutine { continuation ->
                val task = recognizer.process(image)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
                continuation.invokeOnCancellation { task.cancel() }
            }
            Result.success(visionText?.text ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        recognizer.close()
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
