package com.propdf.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class DocumentTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "color")
    val color: Int
)

@Entity(
    tableName = "document_tag_cross_ref",
    primaryKeys = ["document_id", "tag_id"],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["tag_id"])
    ]
)
data class DocumentTagCrossRef(
    @ColumnInfo(name = "document_id")
    val documentId: Long,
    
    @ColumnInfo(name = "tag_id")
    val tagId: Long
)
