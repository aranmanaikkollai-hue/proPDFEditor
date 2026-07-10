package com.propdf.editor.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.propdf.core.data.entity.PdfDocumentEntity
import com.propdf.core.data.local.dao.*
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.DocumentRepository
import com.propdf.core.domain.result.safeCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfDocumentDao: PdfDocumentDao,
    private val tagDao: DocumentTagDao,
    private val collectionDao: DocumentCollectionDao,
    private val activityDao: RecentActivityDao
) : DocumentRepository {

    private val contentResolver: ContentResolver = context.contentResolver

    override fun getAllDocuments(filter: DocumentFilter): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getAllDocuments(
            sort = filter.sortOption.name,
            includeHidden = filter.includeHidden
        ).map { entities ->
            entities.map { it.toDomain() }
                .filter { doc ->
                    filter.fileTypeFilter == FileTypeFilter.ALL || when (filter.fileTypeFilter) {
                        FileTypeFilter.PDF -> doc.isPdf
                        FileTypeFilter.LARGE_FILES -> doc.isLargeFile
                        else -> true
                    }
                }
                .filter { doc ->
                    filter.tags.isEmpty() || true // Tags filtered at query level if needed
                }
        }
    }

    override fun getRecentDocuments(limit: Int): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getRecentDocuments(limit, includeHidden = false)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getFavoriteDocuments(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getFavoriteDocuments(includeHidden = false)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getRecycleBinDocuments(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getRecycleBinDocuments()
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getLargeFiles(threshold: Long): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getLargeFiles(threshold, includeHidden = false)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun searchDocuments(query: String): Flow<List<PdfDocument>> {
        return pdfDocumentDao.searchDocuments(query, includeHidden = false)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getDocumentsInFolder(folderPath: String): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getDocumentsInFolder(folderPath, includeHidden = false)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getDocumentsByCollection(collectionId: Long): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getDocumentsByCollection(collectionId)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getDocumentsByTag(tagId: Long): Flow<List<PdfDocument>> {
        return tagDao.getDocumentsByTag(tagId)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getDocumentById(id: Long): PdfDocument? {
        return pdfDocumentDao.getById(id)?.toDomain()
    }

    override suspend fun insertDocument(document: PdfDocument): Long {
        val entity = document.toEntity()
        return pdfDocumentDao.insert(entity)
    }

    override suspend fun updateDocument(document: PdfDocument) {
        pdfDocumentDao.update(document.toEntity())
    }

    override suspend fun deleteDocument(id: Long) {
        pdfDocumentDao.deletePermanently(id)
    }

    override suspend fun moveToRecycleBin(id: Long) {
        pdfDocumentDao.moveToRecycleBin(id, System.currentTimeMillis())
        logActivity(id, ActivityAction.DELETED)
    }

    override suspend fun restoreFromRecycleBin(id: Long) {
        pdfDocumentDao.restoreFromRecycleBin(id)
        logActivity(id, ActivityAction.RESTORED)
    }

    override suspend fun permanentlyDelete(id: Long) {
        val doc = pdfDocumentDao.getById(id) ?: return
        try {
            val file = File(doc.filePath)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            // Log but continue
        }
        pdfDocumentDao.deletePermanently(id)
    }

    override suspend fun setFavorite(id: Long, favorite: Boolean) {
        pdfDocumentDao.setFavorite(id, favorite)
        logActivity(id, if (favorite) ActivityAction.FAVORITED else ActivityAction.UNFAVORITED)
    }

    override suspend fun setHidden(id: Long, hidden: Boolean) {
        pdfDocumentDao.setHidden(id, hidden)
    }

    override suspend fun renameDocument(id: Long, newName: String) {
        val doc = pdfDocumentDao.getById(id) ?: return
        val oldFile = File(doc.filePath)
        val newFile = File(oldFile.parentFile, newName)
        
        withContext(Dispatchers.IO) {
            if (oldFile.renameTo(newFile)) {
                pdfDocumentDao.updatePathAndName(id, newFile.parent ?: "", newName)
                logActivity(id, ActivityAction.RENAMED, "Renamed to $newName")
            }
        }
    }

    override suspend fun moveDocument(id: Long, newPath: String) {
        val doc = pdfDocumentDao.getById(id) ?: return
        val oldFile = File(doc.filePath)
        val newFile = File(newPath, doc.fileName)
        
        withContext(Dispatchers.IO) {
            oldFile.parentFile?.mkdirs()
            if (oldFile.renameTo(newFile)) {
                pdfDocumentDao.updatePathAndName(id, newPath, doc.fileName)
                logActivity(id, ActivityAction.MOVED, "Moved to $newPath")
            }
        }
    }

    override suspend fun copyDocument(id: Long, destinationPath: String): Long {
        val doc = pdfDocumentDao.getById(id) ?: throw IllegalArgumentException("Document not found")
        val sourceFile = File(doc.filePath)
        val destFile = File(destinationPath, doc.fileName)
        
        return withContext(Dispatchers.IO) {
            sourceFile.copyTo(destFile, overwrite = true)
            val newEntity = doc.copy(
                id = 0,
                filePath = destFile.absolutePath,
                uriString = destFile.toUri().toString()
            )
            val newId = pdfDocumentDao.insert(newEntity)
            logActivity(newId, ActivityAction.COPIED, "Copied to $destinationPath")
            newId
        }
    }

    override suspend fun updateLastOpened(id: Long) {
        pdfDocumentDao.updateLastOpened(id, System.currentTimeMillis())
        logActivity(id, ActivityAction.OPENED)
    }

    override suspend fun addToCollection(documentId: Long, collectionId: Long?) {
        pdfDocumentDao.updateCollection(documentId, collectionId)
    }

    override fun getDocumentCount(): Flow<Int> = pdfDocumentDao.getDocumentCount()

    override fun getTotalSize(): Flow<Long?> = pdfDocumentDao.getTotalSize()

    override suspend fun findDuplicates(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val potentialDups = pdfDocumentDao.findPotentialDuplicates()
        val checksumMap = potentialDups.groupBy { it.checksum ?: "" }
            .filter { it.value.size > 1 && it.key.isNotBlank() }
        
        checksumMap.map { (checksum, entities) ->
            val docs = entities.map { it.toDomain() }
            val wasted = docs.drop(1).sumOf { it.sizeBytes }
            DuplicateGroup(checksum, docs, wasted)
        }.sortedByDescending { it.wastedBytes }
    }

    override suspend fun getStorageAnalysis(): StorageAnalysis = withContext(Dispatchers.IO) {
        val allDocs = pdfDocumentDao.getAllDocuments(SortOption.SIZE_DESC.name, includeHidden = false)
            .first()
            .map { it.toDomain() }
        
        val totalStorage = File("/storage/emulated/0").totalSpace
        val usedStorage = totalStorage - File("/storage/emulated/0").freeSpace
        val pdfBytes = allDocs.sumOf { it.sizeBytes }
        val duplicates = findDuplicates()
        val dupBytes = duplicates.sumOf { it.wastedBytes }
        
        val folderMap = allDocs.groupBy { 
            File(it.filePath).parentFile?.name ?: "Root" 
        }
        val breakdown = folderMap.mapValues { it.value.sumOf { doc -> doc.sizeBytes } }
        
        StorageAnalysis(
            totalStorageBytes = totalStorage,
            usedStorageBytes = usedStorage,
            pdfFilesBytes = pdfBytes,
            pdfFileCount = allDocs.size,
            largestFiles = allDocs.take(10),
            duplicateFilesBytes = dupBytes,
            duplicateGroupCount = duplicates.size,
            oldestFiles = allDocs.sortedBy { it.lastModified }.take(10),
            collectionBreakdown = breakdown
        )
    }

    override suspend fun emptyRecycleBin() {
        val recycleBinDocs = pdfDocumentDao.getRecycleBinDocuments().first()
        recycleBinDocs.forEach {
            permanentlyDelete(it.id)
        }
    }

    override suspend fun cleanOldRecycleBinItems(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000)
        val oldItems = pdfDocumentDao.getRecycleBinDocuments().first()
            .filter { (it.deletedAt ?: 0) < cutoff }
        oldItems.forEach {
            permanentlyDelete(it.id)
        }
    }

    // Batch Operations
    override suspend fun batchDelete(ids: List<Long>) {
        ids.forEach { permanentlyDelete(it) }
    }

    override suspend fun batchMoveToRecycleBin(ids: List<Long>) {
        ids.forEach { moveToRecycleBin(it) }
    }

    override suspend fun batchRestore(ids: List<Long>) {
        ids.forEach { restoreFromRecycleBin(it) }
    }

    override suspend fun batchFavorite(ids: List<Long>, favorite: Boolean) {
        ids.forEach { setFavorite(it, favorite) }
    }

    override suspend fun batchMove(ids: List<Long>, destinationPath: String) {
        ids.forEach { moveDocument(it, destinationPath) }
    }

    override suspend fun batchCopy(ids: List<Long>, destinationPath: String) {
        ids.forEach { copyDocument(it, destinationPath) }
    }

    override suspend fun batchHide(ids: List<Long>, hidden: Boolean) {
        ids.forEach { setHidden(it, hidden) }
    }

    override suspend fun batchAddToCollection(ids: List<Long>, collectionId: Long?) {
        ids.forEach { addToCollection(it, collectionId) }
    }

    override suspend fun batchAddTags(documentIds: List<Long>, tagIds: List<Long>) {
        documentIds.forEach { docId ->
            tagIds.forEach { tagId ->
                tagDao.addTagToDocument(com.propdf.core.data.entity.DocumentTagCrossRef(docId, tagId))
            }
        }
    }

    private suspend fun logActivity(documentId: Long, action: ActivityAction, details: String? = null) {
        val doc = pdfDocumentDao.getById(documentId) ?: return
        activityDao.insert(
            com.propdf.core.data.entity.RecentActivityEntity(
                documentId = documentId,
                documentName = doc.fileName,
                action = action.name,
                details = details
            )
        )
    }

    private fun PdfDocumentEntity.toDomain(): PdfDocument {
        return PdfDocument(
            id = id,
            uriString = uriString,
            fileName = fileName,
            filePath = filePath,
            sizeBytes = sizeBytes,
            pageCount = pageCount,
            lastModified = lastModified,
            lastOpened = lastOpened,
            isFavorite = isFavorite,
            isHidden = isHidden,
            isInRecycleBin = isInRecycleBin,
            deletedAt = deletedAt,
            thumbnailUri = thumbnailUri,
            collectionId = collectionId,
            checksum = checksum,
            metadataTitle = metadataTitle,
            metadataAuthor = metadataAuthor,
            metadataSubject = metadataSubject,
            metadataKeywords = metadataKeywords,
            metadataCreationDate = metadataCreationDate
        )
    }

    private fun PdfDocument.toEntity(): PdfDocumentEntity {
        return PdfDocumentEntity(
            id = id,
            uriString = uriString,
            fileName = fileName,
            filePath = filePath,
            sizeBytes = sizeBytes,
            pageCount = pageCount,
            lastModified = lastModified,
            lastOpened = lastOpened,
            isFavorite = isFavorite,
            isHidden = isHidden,
            isInRecycleBin = isInRecycleBin,
            deletedAt = deletedAt,
            thumbnailUri = thumbnailUri,
            collectionId = collectionId,
            checksum = checksum,
            metadataTitle = metadataTitle,
            metadataAuthor = metadataAuthor,
            metadataSubject = metadataSubject,
            metadataKeywords = metadataKeywords,
            metadataCreationDate = metadataCreationDate
        )
    }
}
