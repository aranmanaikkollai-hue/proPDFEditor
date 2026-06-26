package com.propdf.editor.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.logger.AppLogger
import com.propdf.editor.data.hash.FileHashEngine
import com.propdf.editor.data.local.dao.FileHashDao
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.entity.FileHashEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.*
import java.util.concurrent.TimeUnit

@HiltWorker
class DuplicateScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val fileHashEngine: FileHashEngine,
    private val fileHashDao: FileHashDao,
    private val pdfDocumentDao: PdfDocumentDao,
    private val logger: AppLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val allDocuments = pdfDocumentDao.getAllDocuments()
            var processed = 0
            val total = allDocuments.size
            setProgress(workDataOf("total" to total, "processed" to 0))

            for (doc in allDocuments) {
                val existingHash = fileHashDao.getByDocumentId(doc.id.toString())
                if (existingHash != null && existingHash.strongHash != null) continue

                try {
                    val uri = android.net.Uri.parse(doc.uri)
                    val (fastHash, strongHash) = fileHashEngine.computeBothHashes(uri, doc.fileSize)
                    fileHashDao.insert(
                        FileHashEntity(
                            documentId = doc.id.toString(),
                            fileSize = doc.fileSize,
                            fastHash = fastHash,
                            strongHash = strongHash,
                            pageCount = doc.pageCount
                        )
                    )
                } catch (e: Exception) {
                    logger.e("DuplicateScan", "Hash failed for ${doc.id}", e)
                }
                processed++
                if (processed % 10 == 0) {
                    setProgress(workDataOf("total" to total, "processed" to processed))
                }
            }

            groupDuplicates()
            Result.success(workDataOf("processed" to processed))
        } catch (e: Exception) {
            logger.e("DuplicateScan", "Worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun groupDuplicates() {
        val strongDuplicates = fileHashDao.findDuplicateStrongHashes()
        for (dup in strongDuplicates) {
            dup.strongHash ?: continue
            val matches = fileHashDao.getByStrongHash(dup.strongHash)
            if (matches.size > 1) {
                val groupId = UUID.randomUUID().toString()
                fileHashDao.assignGroup(groupId, matches.map { it.documentId })
            }
        }

        val fastDuplicates = fileHashDao.findDuplicateFastHashes()
        for (dup in fastDuplicates) {
            val matches = fileHashDao.getByFastHash(dup.fastHash)
            val ungrouped = matches.filter { it.duplicateGroupId == null && it.strongHash == null }
            if (ungrouped.size > 1) {
                val groupId = UUID.randomUUID().toString()
                fileHashDao.assignGroup(groupId, ungrouped.map { it.documentId })
            }
        }
    }

    companion object {
        const val WORK_NAME = "duplicate_scan_work"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DuplicateScanWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DuplicateScanWorker>().build()
            )
        }
    }
}
