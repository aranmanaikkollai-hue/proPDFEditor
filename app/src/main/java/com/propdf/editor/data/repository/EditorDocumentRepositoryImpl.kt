package com.propdf.editor.data.repository

import android.net.Uri
import com.propdf.core.domain.model.DocumentFilter
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.editor.domain.model.DocumentCategory
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SortOption
import com.propdf.editor.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs the small app-level [DocumentRepository] interface (used by
 * DocumentUseCases, DocumentManagerViewModel and DocumentScanWorker) with the
 * already-implemented, already-Hilt-bound :core repositories, translating
 * between com.propdf.core.domain.model.PdfDocument and
 * com.propdf.editor.domain.model.PdfDocument.
 *
 * Named distinctly from data.repository.DocumentRepositoryImpl (which
 * implements the unrelated, larger com.propdf.core.domain.repository.DocumentRepository
 * interface directly) to avoid a duplicate class name in this package.
 */
@Singleton
class EditorDocumentRepositoryImpl @Inject constructor(
    private val coreDocumentRepository: com.propdf.core.domain.repository.DocumentRepository,
    private val recentFilesRepository: RecentFilesRepository
) : DocumentRepository {

    override suspend fun getAllDocuments(): List<PdfDocument> =
        coreDocumentRepository.getAllDocuments(DocumentFilter()).first().map { it.toEditorModel() }

    override fun getRecentFiles(): Flow<List<PdfDocument>> =
        coreDocumentRepository.getRecentDocuments().map { list -> list.map { it.toEditorModel() } }

    override fun getFavorites(): Flow<List<PdfDocument>> =
        coreDocumentRepository.getFavoriteDocuments().map { list -> list.map { it.toEditorModel() } }

    override fun getDeletedFiles(): Flow<List<PdfDocument>> =
        coreDocumentRepository.getRecycleBinDocuments().map { list -> list.map { it.toEditorModel() } }

    override fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>> =
        coreDocumentRepository.searchDocuments(query).map { list ->
            list.map { it.toEditorModel() }.sortedWith(sortComparator(sortBy))
        }

    override suspend fun setFavorite(id: Long, favorite: Boolean) =
        coreDocumentRepository.setFavorite(id, favorite)

    override suspend fun deleteDocument(id: Long) =
        coreDocumentRepository.moveToRecycleBin(id)

    override suspend fun restoreDocument(id: Long) =
        coreDocumentRepository.restoreFromRecycleBin(id)

    override suspend fun permanentDelete(id: Long) =
        coreDocumentRepository.permanentlyDelete(id)

    override suspend fun emptyRecycleBin(olderThanDays: Int) =
        coreDocumentRepository.cleanOldRecycleBinItems(olderThanDays)

    override suspend fun insertOrUpdateRecentFile(recentFile: RecentFile) {
        recentFilesRepository.add(recentFile)
    }

    private fun sortComparator(sortBy: SortOption): Comparator<PdfDocument> = when (sortBy) {
        SortOption.NAME -> compareBy { it.displayName }
        SortOption.LAST_OPENED -> compareByDescending { it.dateAdded }
        SortOption.LAST_MODIFIED -> compareByDescending { it.dateModified }
        SortOption.SIZE -> compareByDescending { it.fileSize }
    }

    private fun com.propdf.core.domain.model.PdfDocument.toEditorModel(): PdfDocument = PdfDocument(
        id = id,
        uri = Uri.parse(uriString),
        displayName = displayName,
        fileSize = sizeBytes,
        dateModified = lastModified,
        dateAdded = lastOpened ?: lastModified,
        isFavorite = isFavorite,
        isDeleted = isInRecycleBin,
        category = DocumentCategory.UNCATEGORIZED,
        cloudProvider = null,
        pageCount = pageCount
    )
}
