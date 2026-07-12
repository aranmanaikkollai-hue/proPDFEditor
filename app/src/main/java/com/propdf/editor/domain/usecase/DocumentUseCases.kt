package com.propdf.editor.domain.usecase

import com.propdf.editor.domain.model.*
import com.propdf.editor.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetRecentFilesUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    operator fun invoke(): Flow<List<PdfDocument>> = repository.getRecentFiles()
}

class GetFavoritesUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    operator fun invoke(): Flow<List<PdfDocument>> = repository.getFavorites()
}

class GetDeletedFilesUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    operator fun invoke(): Flow<List<PdfDocument>> = repository.getDeletedFiles()
}

class SearchDocumentsUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    operator fun invoke(query: String, sortBy: SortOption = SortOption.LAST_OPENED): Flow<List<PdfDocument>> {
        return repository.searchFiles(query, sortBy)
    }
}

class ToggleFavoriteUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend operator fun invoke(id: Long, currentState: Boolean) {
        repository.setFavorite(id, !currentState)
    }
}

class MoveToRecycleBinUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend operator fun invoke(id: Long) {
        repository.deleteDocument(id)
    }
}

class RestoreDocumentUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend operator fun invoke(id: Long) {
        repository.restoreDocument(id)
    }
}

class PermanentDeleteUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend operator fun invoke(id: Long) {
        repository.permanentDelete(id)
    }
}

class EmptyRecycleBinUseCase @Inject constructor(
    private val repository: DocumentRepository
) {
    suspend operator fun invoke(olderThanDays: Int = 30) {
        repository.emptyRecycleBin(olderThanDays)
    }
}
