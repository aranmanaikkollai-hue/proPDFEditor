package com.propdf.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class OcrRepositoryImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : OcrRepository {

    companion object {
        private const val TAG = "OcrRepo"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(bitmap: Bitmap): AppResult<String> =
        withContext(dispatchers.default) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) {
                            cont.resume(AppResult.Success(result.text.trim().ifBlank { "No text found" }))
                        }
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) {
                            cont.resume(AppResult.Error(e.toAppException()))
                        }
                    }
            }
        }

    override suspend fun recognizeAllPages(bitmaps: List<Bitmap>): AppResult<String> =
        withContext(dispatchers.default) {
            val sb = StringBuilder()
            for ((i, bmp) in bitmaps.withIndex()) {
                val result = recognizeText(bmp)
                result.onSuccess { text ->
                    if (text.isNotBlank() && text != "No text found") {
                        sb.append("--- Page ${i + 1} ---\n")
                        sb.append(text)
                        sb.append("\n\n")
                    }
                }
            }
            AppResult.Success(sb.toString().trim().ifBlank { "No text found in document" })
        }

    override suspend fun findPagesContaining(bitmaps: List<Bitmap>, query: String): AppResult<List<Int>> =
        withContext(dispatchers.default) {
            if (query.isBlank()) return@withContext AppResult.Success(emptyList())
            val matching = mutableListOf<Int>()
            val lowerQuery = query.lowercase()
            for ((i, bmp) in bitmaps.withIndex()) {
                val result = recognizeText(bmp)
                result.onSuccess { text ->
                    if (text.lowercase().contains(lowerQuery)) matching.add(i)
                }
            }
            AppResult.Success(matching)
        }

    override suspend fun recognizeFromUri(context: Context, uri: Uri): AppResult<String> =
        withContext(dispatchers.io) {
            if (uri.toString().isBlank()) {
                return@withContext AppResult.Error(com.propdf.core.domain.result.AppException.FileNotFound("Empty URI"))
            }
            val bmp: Bitmap? = try {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                return@withContext AppResult.Error(e.toAppException())
            }
            if (bmp == null || bmp.isRecycled) {
                return@withContext AppResult.Error(
                    com.propdf.core.domain.result.AppException.InvalidFormat("Could not decode image")
                )
            }
            recognizeText(bmp)
        }

    override fun release() {
        try { recognizer.close() } catch (_: Exception) {}
    }
}
