package com.propdf.editor.data.worker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.core.data.entity.PdfDocumentEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class DocumentScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfDocumentDao: PdfDocumentDao
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "document_scan_worker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = applicationContext
            val existingUris = pdfDocumentDao.getAllDocuments("NAME_ASC", includeHidden = true)
                .first()
                .map { it.uriString }
                .toMutableSet()

            // Scan MediaStore for PDFs
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
                "%.pdf"
            )

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val path = cursor.getString(pathCol)
                    val size = cursor.getLong(sizeCol)
                    val modified = cursor.getLong(modifiedCol) * 1000

                    val uri = Uri.withAppendedPath(
                        MediaStore.Files.getContentUri("external"),
                        id.toString()
                    ).toString()

                    if (uri !in existingUris && File(path).exists()) {
                        pdfDocumentDao.insert(
                            PdfDocumentEntity(
                                uriString = uri,
                                fileName = name,
                                filePath = path,
                                sizeBytes = size,
                                lastModified = modified
                            )
                        )
                        existingUris.add(uri)
                    }
                }
            }

            // Clean up non-existent files
            val allDocs = pdfDocumentDao.getAllDocuments("NAME_ASC", includeHidden = true).first()
            allDocs.forEach { doc ->
                if (!File(doc.filePath).exists() && !doc.isInRecycleBin) {
                    pdfDocumentDao.moveToRecycleBin(doc.id, System.currentTimeMillis())
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
