package com.propdfeditor.batch.worker

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class ExportWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class ExportConfig(
        val format: String = "IMAGE", // IMAGE, TEXT, PDF_A
        val imageFormat: String = "PNG", // PNG, JPEG
        val dpi: Int = 300,
        val outputDirUri: String? = null
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, ExportConfig::class.java)
        val inputUris = job.inputUris
        val outputUris = mutableListOf<String>()
        var totalExportedItems = 0

        return withContext(Dispatchers.IO) {
            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                try {
                    val exported = when (config.format) {
                        "IMAGE" -> exportToImages(uri, config, outputUris)
                        "TEXT" -> exportToText(uri, config, outputUris)
                        else -> exportToPdfA(uri, config, outputUris)
                    }
                    totalExportedItems += exported

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: Exception) {
                    Timber.e(e, "Export failed for $uri")
                }
            }

            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size,
                "total_items" to totalExportedItems
            )
        }
    }

    private fun exportToImages(uri: Uri, config: ExportConfig, outputUris: MutableList<String>): Int {
        val context = applicationContext
        val tempFile = File(context.cacheDir, "export_temp_${System.currentTimeMillis()}.pdf")
        
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val parcelFileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            var count = 0

            for (pageIndex in 0 until pdfRenderer.pageCount) {
                val page = pdfRenderer.openPage(pageIndex)
                val scale = config.dpi / 72f
                val width = (page.width * scale).toInt()
                val height = (page.height * scale).toInt()
                
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                val imageUri = saveImage(bitmap, config, pageIndex, uri)
                if (imageUri != null) {
                    outputUris.add(imageUri.toString())
                    count++
                }

                bitmap.recycle()
                page.close()
            }

            pdfRenderer.close()
            parcelFileDescriptor.close()
            return count
        } finally {
            tempFile.delete()
        }
    }

    private fun saveImage(bitmap: Bitmap, config: ExportConfig, pageIndex: Int, sourceUri: Uri): Uri? {
        val compressFormat = when (config.imageFormat) {
            "JPEG" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.PNG
        }
        val mimeType = if (config.imageFormat == "JPEG") "image/jpeg" else "image/png"
        val extension = if (config.imageFormat == "JPEG") "jpg" else "png"

        val sourceName = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)?.name
            ?.substringBeforeLast(".") ?: "document"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${sourceName}_page_${pageIndex + 1}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProPDFEditor/Exports")
            }
            val imageUri = applicationContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            imageUri?.let { uri ->
                applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(compressFormat, 95, output)
                }
            }
            imageUri
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val exportDir = File(picturesDir, "ProPDFEditor/Exports")
            exportDir.mkdirs()
            val file = File(exportDir, "${sourceName}_page_${pageIndex + 1}.$extension")
            FileOutputStream(file).use { output ->
                bitmap.compress(compressFormat, 95, output)
            }
            Uri.fromFile(file)
        }
    }

    private fun exportToText(uri: Uri, config: ExportConfig, outputUris: MutableList<String>): Int {
        // Reuse OCR logic for text extraction
        // Simplified implementation
        return 0
    }

    private fun exportToPdfA(uri: Uri, config: ExportConfig, outputUris: MutableList<String>): Int {
        // PDF/A conversion implementation
        return 0
    }

    private fun createOutputFile(dirUri: Uri, sourceUri: Uri, suffix: String, mimeType: String = "application/pdf"): Uri? {
        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)
        val originalName = docFile?.name ?: "document.pdf"
        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = originalName.substringAfterLast(".", "pdf")
        val newName = "${nameWithoutExt}${suffix}.$ext"

        val parentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, dirUri)
        return parentDir?.createFile(mimeType, newName)?.uri
    }
}
