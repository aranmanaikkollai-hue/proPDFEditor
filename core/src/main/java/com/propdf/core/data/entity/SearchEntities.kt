package com.propdf.core.data.entity

import androidx.room.*

/**
 * FTS4 virtual table for full-text search indexing of PDF page text.
 * Room automatically creates the FTS4 shadow table.
 */
@Entity(tableName = "search_index")
@Fts4(contentEntity = SearchIndexContent::class)
data class SearchIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    @ColumnInfo(name = "page_text")
    val pageText: String
)

/**
 * Content entity that backs the FTS4 virtual table.
 * This stores the actual data while FTS4 handles the search index.
 */
@Entity(tableName = "search_index_content")
data class SearchIndexContent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    @ColumnInfo(name = "page_text")
    val pageText: String
)

/**
 * Recent search history entity.
 */
@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "query")
    val query: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "result_count")
    val resultCount: Int
)
