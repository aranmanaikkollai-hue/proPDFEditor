import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.propdfeditor.core.worker.SignatureCleanupWorker
import java.util.concurrent.TimeUnit

// Inside onCreate():
private fun setupPeriodicCleanup() {
    val cleanupRequest = PeriodicWorkRequestBuilder<SignatureCleanupWorker>(
        7, TimeUnit.DAYS
    ).build()
    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        SignatureCleanupWorker.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        cleanupRequest
    )
}
