package com.propdf.storage.domain.repository

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.result.AppResult
import com.propdf.storage.domain.model.BulkImportResult
import com.propdf.storage.domain.model.PersistedTreeUri
import com.propdf.storage.domain.model.SafDocument
import kotlinx.coroutines.flow.Flow

/**
 * Repository for Storage Access Framework operations.
 * Provides deep integration with system document providers.
 */
interface SafRepository {
    /**
     * Persist a tree URI permission for long-term access.
     * Call this after user selects folder via ACTION_OPEN_DOCUMENT_TREE.
     */
    suspend fun persistTreeUri(uri: Uri, displayName: String): AppResult<PersistedTreeUri>

    /**
     * Get all persisted tree URIs.
     */
    fun getPersistedTreeUris(): Flow<List<PersistedTreeUri>>

    /**
     * Remove a persisted tree URI and release permission.
     */
    suspend fun removeTreeUri(id: Long): AppResult<Unit>

    /**
     * List documents in a tree URI.
     */
    suspend fun listDocuments(treeUri: Uri, mimeTypeFilter: String? = "application/pdf"): AppResult<List<SafDocument>>

    /**
     * Import a document from SAF into app storage.
     */
    suspend fun importDocument(sourceUri: Uri, targetName: String? = null): AppResult<android.net.Uri>

    /**
     * Bulk import PDFs from a tree URI.
     */
    suspend fun bulkImportPdfs(treeUri: Uri): AppResult<BulkImportResult>

    /**
     * Check if URI permission is still valid.
     */
    suspend fun isUriPermissionValid(uri: Uri): Boolean

    /**
     * Get display name from URI.
     */
    suspend fun getDisplayName(uri: Uri): String?
}
