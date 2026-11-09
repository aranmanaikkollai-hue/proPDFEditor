package com.propdf.editor.data.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin Android wrapper: MediaStore enumeration -> [DocumentScanRunner] (all decision logic) -> WorkManager result.
 * NOTE: this worker is still NOT scheduled anywhere active (owner decision: legacy WorkManager initializer unchanged).
 */
@HiltWorker
class DocumentScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outcome =
            DocumentScanRunner(MediaStorePdfSource(applicationContext.contentResolver), documentRepository).run()
        val decision = DocumentScanRunner.decide(outcome, runAttemptCount)
        // Log counts and the error CLASS only: messages can contain document URIs / private paths.
        Log.i(TAG, "scan done: discovered=${outcome.discovered} inserted=${outcome.inserted} " +
            "known=${outcome.alreadyKnown} lookupFailures=${outcome.lookupFailures} " +
            "insertFailures=${outcome.insertFailures} sourceFailed=${outcome.sourceFailed} " +
            "decision=$decision firstFailure=${outcome.firstFailure?.javaClass?.simpleName}")
        when (decision) {
            ScanDecision.SUCCESS -> Result.success()
            ScanDecision.RETRY -> Result.retry()
            ScanDecision.FAILURE -> Result.failure(
                workDataOf("error_class" to (outcome.firstFailure?.javaClass?.simpleName ?: "Unknown"))
            )
        }
    }

    companion object {
        const val WORK_NAME = "document_scan_worker"
        private const val TAG = "DocumentScanWorker"
    }
}

/** Production [PdfDiscoverySource]: every PDF known to MediaStore. */
internal class MediaStorePdfSource(private val contentResolver: ContentResolver) : PdfDiscoverySource {
    override suspend fun forEachPdf(action: suspend (RecentFile) -> Unit) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val cursor = contentResolver.query(uri, projection, selection, arrayOf("application/pdf"), null)
            ?: throw java.io.IOException("MediaStore query returned no cursor")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                action(
                    RecentFile(
                        uri = Uri.withAppendedPath(uri, id.toString()).toString(),
                        name = it.getString(nameCol) ?: "Unknown",
                        size = it.getLong(sizeCol),
                        lastOpened = it.getLong(modCol),
                        pageCount = 0,
                        isFavorite = false,
                        category = ""
                    )
                )
            }
        }
    }
}
