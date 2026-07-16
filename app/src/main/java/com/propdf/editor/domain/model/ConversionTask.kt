package com.propdf.editor.domain.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.util.Date

@Entity(tableName = "conversion_tasks")
data class ConversionTask(
    @PrimaryKey
    val id: String,
    val conversionType: ConversionType,
    val sourceUris: List<String>,
    val outputUri: String?,
    val status: ConversionStatus,
    val progress: Int,
    val errorMessage: String?,
    val createdAt: Date,
    val completedAt: Date?,
    val outputFileName: String
)

enum class ConversionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromConversionType(value: String): ConversionType {
        return ConversionType.valueOf(value)
    }

    @TypeConverter
    fun conversionTypeToString(type: ConversionType): String {
        return type.name
    }

    @TypeConverter
    fun fromConversionStatus(value: String): ConversionStatus {
        return ConversionStatus.valueOf(value)
    }

    @TypeConverter
    fun conversionStatusToString(status: ConversionStatus): String {
        return status.name
    }

    @TypeConverter
    fun fromStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }

    @TypeConverter
    fun stringListToString(list: List<String>): String {
        return list.joinToString(",")
    }
}
