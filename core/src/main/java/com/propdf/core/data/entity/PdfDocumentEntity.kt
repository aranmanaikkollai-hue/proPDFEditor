package com.propdf.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pdf_documents",
    indices = [
        Index(value = ["uri_string"], unique = true),
        Index(value = ["file_path"]),
        Index(value = ["is_favorite"]),
        Index(value = ["is_hidden"]),
        Index(value = ["is_in_recycle_bin"]),
        Index(value = ["last_opened"]),
        Index(value = ["last_modified"]),
        Index(value = ["size_bytes"]),
        Index(value = ["checksum"]),
        Index(value = ["collection_id"])
    ]
)
data class PdfDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "uri_string")
    val uriString: String,
    
    @ColumnInfo(name = "file_name")
    val fileName: String,
    
    @ColumnInfo(name = "file_path")
    val filePath: String,
    
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    
    @ColumnInfo(name = "page_count")
    val pageCount: Int = 0,
    
    @ColumnInfo(name = "last_modified")
    val lastModified: Long,
    
    @ColumnInfo(name = "last_opened")
    val lastOpened: Long? = null,
    
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,
    
    @ColumnInfo(name = "is_hidden", defaultValue = "0")
    val isHidden: Boolean = false,
    
    @ColumnInfo(name = "is_in_recycle_bin", defaultValue = "0")
    val isInRecycleBin: Boolean = false,
    
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    
    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,
    
    @ColumnInfo(name = "collection_id")
    val collectionId: Long? = null,
    
    @ColumnInfo(name = "checksum")
    val checksum: String? = null,
    
    @ColumnInfo(name = "metadata_title")
    val metadataTitle: String? = null,
    
    @ColumnInfo(name = "metadata_author")
    val metadataAuthor: String? = null,
    
    @ColumnInfo(name = "metadata_subject")
    val metadataSubject: String? = null,
    
    @ColumnInfo(name = "metadata_keywords")
    val metadataKeywords: String? = null,
    
    @ColumnInfo(name = "metadata_creation_date")
    val metadataCreationDate: Long? = null
)
