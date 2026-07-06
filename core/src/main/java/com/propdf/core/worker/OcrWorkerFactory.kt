package com.propdf.core.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.propdf.core.data.local.OcrJobDao
import com.propdf.core.domain.repository.OcrRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrWorkerFactory @Inject constructor(
    private val ocrRepository: OcrRepository,
    private val ocrJobDao: OcrJobDao
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        return when (workerClassName) {
            OcrWorker::class.java.name -> OcrWorker(appContext, workerParameters, ocrRepository, ocrJobDao)
            else -> null
        }
    }
}
