package com.propdf.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_activities",
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["timestamp"])
    ]
)
data class RecentActivityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "document_id")
    val documentId: Long,
    
    @ColumnInfo(name = "document_name")
    val documentName: String,
    
    @ColumnInfo(name = "action")
    val action: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "details")
    val details: String? = null
)
