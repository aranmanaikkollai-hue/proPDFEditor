package com.propdf.editor.data.local.db

import androidx.room.TypeConverter
import com.propdf.editor.data.local.entity.IndexingStatus

class Converters {
    @TypeConverter
    fun fromIndexingStatus(status: IndexingStatus): String = status.name

    @TypeConverter
    fun toIndexingStatus(name: String): IndexingStatus = 
        IndexingStatus.values().find { it.name == name } ?: IndexingStatus.PENDING
}
