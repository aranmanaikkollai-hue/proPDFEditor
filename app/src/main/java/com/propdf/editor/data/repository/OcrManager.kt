package com.propdf.editor.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optimized OCR Manager with:
 * - Proper resource cleanup after each recognition
 * - Error isolation: one failed page doesn't crash the whole operation
 * - Memory-efficient: InputImage created from Bitmap without copies where possible
 * - Latin script only (removed translate to save APK size)
 */
class OcrManager {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isReleased = false

    companion object {
        private const val TAG = "OcrManager"
    }

    /**
     * Recognize text from bitmap. Returns empty string on failure (never crashes).
     */
    suspend fun recognize(bitmap: Bitmap): String {
        if (isReleased) return ""

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { continuation ->
                val task = recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(visionText.text)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR failed: ${e.message}")
                        continuation.resume("") // Return empty on failure, don't crash
                    }

                continuation.invokeOnCancellation {
                    // Note: com.google.android.gms.tasks.Task has no cancel() API —
                    // ML Kit recognition tasks can't be cancelled once started. The
                    // success/failure listeners above will still fire, but resuming an
                    // already-cancelled continuation is a safe no-op in kotlinx.coroutines.
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR exception: ${e.message}")
            ""
        }
    }

    /**
     * Recognize text from a list of bitmaps with progress callback.
     * Processes sequentially to prevent memory spikes.
     */
    suspend fun recognizeAll(
        bitmaps: List<Bitmap>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): List<String> {
        val results = mutableListOf<String>()
        bitmaps.forEachIndexed { index, bmp ->
            if (isReleased) return@forEachIndexed
            onProgress?.invoke(index + 1, bitmaps.size)
            results.add(recognize(bmp))
        }
        return results
    }

    /**
     * Release recognizer resources. Must be called when done.
     */
    fun release() {
        isReleased = true
        try {
            recognizer.close()
        } catch (_: Exception) {}
    }

    protected fun finalize() {
        if (!isReleased) {
            Log.w(TAG, "OcrManager not released — memory leak detected")
            release()
        }
    }
}
