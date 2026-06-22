package com.propdf.sync.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.propdf.sync.data.local.entity.FolderStateEntity
import com.propdf.sync.data.local.entity.WatchedFolderEntity

@Database(
    entities = [WatchedFolderEntity::class, FolderStateEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun watchedFolderDao(): WatchedFolderDao
    abstract fun folderStateDao(): FolderStateDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
