package com.propdfeditor.batch.worker

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class RenameWorker(
    context: Context,
    params: WorkerParameters,
    repository: BatchJobRepository,
    notificationManager: BatchNotificationManager
) : BaseBatchWorker(context, params, repository, notificationManager) {

    data class RenameConfig(
        val pattern: String, // e.g., "Document_{index}_{date}"
        val startIndex: Int = 1,
        val prefix: String = "",
        val suffix: String = ""
    )

    override suspend fun executeBatch(): androidx.work.Data {
        val job = currentJob ?: throw IllegalStateException("Job not initialized")
        val config = Gson().fromJson(job.configJson, RenameConfig::class.java)
        val inputUris = job.inputUris

        return withContext(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0

            inputUris.forEachIndexed { index, uri ->
                if (isStopped) {
                    isCancelled = true
                    return@withContext workDataOf("cancelled" to true)
                }

                try {
                    val documentFile = DocumentFile.fromSingleUri(applicationContext, uri)
                    val parent = documentFile?.parentFile
                    val extension = documentFile?.name?.substringAfterLast('.', 'pdf') ?: 'pdf'
                    
                    val newName = buildNewName(config, index + config.startIndex, extension)
                    
                    if (parent != null && documentFile != null) {
                        val newUri = parent.createFile("application/pdf", newName)?.uri
                        if (newUri != null) {
                            // Copy content to new file
                            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                                applicationContext.contentResolver.openOutputStream(newUri)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            documentFile.delete()
                            successCount++
                        } else {
                            failCount++
                        }
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Rename failed for $uri")
                    failCount++
                }

                val progress = ((index + 1) * 100) / inputUris.size
                updateProgress(progress, index + 1, inputUris.size)
            }

            workDataOf(
                "success_count" to successCount,
                "fail_count" to failCount
            )
        }
    }

    private fun buildNewName(config: RenameConfig, index: Int, extension: String): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        return config.pattern
            .replace("{index}", index.toString().padStart(3, '0'))
            .replace("{date}", date)
            .replace("{prefix}", config.prefix)
            .replace("{suffix}", config.suffix) + ".$extension"
    }
}
