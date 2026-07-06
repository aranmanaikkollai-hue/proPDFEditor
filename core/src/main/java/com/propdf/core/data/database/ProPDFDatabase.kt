package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.core.data.local.*

@Database(
    entities = [
        RecentFileEntity::class,
        OcrJobEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(
    OcrConverters::class
)
abstract class ProPDFDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao
    abstract fun ocrJobDao(): OcrJobDao
}
