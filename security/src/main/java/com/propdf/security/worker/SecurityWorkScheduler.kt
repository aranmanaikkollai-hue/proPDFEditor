// security/src/main/java/com/propdf/security/worker/SecurityWorkScheduler.kt
package com.propdf.security.worker

import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.propdf.security.data.entity.SecurityOperationType
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules [SecurityWorker] runs via WorkManager.
 *
 * This lives in the `worker` package (not `data.repository`) specifically to
 * avoid a circular source reference between SecurityRepository and
 * SecurityWorker: SecurityWorker is @Inject-constructed with a
 * SecurityRepository, so SecurityRepository must not, in turn, import
 * SecurityWorker to enqueue it. That two-way reference between the exact
 * classes at the center of Hilt's dependency graph was the root cause of a
 * persistent `error.NonExistentClass` failure in :security's kspDebugKotlin
 * (Dagger/Hilt's KSP-based InjectProcessingStep/AssistedInjectProcessingStep
 * could not resolve the cycle).
 */
@Singleton
class SecurityWorkScheduler @Inject constructor(
    private val workManager: WorkManager
) {
    fun scheduleSecurityOperation(
        operationType: SecurityOperationType,
        sourceUri: Uri,
        params: Data
    ): Operation {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<SecurityWorker>()
            .setInputData(
                workDataOf(
                    "operation_type" to operationType.name,
                    "source_uri" to sourceUri.toString()
                )
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        return workManager.enqueueUniqueWork(
            "security_${operationType.name}_${System.currentTimeMillis()}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
