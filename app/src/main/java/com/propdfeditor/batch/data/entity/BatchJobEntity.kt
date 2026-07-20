package com.propdfeditor.batch.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.UriListConverter
import android.net.Uri

@Entity(tableName = "batch_jobs")
@TypeConverters(UriListConverter::class)
data class BatchJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: BatchJobType,
    val status: BatchJobStatus = BatchJobStatus.PENDING,
    val inputUris: List<Uri>,
    val outputUri: Uri? = null,
    val configJson: String = "{}",
    val progress: Int = 0,
    val totalItems: Int = 0,
    val processedItems: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val workRequestId: String? = null,
    val notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
)
