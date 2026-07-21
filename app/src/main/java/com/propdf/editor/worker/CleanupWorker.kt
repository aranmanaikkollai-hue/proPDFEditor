package com.propdf.editor.worker

import android.content.Context
import androidx.work.*
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.pool.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that cleans up stale cache files and trims memory pools.
 * Runs daily when app is in background, respecting battery constraints.
 */
class CleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "pdf_cleanup"
        const val MAX_CACHE_AGE_DAYS = 7L
        const val MAX_CACHE_SIZE_MB = 512L

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<CleanupWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS // flex interval
            )
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            var cleanedBytes = 0L

            // 1. Clean old bitmap cache files
            val cacheDir = File(applicationContext.cacheDir, "pdf_bitmap_cache")
            if (cacheDir.exists()) {
                val cutoff = System.currentTimeMillis() - (MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000)
                cacheDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) {
                        cleanedBytes += file.length()
                        file.delete()
                    }
                }
            }

            // 2. Clean temp files older than 1 day
            val tempDir = applicationContext.cacheDir
            val tempCutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
            tempDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("pdf_") && file.lastModified() < tempCutoff) {
                    cleanedBytes += file.length()
                    file.delete()
                }
            }

            // 3. Trim memory pools
            BitmapPool.getDefaultInstance().trimToSize(32L * 1024 * 1024)
            LruBitmapCache.getInstance(applicationContext).trimMemory(
                android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
            )

            // 4. System GC hint
            System.gc()

            Result.success(workDataOf(
                "cleaned_bytes" to cleanedBytes,
                "timestamp" to System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
