package com.propdf.editor.data.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class DocumentScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val contentResolver = applicationContext.contentResolver
            scanForPdfs(contentResolver)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private suspend fun scanForPdfs(contentResolver: ContentResolver) {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
        val selectionArgs = arrayOf("application/pdf")

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Unknown"
                val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
                val lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))

                val fileUri = Uri.withAppendedPath(uri, id.toString()).toString()

                val recentFile = RecentFile(
                    uri = fileUri,
                    name = displayName,
                    size = sizeBytes,
                    lastOpened = lastModified,
                    pageCount = 0,
                    isFavorite = false,
                    category = ""
                )
                // Do NOT overwrite an existing entry: the REPLACE insert would reset the user's favourite flag,
                // category and last-opened time every hour. Only PDFs the app has never seen are added.
                if (shouldInsert(documentRepository.getRecentFileByUri(fileUri) != null)) {
                    documentRepository.insertOrUpdateRecentFile(recentFile)
                }
            }
        }
    }

    companion object {
        const val WORK_NAME = "document_scan_worker"

        /**
         * The single rule the scan applies: insert a discovered PDF only if the app has no entry for it.
         * Pure function so it can be unit-tested without Android or a database.
         */
        internal fun shouldInsert(alreadyKnown: Boolean): Boolean = !alreadyKnown
    }
}
