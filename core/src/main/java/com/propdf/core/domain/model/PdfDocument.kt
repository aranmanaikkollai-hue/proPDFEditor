package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class PdfDocument(
    val id: Long = 0,
    val uriString: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val pageCount: Int = 0,
    val lastModified: Long,
    val lastOpened: Long? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isInRecycleBin: Boolean = false,
    val deletedAt: Long? = null,
    val thumbnailUri: String? = null,
    val collectionId: Long? = null,
    val tags: List<String> = emptyList(),
    val checksum: String? = null,
    val metadataTitle: String? = null,
    val metadataAuthor: String? = null,
    val metadataSubject: String? = null,
    val metadataKeywords: String? = null,
    val metadataCreationDate: Long? = null
) : Parcelable {

    val displayName: String get() = metadataTitle?.takeIf { it.isNotBlank() } ?: fileName.removeSuffix(".pdf")
    val extension: String get() = fileName.substringAfterLast('.', "")
    val isPdf: Boolean get() = extension.equals("pdf", ignoreCase = true)
    val sizeInMb: Double get() = sizeBytes / (1024.0 * 1024.0)
    val isLargeFile: Boolean get() = sizeBytes > 50 * 1024 * 1024 // > 50MB
}

enum class SortOption {
    NAME_ASC, NAME_DESC,
    DATE_MODIFIED_ASC, DATE_MODIFIED_DESC,
    DATE_OPENED_ASC, DATE_OPENED_DESC,
    SIZE_ASC, SIZE_DESC,
    PAGE_COUNT_ASC, PAGE_COUNT_DESC
}

enum class FileTypeFilter {
    ALL, PDF, IMAGES, SCANNED, SECURED, RECENTLY_MODIFIED, LARGE_FILES
}

@Parcelize
data class DocumentCollection(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val color: Int,
    val createdAt: Long,
    val documentCount: Int = 0,
    val coverUri: String? = null
) : Parcelable

@Parcelize
data class DocumentTag(
    val id: Long = 0,
    val name: String,
    val color: Int,
    val documentCount: Int = 0
) : Parcelable

@Parcelize
data class RecentActivity(
    val id: Long = 0,
    val documentId: Long,
    val documentName: String,
    val action: ActivityAction,
    val timestamp: Long,
    val details: String? = null
) : Parcelable

enum class ActivityAction {
    OPENED, EDITED, SHARED, RENAMED, MOVED, COPIED, DELETED, RESTORED,
    FAVORITED, UNFAVORITED, TAGGED, COLLECTION_ADDED, EXPORTED, PRINTED
}

data class StorageAnalysis(
    val totalStorageBytes: Long,
    val usedStorageBytes: Long,
    val pdfFilesBytes: Long,
    val pdfFileCount: Int,
    val largestFiles: List<PdfDocument>,
    val duplicateFilesBytes: Long,
    val duplicateGroupCount: Int,
    val oldestFiles: List<PdfDocument>,
    val collectionBreakdown: Map<String, Long>
)

data class DuplicateGroup(
    val checksum: String,
    val documents: List<PdfDocument>,
    val wastedBytes: Long
)

data class FolderNode(
    val path: String,
    val name: String,
    val parentPath: String?,
    val documentCount: Int,
    val totalSize: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val children: List<FolderNode> = emptyList()
)
