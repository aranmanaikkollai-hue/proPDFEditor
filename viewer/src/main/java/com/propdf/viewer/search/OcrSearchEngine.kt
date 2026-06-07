package com.propdf.viewer.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline OCR search engine using ML Kit (no cloud dependency).
 * Extracts text from scanned PDF pages for indexing.
 *
 * Uses ML Kit's on-device text recognition (Latin script).
 * Falls back gracefully if ML Kit is unavailable.
 */
class OcrSearchEngine(context: Context) {

    companion object {
        private const val TAG = "OcrSearchEngine"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Extract text from a bitmap using ML Kit OCR.
     * Runs on IO dispatcher to avoid blocking.
     *
     * @param bitmap Page bitmap to OCR
     * @return Extracted text string, or empty if no text detected
     */
    suspend fun extractText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            suspendCancellableCoroutine { continuation ->
                val task = textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val extractedText = visionText.text
                        Log.d(TAG, "OCR extracted ${extractedText.length} chars")
                        continuation.resume(extractedText)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "OCR failed", e)
                        continuation.resumeWithException(e)
                    }

                continuation.invokeOnCancellation {
                    task.cancel()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            ""
        }
    }

    /**
     * Batch OCR multiple bitmaps.
     * Processes sequentially to avoid memory pressure.
     */
    suspend fun extractTextBatch(bitmaps: List<Bitmap>): List<String> = withContext(Dispatchers.IO) {
        bitmaps.map { bitmap ->
            extractText(bitmap)
        }
    }

    /**
     * Check if OCR engine is available (ML Kit initialized).
     */
    fun isAvailable(): Boolean {
        return try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Release OCR resources when no longer needed.
     */
    fun close() {
        textRecognizer.close()
    }
}
