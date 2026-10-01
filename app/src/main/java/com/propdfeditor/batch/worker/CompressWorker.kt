package com.propdfeditor.batch.worker

import android.content.Context
import android.net.Uri
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.QualityPreset
import com.propdfeditor.batch.util.PdfProcessor
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class CompressWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class CompressConfig(
        val quality: String = "MEDIUM", // LOW, MEDIUM, HIGH
        val compressImages: Boolean = true,
        val removeMetadata: Boolean = false,
        val outputDirUri: String
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, CompressConfig::class.java)
        val inputUris = job.inputUris
        val outputDirUri = Uri.parse(config.outputDirUri)
        val outputUris = mutableListOf<String>()
        val sizeReductions = mutableListOf<Long>()

        return withContext(Dispatchers.IO) {
            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                var outputFile: Uri? = null
                try {
                    outputFile = createOutputFile(outputDirUri, uri, "_compressed")
                        ?: throw IllegalStateException("Cannot create output file")

                    val result = pdfProcessor.compressPdf(applicationContext, uri, outputFile, toCompressionConfig(config))
                    sizeReductions.add(result.originalSizeBytes - result.compressedSizeBytes)
                    outputUris.add(outputFile.toString())
                    recordSuccess()

                    val progress = ((index + 1) * 100) / inputUris.size
                    updateProgress(progress, index + 1, inputUris.size)
                } catch (e: CancellationException) {
                    deleteOutputQuietly(outputFile)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Compress failed for $uri")
                    recordFailure()
                    deleteOutputQuietly(outputFile)
                }
            }

            val totalSaved = sizeReductions.sum()
            workDataOf(
                "output_uris" to Gson().toJson(outputUris),
                "count" to outputUris.size,
                "total_bytes_saved" to totalSaved
            )
        }
    }

    /** Maps the batch settings onto the single PDFBox compression implementation in :core. */
    private fun toCompressionConfig(config: CompressConfig): CompressionConfig {
        val base = when (config.quality) {
            "LOW" -> QualityPreset.SCREEN.config
            "HIGH" -> QualityPreset.PRINTER.config
            else -> QualityPreset.EBOOK.config
        }
        return base.copy(
            removeMetadata = config.removeMetadata,
            optimizeImages = config.compressImages,
            linearize = false
        )
    }

    private fun createOutputFile(dirUri: Uri, sourceUri: Uri, suffix: String): Uri? {
        val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(applicationContext, sourceUri)
        val originalName = docFile?.name ?: "document.pdf"
        val nameWithoutExt = originalName.substringBeforeLast(".")
        val ext = originalName.substringAfterLast(".", "pdf")
        val newName = "${nameWithoutExt}${suffix}.$ext"

        val parentDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(applicationContext, dirUri)
        return parentDir?.createFile("application/pdf", newName)?.uri
    }
}
