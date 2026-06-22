package com.propdf.nas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_nas_operations")
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "config_id")
    val configId: Long,

    @ColumnInfo(name = "operation_type")
    val operationType: String, // UPLOAD, DOWNLOAD, DELETE, LIST

    @ColumnInfo(name = "remote_path")
    val remotePath: String,

    @ColumnInfo(name = "local_uri")
    val localUri: String?,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
