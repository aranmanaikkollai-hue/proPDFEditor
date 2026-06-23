package com.propdf.storage.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.propdf.storage.data.local.entity.PersistedUriEntity

@Database(
    entities = [PersistedUriEntity::class],
    version = 1,
    exportSchema = false  // ADD THIS
)
abstract class StorageDatabase : RoomDatabase() {
    // ...
}
abstract class StorageDatabase : RoomDatabase() {
    abstract fun persistedUriDao(): PersistedUriDao

    companion object {
        @Volatile
        private var INSTANCE: StorageDatabase? = null

        fun getInstance(context: Context): StorageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StorageDatabase::class.java,
                    "storage.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
