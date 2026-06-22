package com.propdf.storage.domain.model

import android.net.Uri

/**
 * Represents a persisted SAF tree URI with metadata.
 * Used for Drive/Dropbox/OneDrive access via system picker.
 */
data class PersistedTreeUri(
    val id: Long = 0,
    val uri: Uri,
    val displayName: String,
    val providerAuthority: String?, // e.g., "com.google.android.apps.docs.storage"
    val isRemovableStorage: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a document within a SAF tree.
 */
data class SafDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/**
 * Result of a bulk import operation.
 */
data class BulkImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val failedUris: List<Uri>
)
