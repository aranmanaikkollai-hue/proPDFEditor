package com.propdf.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [OcrJobEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(OcrConverters::class)
abstract class OcrDatabase : RoomDatabase() {
    abstract fun ocrJobDao(): OcrJobDao
}
