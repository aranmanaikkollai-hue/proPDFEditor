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
 */
class OcrSearchEngine(context: Context) {

    companion object {
        private const val TAG = "OcrSearchEngine"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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

    suspend fun extractTextBatch(bitmaps: List<Bitmap>): List<String> = withContext(Dispatchers.IO) {
        bitmaps.map { bitmap ->
            extractText(bitmap)
        }
    }

    fun isAvailable(): Boolean {
        return try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        textRecognizer.close()
    }
}
