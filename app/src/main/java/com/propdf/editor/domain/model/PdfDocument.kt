package com.propdf.editor.domain.model

import android.net.Uri

/**
 * Domain model for PDF documents.
 * Decoupled from Room entity for clean architecture.
 */
data class PdfDocument(
    val id: Long = 0,
    val uri: Uri,
    val fileName: String,
    val displayName: String,
    val fileSize: Long,
    val pageCount: Int = 0,
    val thumbnailUri: Uri? = null,
    val category: DocumentCategory = DocumentCategory.UNCATEGORIZED,
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpened: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val cloudProvider: CloudProvider? = null,
    val cloudId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)

enum class DocumentCategory(val displayName: String, val icon: String) {
    UNCATEGORIZED("Uncategorized", "folder"),
    BUSINESS("Business", "business"),
    PERSONAL("Personal", "person"),
    FINANCE("Finance", "account_balance"),
    EDUCATION("Education", "school"),
    LEGAL("Legal", "gavel"),
    MEDICAL("Medical", "medical_services"),
    WORK("Work", "work"),
    RECEIPTS("Receipts", "receipt"),
    CONTRACTS("Contracts", "description")
}

enum class CloudProvider {
    GOOGLE_DRIVE, ONEDRIVE, DROPBOX, LOCAL
}

enum class SyncStatus {
    SYNCED, PENDING_UPLOAD, PENDING_DOWNLOAD, CONFLICT, ERROR
}

enum class SortOption {
    NAME_ASC, NAME_DESC, DATE_MODIFIED, DATE_CREATED, SIZE, LAST_OPENED
}

enum class FilterOption {
    ALL, PDF, FAVORITES, RECENT, CLOUD, LOCAL
}
