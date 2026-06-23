package com.propdf.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.propdf.core.data.entity.RecentSearchEntity
import com.propdf.core.data.entity.SearchIndexContent
import com.propdf.core.data.entity.SearchIndexEntity

/**
 * Room database for PDF search indexing.
 * Uses FTS4 for full-text search capabilities.
 */
@Database(
    entities = [
        SearchIndexEntity::class,
        SearchIndexContent::class,
        RecentSearchEntity::class
    ],
    version = 1,
    exportSchema = false
)
@Database(
    entities = [SearchIndexEntity::class, SearchIndexContent::class, RecentSearchEntity::class],
    version = 1,
    exportSchema = false  // ADD THIS
)
abstract class SearchDatabase : RoomDatabase() {
    // ...
}
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao

    companion object {
        @Volatile
        private var INSTANCE: SearchDatabase? = null

        fun getInstance(context: Context): SearchDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchDatabase::class.java,
                    "pdf_search.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
