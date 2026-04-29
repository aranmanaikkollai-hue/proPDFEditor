package com.propdf.editor.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

// -------------------------------------------------------
// ENTITY
// -------------------------------------------------------

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey
    val uri: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val isFavourite: Boolean = false,
    val category: String = ""
)

// -------------------------------------------------------
// DAO
// -------------------------------------------------------
data class RecentFileEntity(
 @Query("UPDATE recent_files SET lastOpenedAt = 0 WHERE isFavourite = 0 AND (category IS NULL OR category = '') AND lastOpenedAt > 0")
// DATABASE
// -------------------------------------------------------

@Database(
    entities = [RecentFileEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RecentFilesDatabase : RoomDatabase() {

    abstract fun recentFilesDao(): RecentFilesDao

    companion object {

        @Volatile
        private var INSTANCE: RecentFilesDatabase? = null

        fun get(context: Context): RecentFilesDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentFilesDatabase::class.java,
                    "recent_files.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
