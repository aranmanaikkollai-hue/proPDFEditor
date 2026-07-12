package com.propdf.editor.domain.model

import android.net.Uri

data class PdfDocument(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val fileSize: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val category: DocumentCategory = DocumentCategory.UNCATEGORIZED,
    val cloudProvider: String? = null,
    val pageCount: Int = 0
)

enum class DocumentCategory(val displayName: String) {
    UNCATEGORIZED("Uncategorized"),
    WORK("Work"),
    PERSONAL("Personal"),
    FINANCE("Finance"),
    EDUCATION("Education"),
    HEALTH("Health"),
    LEGAL("Legal"),
    OTHER("Other")
}

// StorageStats and Folder used to be declared here too, but they're also
// declared (with more fields, actually used by FolderRepositoryImpl and
// StorageAnalyzerScreen) in their own StorageStats.kt and Folder.kt files in
// this same package. Two data classes with the same name in the same package
// is a Kotlin "Conflicting declarations" compile error - kapt's opaque
// "Could not load module <Error module>" message was masking this.
