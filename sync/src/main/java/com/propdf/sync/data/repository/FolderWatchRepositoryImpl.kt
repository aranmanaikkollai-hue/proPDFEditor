package com.propdf.sync.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.storage.domain.repository.SafRepository
import com.propdf.sync.data.local.FolderStateDao
import com.propdf.sync.data.local.SyncDatabase
import com.propdf.sync.data.local.WatchedFolderDao
import com.propdf.sync.data.local.entity.FolderStateEntity
import com.propdf.sync.data.local.entity.WatchedFolderEntity
import com.propdf.sync.domain.model.WatchedFolder
import com.propdf.sync.domain.repository.FolderWatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FolderWatchRepositoryImpl(
    private val context: Context,
    private val safRepository: SafRepository,
    private val watchedFolderDao: WatchedFolderDao,
    private val folderStateDao: FolderStateDao,
    private val dispatcherProvider: DispatcherProvider
) : FolderWatchRepository {

    private val contentResolver = context.contentResolver

    override suspend fun addWatchedFolder(
        treeUri: Uri,
        displayName: String
    ): AppResult<WatchedFolder> = withContext(dispatcherProvider.io) {
        try {
            // Verify we have permission
            if (!safRepository.isUriPermissionValid(treeUri)) {
                return@withContext AppResult.Error(
                    AppException.SecurityError("No persistent permission for this folder")
                )
            }

            val entity = WatchedFolderEntity(
                treeUriString = treeUri.toString(),
                displayName = displayName,
                isActive = true,
                autoImport = true,
                lastCheckedAt = 0,
                importedCount = 0
            )

            val id = watchedFolderDao.insert(entity)

            AppResult.Success(
                WatchedFolder(
                    id = id,
                    treeUri = treeUri,
                    displayName = displayName,
                    isActive = true,
                    autoImport = true,
                    lastCheckedAt = 0,
                    importedCount = 0
                )
            )
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to add watched folder: ${e.message}"))
        }
    }

    override suspend fun removeWatchedFolder(id: Long): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                watchedFolderDao.deleteById(id)
                folderStateDao.deleteByFolderId(id)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Failed to remove watched folder: ${e.message}"))
            }
        }

    override suspend fun updateFolderState(folder: WatchedFolder): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val entity = WatchedFolderEntity(
                    id = folder.id,
                    treeUriString = folder.treeUri.toString(),
                    displayName = folder.displayName,
                    isActive = folder.isActive,
                    autoImport = folder.autoImport,
                    lastCheckedAt = folder.lastCheckedAt,
                    importedCount = folder.importedCount
                )
                watchedFolderDao.update(entity)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Failed to update folder: ${e.message}"))
            }
        }

    override fun getWatchedFolders(): Flow<List<WatchedFolder>> {
        return watchedFolderDao.getAll().map { entities ->
            entities.map { entity ->
                WatchedFolder(
                    id = entity.id,
                    treeUri = Uri.parse(entity.treeUriString),
                    displayName = entity.displayName,
                    isActive = entity.isActive,
                    autoImport = entity.autoImport,
                    lastCheckedAt = entity.lastCheckedAt,
                    importedCount = entity.importedCount
                )
            }
        }
    }

    override suspend fun performScan(folderId: Long): AppResult<Int> =
        withContext(dispatcherProvider.io) {
            try {
                val folder = watchedFolderDao.getById(folderId)
                    ?: return@withContext AppResult.Error(AppException.FileNotFound("Watched folder not found"))

                if (!folder.isActive) {
                    return@withContext AppResult.Success(0)
                }

                val treeUri = Uri.parse(folder.treeUriString)
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext AppResult.Error(AppException.IOError("Invalid tree URI"))

                // Get previously known documents
                val knownUris = folderStateDao.getDocumentUris(folderId).toSet()

                // Scan current documents
                val currentDocs = mutableListOf<Pair<Uri, String>>()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )

                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )

                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1)
                        val mimeType = cursor.getString(2)
                        val lastModified = cursor.getLong(3)

                        if (mimeType == "application/pdf") {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            currentDocs.add(docUri to name)

                            // Import if new
                            if (!knownUris.contains(docUri.toString()) && folder.autoImport) {
                                safRepository.importDocument(docUri, name)
                            }
                        }
                    }
                }

                // Update folder state
                val newImports = currentDocs.count { !knownUris.contains(it.first.toString()) }
                val stateEntities = currentDocs.map { (uri, name) ->
                    FolderStateEntity(
                        folderId = folderId,
                        documentUri = uri.toString(),
                        documentName = name,
                        lastModified = System.currentTimeMillis(),
                        checkedAt = System.currentTimeMillis()
                    )
                }

                folderStateDao.deleteByFolderId(folderId)
                folderStateDao.insertAll(stateEntities)
                watchedFolderDao.updateScanResult(folderId, System.currentTimeMillis(), newImports)

                AppResult.Success(newImports)
            } catch (e: SecurityException) {
                AppResult.Error(AppException.SecurityError("Permission lost for watched folder"))
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("Scan failed: ${e.message}"))
            }
        }

    override fun isFolderWatched(uri: Uri): Boolean {
        // This would need a synchronous check or cached state
        // For now, return false - implement with LiveData/StateFlow in ViewModel
        return false
    }
}
