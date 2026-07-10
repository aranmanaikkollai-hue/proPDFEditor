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
import kotlinx.coroutines.flow.first
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
            val appContext = applicationContext
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

            appContext.contentResolver.query(
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

                    if (uri !in existingUris && path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            val entity = PdfDocumentEntity(
                                uriString = uri,
                                fileName = name,
                                filePath = path,
                                fileSize = size,
                                dateModified = modified,
                                dateAdded = System.currentTimeMillis()
                            )
                            pdfDocumentDao.insert(entity)
                            existingUris.add(uri)
                        }
                    }
                }
            }

            // Scan DocumentsContract for cloud files
            scanDocumentProvider(appContext, existingUris)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun scanDocumentProvider(context: Context, existingUris: MutableSet<String>) {
        val docUri = DocumentsContract.buildRootsUri("com.android.externalstorage.documents")
        context.contentResolver.query(docUri, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rootId = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID))
                val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", rootId)
                scanTree(context, treeUri, existingUris)
            }
        }
    }

    private suspend fun scanTree(context: Context, treeUri: Uri, existingUris: MutableSet<String>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childTree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId)
                    scanTree(context, childTree, existingUris)
                } else if (name.endsWith(".pdf", ignoreCase = true)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                    if (uri !in existingUris) {
                        val entity = PdfDocumentEntity(
                            uriString = uri,
                            fileName = name,
                            filePath = null,
                            fileSize = cursor.getLong(sizeCol),
                            dateModified = cursor.getLong(modifiedCol),
                            dateAdded = System.currentTimeMillis()
                        )
                        pdfDocumentDao.insert(entity)
                        existingUris.add(uri)
                    }
                }
            }
        }
    }
}
