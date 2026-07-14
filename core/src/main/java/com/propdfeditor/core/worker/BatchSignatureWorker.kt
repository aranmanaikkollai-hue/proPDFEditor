package com.propdfeditor.core.worker

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.pdf.signature.PdfSignatureEngine
import com.propdfeditor.core.repository.SignatureRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class BatchSignatureWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val signatureRepository: SignatureRepository,
    private val pdfSignatureEngine: PdfSignatureEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val documentUriString = inputData.getString(KEY_DOCUMENT_URI) ?: return Result.failure()
        val signatureId = inputData.getLong(KEY_SIGNATURE_ID, -1)
        val pageNumbers = inputData.getIntArray(KEY_PAGE_NUMBERS) ?: intArrayOf(1)
        val rectLeft = inputData.getFloat(KEY_RECT_LEFT, 0f)
        val rectTop = inputData.getFloat(KEY_RECT_TOP, 0f)
        val rectRight = inputData.getFloat(KEY_RECT_RIGHT, 100f)
        val rectBottom = inputData.getFloat(KEY_RECT_BOTTOM, 50f)

        return try {
            val documentUri = Uri.parse(documentUriString)
            val signature = signatureRepository.getSignatureById(signatureId) ?: return Result.failure()
            val bitmap = signatureRepository.getSignatureBitmap(signature) ?: return Result.failure()

            val outputDir = File(applicationContext.cacheDir, "batch_signed").apply { mkdirs() }
            val outputFile = File(outputDir, "batch_signed_${System.currentTimeMillis()}.pdf")

            // Apply signature to first page (batch logic can be extended)
            val rect = RectF(rectLeft, rectTop, rectRight, rectBottom)

            pdfSignatureEngine.applyVisualSignature(
                inputUri = documentUri,
                outputFile = outputFile,
                signatureBitmap = bitmap,
                pageNumber = pageNumbers.firstOrNull() ?: 1,
                rect = rect
            ).getOrThrow()

            val outputData = Data.Builder()
                .putString(KEY_OUTPUT_PATH, outputFile.absolutePath)
                .build()

            Result.success(outputData)
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString(KEY_ERROR, e.message).build())
        }
    }

    companion object {
        const val WORK_NAME = "batch_signature_worker"
        const val KEY_DOCUMENT_URI = "document_uri"
        const val KEY_SIGNATURE_ID = "signature_id"
        const val KEY_PAGE_NUMBERS = "page_numbers"
        const val KEY_RECT_LEFT = "rect_left"
        const val KEY_RECT_TOP = "rect_top"
        const val KEY_RECT_RIGHT = "rect_right"
        const val KEY_RECT_BOTTOM = "rect_bottom"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"
    }
}
