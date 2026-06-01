package com.propdf.editor.data.index

import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.propdf.editor.data.local.db.AppDatabase
import java.io.File

/**
 * Local-only WorkManager pipeline for background PDF indexing.
 *
 * Runs without network and is constrained to avoid indexing during low-battery
 * or low-storage conditions. The worker opens the app Room database directly so
 * it remains usable even without adding Hilt worker plumbing.
 */
class DocumentIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        val path = inputData.getString(KEY_FILE_PATH)
        val file = path?.let(::File)

        if (documentId <= 0L || file == null || !file.exists() || !file.canRead()) {
            return Result.failure()
        }

        val database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME
        ).build()

        return try {
            val engine = DocumentIndexEngine(applicationContext, database.searchIndexDao())
            if (engine.indexDocument(documentId, file)) Result.success() else Result.retry()
        } finally {
            database.close()
        }
    }

    companion object {
        private const val KEY_DOCUMENT_ID = "document_id"
        private const val KEY_FILE_PATH = "file_path"
        private const val DATABASE_NAME = "propdf_database"

        fun enqueue(context: Context, documentId: Long, pdfFile: File) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val input = Data.Builder()
                .putLong(KEY_DOCUMENT_ID, documentId)
                .putString(KEY_FILE_PATH, pdfFile.absolutePath)
                .build()

            val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "index_document_$documentId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
