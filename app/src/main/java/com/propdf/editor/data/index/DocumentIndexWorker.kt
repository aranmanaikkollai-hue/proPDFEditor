package com.propdf.editor.data.index

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.logger.AppLogger
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.data.local.entity.IndexingStatus
import com.propdf.editor.data.local.entity.SearchIndexEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class DocumentIndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfDocumentDao: PdfDocumentDao,
    private val searchIndexDao: SearchIndexDao,
    private val documentIndexEngine: DocumentIndexEngine,
    private val logger: AppLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Get documents that haven't been indexed yet
            val pending = searchIndexDao.getPendingForProcessing()
            var indexed = 0

            for (entity in pending) {
                val doc = pdfDocumentDao.getByIdString(entity.documentId) ?: continue
                try {
                    val uri = android.net.Uri.parse(doc.uri)
                    val file = when (uri.scheme) {
                        "file" -> File(uri.path ?: continue)
                        else -> continue // can only index file:// URIs
                    }
                    if (!file.exists()) continue

                    val success = documentIndexEngine.indexDocument(doc.id.toString(), file)
                    if (success) indexed++
                } catch (e: Exception) {
                    logger.e("DocumentIndexWorker", "Failed to index ${doc.id}", e)
                }
            }

            // Also create pending entries for docs that have no index entry yet
            val allDocs = pdfDocumentDao.getAllDocuments()
            for (doc in allDocs) {
                val existing = searchIndexDao.getByDocumentId(doc.id.toString())
                if (existing == null) {
                    searchIndexDao.insertOrUpdate(
                        SearchIndexEntity(
                            documentId = doc.id.toString(),
                            fileName = doc.fileName,
                            contentText = null,
                            pageCount = doc.pageCount,
                            indexingStatus = IndexingStatus.PENDING
                        )
                    )
                }
            }

            Result.success(workDataOf("indexed" to indexed))
        } catch (e: Exception) {
            logger.e("DocumentIndexWorker", "Worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "document_index_work"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DocumentIndexWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DocumentIndexWorker>().build()
            )
        }
    }
}
