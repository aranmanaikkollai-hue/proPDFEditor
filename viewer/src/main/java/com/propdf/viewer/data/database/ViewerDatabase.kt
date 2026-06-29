package com.propdf.viewer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, RecentPageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ViewerDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun recentPageDao(): RecentPageDao
}
