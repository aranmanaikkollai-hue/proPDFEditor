package com.propdfeditor.batch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.repository.OcrRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class BatchOperationsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ocrRepository: OcrRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "propdf_batch_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_OPERATION = "operation"
        const val KEY_URIS = "uris"
        const val KEY_QUALITY = "quality"
        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_RESULT_URIS = "result_uris"
        const val OPERATION_COMPRESS = "compress"
        const val OPERATION_OCR = "ocr"
        const val OPERATION_MERGE = "merge"
        const val OPERATION_CONVERT_IMAGES = "convert_images"
    }

    override suspend fun doWork(): Result {
        val operation = inputData.getString(KEY_OPERATION) ?: return Result.failure()
        val uris = inputData.getStringArray(KEY_URIS) ?: return Result.failure()

        if (uris.isEmpty()) return Result.failure()

        setForeground(createForegroundInfo(operation, 0, uris.size))

        PDFBoxResourceLoader.init(applicationContext)

        return try {
            when (operation) {
                OPERATION_COMPRESS -> batchCompress(uris.toList())
                OPERATION_OCR -> batchOcr(uris.toList())
                OPERATION_MERGE -> batchMerge(uris.toList())
                OPERATION_CONVERT_IMAGES -> batchConvertToImages(uris.toList())
                else -> Result.failure()
            }
        } catch (e: Exception) {
            Result.failure(
                workDataOf("error" to (e.message ?: "Unknown batch error"))
            )
        }
    }

    private suspend fun batchCompress(uris: List<String>): Result = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        val quality = inputData.getFloat(KEY_QUALITY, 0.7f)

        uris.forEachIndexed { index, uriString ->
            setForeground(createForegroundInfo("Compressing", index + 1, uris.size))
            setProgress(workDataOf("progress" to index + 1, "total" to uris.size))

            try {
                val uri = Uri.parse(uriString)
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    val doc = PDDocument.load(input)
                    val outputFile = File(applicationContext.cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
                    doc.save(outputFile)
                    doc.close()
                    results.add(outputFile.toURI().toString())
                }
            } catch (e: Exception) {
                // Continue with next file, log error
            }
        }

        Result.success(
            workDataOf(KEY_RESULT_URIS to results.toTypedArray())
        )
    }

    private suspend fun batchOcr(uris: List<String>): Result = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()

        uris.forEachIndexed { index, uriString ->
            setForeground(createForegroundInfo("OCR Processing", index + 1, uris.size))
            setProgress(workDataOf("progress" to index + 1, "total" to uris.size))

            try {
                val uri = Uri.parse(uriString)
                ocrRepository.performOcr(uri, "en")
                    .onSuccess { record ->
                        results.add(record.id)
                    }
            } catch (e: Exception) {
                // Continue with next file
            }
        }

        Result.success(
            workDataOf(KEY_RESULT_URIS to results.toTypedArray())
        )
    }

    private suspend fun batchMerge(uris: List<String>): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo("Merging", 1, 1))

        val mergedDoc = PDDocument()
        uris.forEach { uriString ->
            try {
                val uri = Uri.parse(uriString)
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input).use { doc ->
                        doc.pages.forEach { page ->
                            mergedDoc.addPage(mergedDoc.importPage(page))
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip failed files
            }
        }

        val outputFile = File(applicationContext.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
        mergedDoc.save(outputFile)
        mergedDoc.close()

        Result.success(
            workDataOf(KEY_RESULT_URIS to arrayOf(outputFile.toURI().toString()))
        )
    }

    private suspend fun batchConvertToImages(uris: List<String>): Result = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()

        uris.forEachIndexed { index, uriString ->
            setForeground(createForegroundInfo("Converting", index + 1, uris.size))

            try {
                val uri = Uri.parse(uriString)
                applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input).use { doc ->
                        val renderer = com.tom_roush.pdfbox.rendering.PDFRenderer(doc)
                        for (pageIndex in 0 until doc.numberOfPages) {
                            val bitmap = renderer.renderImageWithDPI(pageIndex, 150f)
                            val imageFile = File(
                                applicationContext.cacheDir,
                                "page_${System.currentTimeMillis()}_$pageIndex.jpg"
                            )
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, imageFile.outputStream())
                            results.add(imageFile.toURI().toString())
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue
            }
        }

        Result.success(
            workDataOf(KEY_RESULT_URIS to results.toTypedArray())
        )
    }

    private fun createForegroundInfo(operation: String, current: Int, total: Int): ForegroundInfo {
        val title = "ProPDF Batch: ${operation.replaceFirstChar { it.uppercase() }}"
        val progress = if (total > 0) (current * 100 / total) else 0

        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$current of $total files processed")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Batch Operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background PDF batch processing"
            }
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
