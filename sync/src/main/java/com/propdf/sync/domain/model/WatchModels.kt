package com.propdf.sync.domain.model

import android.net.Uri

/**
 * A folder being monitored for new PDFs.
 */
data class WatchedFolder(
    val id: Long = 0,
    val treeUri: Uri,
    val displayName: String,
    val isActive: Boolean = true,
    val autoImport: Boolean = true,
    val lastCheckedAt: Long = 0,
    val importedCount: Int = 0
)

/**
 * State of a watched folder at last check.
 */
data class FolderState(
    val folderId: Long,
    val documentUris: Set<String>, // Set of document URI strings
    val checkedAt: Long
)
