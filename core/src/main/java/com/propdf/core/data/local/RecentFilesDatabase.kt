package com.propdf.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [RecentFileEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(RecentFilesConverters::class)
abstract class RecentFilesDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao
}
