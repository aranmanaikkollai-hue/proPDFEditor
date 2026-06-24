package com.propdf.sync.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.sync.domain.repository.FolderWatchRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FolderWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderWatchRepository: FolderWatchRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            folderWatchRepository.syncWatchedFolders()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "folder_watch_worker"
    }
}
