package com.propdf.editor.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.propdf.core.domain.logger.AppLogger
import com.propdf.editor.data.local.dao.CollectionDao
import com.propdf.editor.data.local.entity.DocumentCollectionCrossRef
import com.propdf.editor.data.smartfolder.SmartFolderEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SmartFolderRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val collectionDao: CollectionDao,
    private val smartFolderEngine: SmartFolderEngine,
    private val logger: AppLogger
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val smartFolders = collectionDao.getSmartFolders().first()

            for (folder in smartFolders) {
                folder.smartRules ?: continue
                collectionDao.clearCollection(folder.id)

                val matchingDocs = smartFolderEngine.evaluateRules(folder.smartRules)
                for (doc in matchingDocs) {
                    collectionDao.addDocumentToCollection(
                        DocumentCollectionCrossRef(documentId = doc.id, collectionId = folder.id)
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            logger.e("SmartFolder", "Refresh failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "smart_folder_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmartFolderRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
