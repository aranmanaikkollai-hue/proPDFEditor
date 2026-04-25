package com.propdf.editor.data.repository

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class OcrManager @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // -------------------------------------------------------
    // TEXT RECOGNITION  (FIX: guard cont.isActive before resume
    // to prevent IllegalStateException on cancelled coroutines)
    // -------------------------------------------------------

    suspend fun recognizeText(bitmap: Bitmap): Result<String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    // FIX: only resume if coroutine is still active
                    if (cont.isActive) {
                        cont.resume(Result.success(text.ifBlank { "No text found" }))
                    }
                }
                .addOnFailureListener { e ->
                    // FIX: only resume if coroutine is still active
                    if (cont.isActive) {
                        cont.resume(Result.failure(e))
                    }
                }
        }

    // -------------------------------------------------------
    // MULTI-PAGE OCR  (new: process list of bitmaps sequentially)
    // -------------------------------------------------------

    suspend fun recognizeAllPages(bitmaps: List<Bitmap>): Result<String> {
        val sb = StringBuilder()
        for ((i, bmp) in bitmaps.withIndex()) {
            val result = recognizeText(bmp)
            result.onSuccess { text ->
                if (text.isNotBlank() && text != "No text found") {
                    sb.append("--- Page ${i + 1} ---\n")
                    sb.append(text)
                    sb.append("\n\n")
                }
            }.onFailure { /* skip failed pages silently */ }
        }
        val combined = sb.toString().trim()
        return Result.success(combined.ifBlank { "No text found in document" })
    }

    // -------------------------------------------------------
    // SEARCH  (find pages containing query text)
    // -------------------------------------------------------

    suspend fun findPagesContaining(
        bitmaps: List<Bitmap>,
        query: String
    ): List<Int> {
        if (query.isBlank()) return emptyList()
        val matchingPages = mutableListOf<Int>()
        val lowerQuery = query.lowercase()
        for ((i, bmp) in bitmaps.withIndex()) {
            val result = recognizeText(bmp)
            result.onSuccess { text ->
                if (text.lowercase().contains(lowerQuery)) {
                    matchingPages.add(i)
                }
            }
        }
        return matchingPages
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    fun release() {
        try { recognizer.close() } catch (_: Exception) {}
    }
}
