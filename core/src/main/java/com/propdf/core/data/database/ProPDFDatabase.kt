package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.core.data.database.dao.*
import com.propdf.core.data.database.entity.*

@Database(
    entities = [
        RecentFileEntity::class,
        BookmarkEntity::class,
        OcrRecordEntity::class,
        SignatureEntity::class,
        ScanRecordEntity::class,
        CollectionEntity::class,
        ReadingProgressEntity::class,
        RecycleBinEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ProPDFDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun ocrRecordDao(): OcrRecordDao
    abstract fun signatureDao(): SignatureDao
    abstract fun scanRecordDao(): ScanRecordDao
    abstract fun collectionDao(): CollectionDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun recycleBinDao(): RecycleBinDao
}
