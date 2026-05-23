package com.propdf.editor.data.repository

import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import com.propdf.editor.data.storage.StorageManager
import com.propdf.editor.domain.model.*
import com.propdf.editor.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val pdfDocumentDao: PdfDocumentDao,
    private val storageManager: StorageManager
) : DocumentRepository {

    override fun getRecentFiles(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getRecentFiles().map { list -> list.map { it.toDomain() } }
    }

    override fun getFavorites(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getFavorites().map { list -> list.map { it.toDomain() } }
    }

    override fun getDeletedFiles(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getDeletedFiles().map { list -> list.map { it.toDomain() } }
    }

    override fun getFilesInFolder(folderId: Long): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getFilesInFolder(folderId).map { list -> list.map { it.toDomain() } }
    }

    override fun getFilesByCategory(category: DocumentCategory): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getFilesByCategory(category.name).map { list -> list.map { it.toDomain() } }
    }

    override fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>> {
        val sortString = when (sortBy) {
            SortOption.NAME_ASC, SortOption.NAME_DESC -> "name"
            SortOption.DATE_MODIFIED, SortOption.DATE_CREATED, SortOption.LAST_OPENED -> "date"
            SortOption.SIZE -> "size"
        }
        return pdfDocumentDao.searchFiles(query, sortString).map { list -> list.map { it.toDomain() } }
    }

    override fun getAllCategories(): Flow<List<String>> {
        return pdfDocumentDao.getAllCategories()
    }

    override fun getStorageStats(): Flow<StorageStats> {
        return pdfDocumentDao.getDocumentCount().map { count ->
            StorageStats(
                totalDocuments = count,
                totalSize = 0, // Would need additional query
                favoriteCount = 0,
                deletedCount = 0
            )
        }
    }

    override suspend fun addDocument(document: PdfDocument): Long {
        return pdfDocumentDao.insert(document.toEntity())
    }

    override suspend fun updateDocument(document: PdfDocument) {
        pdfDocumentDao.update(document.toEntity())
    }

    override suspend fun deleteDocument(id: Long) {
        pdfDocumentDao.moveToRecycleBin(id)
    }

    override suspend fun restoreDocument(id: Long) {
        pdfDocumentDao.restoreFromRecycleBin(id)
    }

    override suspend fun permanentDelete(id: Long) {
        pdfDocumentDao.permanentDelete(id)
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) {
        pdfDocumentDao.setFavorite(id, isFavorite)
    }

    override suspend fun moveToFolder(id: Long, folderId: Long?) {
        pdfDocumentDao.moveToFolder(id, folderId)
    }

    override suspend fun moveToRecycleBin(id: Long) {
        pdfDocumentDao.moveToRecycleBin(id)
    }

    override suspend fun updateLastOpened(id: Long) {
        pdfDocumentDao.updateLastOpened(id)
    }

    override suspend fun setCategory(id: Long, category: DocumentCategory) {
        val doc = pdfDocumentDao.getByUri("") // Would need proper lookup
        // Implementation would update category field
    }

    override suspend fun setSyncStatus(id: Long, status: SyncStatus) {
        pdfDocumentDao.updateSyncStatus(id, status.name)
    }

    override suspend fun emptyRecycleBin(olderThan: Long?) {
        val cutoff = olderThan ?: System.currentTimeMillis()
        pdfDocumentDao.permanentDeleteOld(cutoff)
    }

    override suspend fun cleanupExpiredPermissions() {
        storageManager.cleanupExpiredPermissions()
    }

    // === Mappers ===

    private fun PdfDocumentEntity.toDomain(): PdfDocument {
        return PdfDocument(
            id = id,
            uri = android.net.Uri.parse(uri),
            fileName = fileName,
            displayName = displayName,
            fileSize = fileSize,
            pageCount = pageCount,
            thumbnailUri = thumbnailUri?.let { android.net.Uri.parse(it) },
            category = DocumentCategory.valueOf(category),
            folderId = folderId,
            isFavorite = isFavorite,
            isDeleted = isDeleted,
            deletedAt = deletedAt,
            createdAt = createdAt,
            lastOpened = lastOpened,
            lastModified = lastModified,
            cloudProvider = cloudProvider?.let { CloudProvider.valueOf(it) },
            cloudId = cloudId,
            syncStatus = SyncStatus.valueOf(syncStatus)
        )
    }

    private fun PdfDocument.toEntity(): PdfDocumentEntity {
        return PdfDocumentEntity(
            id = id,
            uri = uri.toString(),
            fileName = fileName,
            displayName = displayName,
            fileSize = fileSize,
            pageCount = pageCount,
            thumbnailUri = thumbnailUri?.toString(),
            category = category.name,
            folderId = folderId,
            isFavorite = isFavorite,
            isDeleted = isDeleted,
            deletedAt = deletedAt,
            createdAt = createdAt,
            lastOpened = lastOpened,
            lastModified = lastModified,
            searchText = "", // Would be populated by indexing
            cloudProvider = cloudProvider?.name,
            cloudId = cloudId,
            syncStatus = syncStatus.name
        )
    }
}
