package com.propdf.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecentFileEntity::class,
        CompressionHistoryEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(CompressionConverters::class, DateConverters::class)
abstract class ProPDFDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun compressionHistoryDao(): CompressionHistoryDao
}

class DateConverters {
    @androidx.room.TypeConverter
    fun fromTimestamp(value: Long?): java.util.Date? {
        return value?.let { java.util.Date(it) }
    }

    @androidx.room.TypeConverter
    fun dateToTimestamp(date: java.util.Date?): Long? {
        return date?.time
    }
}
