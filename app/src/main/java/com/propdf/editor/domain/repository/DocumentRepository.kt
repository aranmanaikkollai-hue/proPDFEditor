package com.propdf.editor.domain.repository

// NOTE: this file previously contained the entire interface body duplicated -
// two "package com.propdf.editor.domain.repository" lines and two
// "interface DocumentRepository { ... }" declarations back to back in the same
// file. That is a hard Kotlin parse error ("expecting top level declaration" /
// redeclaration of DocumentRepository), guaranteed to fail on every build. The
// kapt KT-70718 bug swallowed that diagnostic during :app's
// kaptGenerateStubsDebugKotlin task and surfaced only the generic
// "Could not load module <Error module>" message, so nothing more specific
// showed up in the CI log.
//
// This rewritten version keeps only the methods actually called by this
// interface's 3 real consumers (DocumentUseCases.kt, DocumentManagerViewModel.kt,
// DocumentScanWorker.kt) rather than the larger, partly-unused method list that
// was split across the two duplicated blocks.

import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SortOption
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    suspend fun getAllDocuments(): List<PdfDocument>
    fun getRecentFiles(): Flow<List<PdfDocument>>
    fun getFavorites(): Flow<List<PdfDocument>>
    fun getDeletedFiles(): Flow<List<PdfDocument>>
    fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>>

    suspend fun setFavorite(id: Long, favorite: Boolean)
    suspend fun deleteDocument(id: Long)
    suspend fun restoreDocument(id: Long)
    suspend fun permanentDelete(id: Long)
    suspend fun emptyRecycleBin(olderThanDays: Int = 30)

    suspend fun insertOrUpdateRecentFile(recentFile: RecentFile)
}
