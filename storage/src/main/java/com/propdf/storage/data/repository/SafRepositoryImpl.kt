package com.propdf.storage.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.storage.data.local.PersistedUriDao
import com.propdf.storage.data.local.entity.PersistedUriEntity
import com.propdf.storage.domain.model.BulkImportResult
import com.propdf.storage.domain.model.PersistedTreeUri
import com.propdf.storage.domain.model.SafDocument
import com.propdf.storage.domain.repository.SafRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafRepositoryImpl @Inject constructor(
    private val context: Context,
    private val persistedUriDao: PersistedUriDao,
    private val dispatcherProvider: DispatcherProvider
) : SafRepository {

    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun persistTreeUri(uri: Uri, displayName: String): AppResult<PersistedTreeUri> =
        withContext(dispatcherProvider.io) {
            try {
                // Take persistable permission
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                val authority = uri.authority
                val isRemovable = authority?.contains("externalstorage") == true ||
                        authority?.contains(" removable") == true

                val entity = PersistedUriEntity(
                    uriString = uri.toString(),
                    displayName = displayName,
                    providerAuthority = authority,
                    isRemovableStorage = isRemovable
                )

                val id = persistedUriDao.insert(entity)
                val result = PersistedTreeUri(
                    id = id,
                    uri = uri,
                    displayName = displayName,
                    providerAuthority = authority,
                    isRemovableStorage = isRemovable
                )

                AppResult.Success(result)
            } catch (e: SecurityException) {
                AppResult.Error(AppException.SecurityError("Failed to persist URI permission: ${e.message}"))
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Failed to persist tree URI: ${e.message}"))
            }
        }

    override fun getPersistedTreeUris(): Flow<List<PersistedTreeUri>> {
        return persistedUriDao.getAll().map { entities ->
            entities.map { entity ->
                PersistedTreeUri(
                    id = entity.id,
                    uri = Uri.parse(entity.uriString),
                    displayName = entity.displayName,
                    providerAuthority = entity.providerAuthority,
                    isRemovableStorage = entity.isRemovableStorage,
                    addedAt = entity.addedAt
                )
            }
        }
    }

    override suspend fun removeTreeUri(id: Long): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val entity = persistedUriDao.getById(id)
                entity?.let {
                    val uri = Uri.parse(it.uriString)
                    try {
                        contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        // Permission may already be released
                    }
                    persistedUriDao.deleteById(id)
                }
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Failed to remove tree URI: ${e.message}"))
            }
        }

    override suspend fun listDocuments(
        treeUri: Uri,
        mimeTypeFilter: String?
    ): AppResult<List<SafDocument>> = withContext(dispatcherProvider.io) {
        try {
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext AppResult.Error(AppException.IOError("Invalid tree URI"))

            val documents = mutableListOf<SafDocument>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    val size = cursor.getLong(3)
                    val lastModified = cursor.getLong(4)

                    if (mimeTypeFilter == null || mimeType == mimeTypeFilter || mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        documents.add(
                            SafDocument(
                                uri = docUri,
                                name = name,
                                mimeType = mimeType,
                                size = size,
                                lastModified = lastModified,
                                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                            )
                        )
                    }
                }
            }

            AppResult.Success(documents)
        } catch (e: SecurityException) {
            AppResult.Error(AppException.SecurityError("Permission denied for tree URI"))
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to list documents: ${e.message}"))
        }
    }

    override suspend fun importDocument(
        sourceUri: Uri,
        targetName: String?
    ): AppResult<Uri> = withContext(dispatcherProvider.io) {
        try {
            val name = targetName ?: getDisplayName(sourceUri)
                ?: "imported_${System.currentTimeMillis()}.pdf"

            val internalDir = File(context.filesDir, "imports")
            if (!internalDir.exists()) internalDir.mkdirs()

            val targetFile = File(internalDir, name)

            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext AppResult.Error(AppException.IOError("Cannot open source document"))

            AppResult.Success(Uri.fromFile(targetFile))
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Import failed: ${e.message}"))
        }
    }

    override suspend fun bulkImportPdfs(treeUri: Uri): AppResult<BulkImportResult> =
        withContext(dispatcherProvider.io) {
            try {
                when (val docsResult = listDocuments(treeUri, "application/pdf")) {
                    is AppResult.Success -> {
                        val pdfs = docsResult.data.filter { !it.isDirectory }
                        var imported = 0
                        var skipped = 0
                        val failed = mutableListOf<Uri>()

                        pdfs.forEach { doc ->
                            when (importDocument(doc.uri, doc.name)) {
                                is AppResult.Success -> imported++
                                else -> {
                                    failed.add(doc.uri)
                                    skipped++
                                }
                            }
                        }

                        AppResult.Success(
                            BulkImportResult(
                                importedCount = imported,
                                skippedCount = skipped,
                                failedUris = failed
                            )
                        )
                    }
                    is AppResult.Error -> docsResult
                    else -> AppResult.Error(AppException.Unknown("Unexpected result"))
                }
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Bulk import failed: ${e.message}"))
            }
        }

    override suspend fun isUriPermissionValid(uri: Uri): Boolean {
        return try {
            contentResolver.persistedUriPermissions.any { it.uri == uri }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
