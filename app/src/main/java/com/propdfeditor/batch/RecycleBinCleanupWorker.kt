package com.propdfeditor.batch

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.data.database.dao.RecycleBinDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecycleBinCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recycleBinDao: RecycleBinDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val now = System.currentTimeMillis()
            recycleBinDao.deleteExpired(now)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
