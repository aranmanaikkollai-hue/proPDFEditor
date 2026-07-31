package com.propdf.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created bookmark on a specific page of a document.
 *
 * Keyed by [uriString] rather than a foreign key into pdf_documents, matching
 * the pattern already used elsewhere in this database (see PdfDocumentEntity's
 * own uri_string index) — a document doesn't need a pdf_documents row to be
 * bookmarkable (e.g. a file opened directly via a content:// URI that hasn't
 * been indexed yet).
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["uri_string", "page_index"], unique = true),
        Index(value = ["uri_string"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "uri_string")
    val uriString: String,

    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
