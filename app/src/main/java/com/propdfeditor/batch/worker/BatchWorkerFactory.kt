package com.propdfeditor.batch.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.util.BatchNotificationManager
import com.propdfeditor.batch.util.PdfProcessor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatchWorkerFactory @Inject constructor(
    private val repository: BatchJobRepository,
    private val notificationManager: BatchNotificationManager,
    private val pdfProcessor: PdfProcessor
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            MergeWorker::class.java.name -> MergeWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            SplitWorker::class.java.name -> SplitWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            RenameWorker::class.java.name -> RenameWorker(appContext, workerParameters, repository, notificationManager)
            WatermarkWorker::class.java.name -> WatermarkWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            RotateWorker::class.java.name -> RotateWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            CompressWorker::class.java.name -> CompressWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            OcrWorker::class.java.name -> OcrWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            EncryptWorker::class.java.name -> EncryptWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            DecryptWorker::class.java.name -> DecryptWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            ExportWorker::class.java.name -> ExportWorker(appContext, workerParameters, repository, notificationManager, pdfProcessor)
            DeleteWorker::class.java.name -> DeleteWorker(appContext, workerParameters, repository, notificationManager)
            else -> null
        }
    }
}
